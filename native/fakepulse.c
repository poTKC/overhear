/*
 * fakepulse: a minimal libpulse.so.0 replacement for running Spotify Soloist
 * on Android. It implements only the client API Soloist resolves with dlsym,
 * converts the float PCM it receives to s16le and hands it to a sink:
 *
 *   FAKEPULSE_OUT=file:/path       write PCM to a file, paced in real time
 *   FAKEPULSE_OUT=unix:@name       stream framed PCM to an abstract unix socket
 *                                  (Linux; the default is unix:@st15i-player-audio)
 *   FAKEPULSE_OUT=unix:/path       the same over a pathname socket (host tests)
 *   FAKEPULSE_LATENCY_US=n         output latency reported for the socket sink
 *                                  (default 250000: socket plus AudioTrack buffer)
 *   FAKEPULSE_LOG=1                trace calls on stderr
 *
 * The socket sink writes blocking, so the app's AudioTrack provides the flow
 * control. If the app is not listening, goes away, or stops reading for 2 s,
 * audio is dropped in real time and the connect is retried every 500 ms; each
 * new connection starts with an 'H' frame (and 'C' 1 if the stream is corked).
 *
 * Socket framing (little endian): u8 type, u32 length, payload.
 *   'H' header: u32 rate, u32 channels (s16le samples follow in 'P' frames)
 *   'P' pcm payload
 *   'F' flush (drop anything buffered)
 *   'C' cork: u8 1 = pause, 0 = resume
 *
 * Volume: pa_context_set_sink_input_volume is stored, reported by
 * pa_context_get_sink_input_info and announced as a sink input change, but
 * never applied: samples pass at unity gain (only clamped to s16). The app
 * turns Soloist's volume into the Android media volume instead.
 *
 * Locking: the main loop mutex guards every pa_* object, as in libpulse (the
 * client holds it through pa_threaded_mainloop_lock). Each sink has its own
 * mutex, always taken after the main loop one; the sink thread never takes the
 * main loop mutex. Queued callbacks hold a reference on their object, and
 * terminated objects get no further callbacks.
 */
#define _GNU_SOURCE
#include <pulse/pulseaudio.h>
#include <pulse/thread-mainloop.h>

#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <poll.h>
#include <pthread.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))

/* From PulseAudio's internal pulsecore/core-rtclock.h: a time event timeval
 * with this bit set in tv_usec is on the monotonic clock, not the wall clock. */
#define PA_TIMEVAL_RTCLOCK ((time_t)(1LU << 30))

static int g_log = -1;

static void trace(const char *fmt, ...) {
    if (g_log < 0) {
        const char *e = getenv("FAKEPULSE_LOG");
        g_log = e && *e == '1';
    }
    if (!g_log) return;
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[fakepulse] ");
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
}

static uint64_t now_usec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000ull + ts.tv_nsec / 1000;
}

/* Condition variables that time out on the monotonic clock, so a wall clock
 * change (the phone syncing its time) cannot stall the loop or the sink. */
static void cond_init(pthread_cond_t *cv) {
#ifdef __APPLE__
    pthread_cond_init(cv, NULL); /* timed waits use the relative variant */
#else
    pthread_condattr_t a;
    pthread_condattr_init(&a);
    pthread_condattr_setclock(&a, CLOCK_MONOTONIC);
    pthread_cond_init(cv, &a);
    pthread_condattr_destroy(&a);
#endif
}

static void cond_wait_until(pthread_cond_t *cv, pthread_mutex_t *mu, uint64_t due_mono) {
    uint64_t now = now_usec();
    uint64_t wait = due_mono > now ? due_mono - now : 0;
#ifdef __APPLE__
    struct timespec rel = {(time_t)(wait / 1000000), (long)(wait % 1000000) * 1000};
    pthread_cond_timedwait_relative_np(cv, mu, &rel);
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    ts.tv_sec += (time_t)(wait / 1000000);
    ts.tv_nsec += (long)(wait % 1000000) * 1000;
    if (ts.tv_nsec >= 1000000000) {
        ts.tv_sec++;
        ts.tv_nsec -= 1000000000;
    }
    pthread_cond_timedwait(cv, mu, &ts);
#endif
}

struct sink;
static void poll_streams(pa_threaded_mainloop *m);

/* ------------------------------------------------------------------ */
/* Main loop: one thread that runs queued callbacks and timers with the
 * loop lock held, the way libpulse's threaded main loop does.          */

typedef struct job job;
typedef void (*job_fn)(job *j);

struct job {
    job_fn fn;
    pa_context *ctx; /* referenced while queued */
    pa_stream *st;   /* referenced while queued */
    void *cb, *ud;
    intptr_t arg;
    job *next;
};

struct pa_time_event {
    pa_threaded_mainloop *m;
    pa_time_event_cb_t cb;
    void *userdata;
    uint64_t due; /* monotonic us, 0 = disabled */
    int rtclock;  /* the client passed PA_TIMEVAL_RTCLOCK times */
    int dead;
    pa_time_event_destroy_cb_t destroy;
    struct pa_time_event *next;
};

struct pa_defer_event {
    pa_threaded_mainloop *m;
    pa_defer_event_cb_t cb;
    void *userdata;
    int enabled;
    int dead;
    pa_defer_event_destroy_cb_t destroy;
    struct pa_defer_event *next;
};

struct pa_threaded_mainloop {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    pthread_cond_t wake;
    pthread_t thread;
    int running;
    int quit;
    job *jobs, **jobs_tail;
    struct pa_time_event *timers;
    struct pa_defer_event *defers;
    pa_stream *streams; /* streams with a sink, polled for underflow */
    pa_mainloop_api api;
};

static void job_release(job *j);

static void post(pa_threaded_mainloop *m, job_fn fn, pa_context *ctx, pa_stream *st, void *cb, void *ud,
                 intptr_t arg) {
    job *j = calloc(1, sizeof *j);
    if (!j) return;
    j->fn = fn;
    j->ctx = ctx;
    j->st = st;
    j->cb = cb;
    j->ud = ud;
    j->arg = arg;
    pthread_mutex_lock(&m->mutex);
    if (ctx) pa_context_ref(ctx);
    if (st) pa_stream_ref(st);
    *m->jobs_tail = j;
    m->jobs_tail = &j->next;
    pthread_cond_signal(&m->wake);
    pthread_mutex_unlock(&m->mutex);
}

static void reap_events(pa_threaded_mainloop *m) {
    for (struct pa_time_event **p = &m->timers; *p;) {
        struct pa_time_event *t = *p;
        if (t->dead) {
            *p = t->next;
            free(t);
        } else {
            p = &t->next;
        }
    }
    for (struct pa_defer_event **p = &m->defers; *p;) {
        struct pa_defer_event *d = *p;
        if (d->dead) {
            *p = d->next;
            free(d);
        } else {
            p = &d->next;
        }
    }
}

static void timer_now(int rtclock, struct timeval *tv) {
    if (rtclock) {
        uint64_t n = now_usec();
        tv->tv_sec = (time_t)(n / 1000000);
        tv->tv_usec = (suseconds_t)(n % 1000000) | PA_TIMEVAL_RTCLOCK;
    } else {
        gettimeofday(tv, NULL);
    }
}

static void *loop_thread(void *arg) {
    pa_threaded_mainloop *m = arg;
    pthread_mutex_lock(&m->mutex);
    while (!m->quit) {
        reap_events(m);

        job *j = m->jobs;
        if (j) {
            m->jobs = j->next;
            if (!m->jobs) m->jobs_tail = &m->jobs;
            j->fn(j);
            job_release(j);
            continue;
        }

        poll_streams(m);

        uint64_t now = now_usec();
        uint64_t next = now + 20000;
        int ran = 0;
        for (struct pa_defer_event *d = m->defers; d; d = d->next) {
            if (d->enabled && !d->dead) {
                d->cb(&m->api, d, d->userdata);
                ran = 1;
            }
        }
        for (struct pa_time_event *t = m->timers; t; t = t->next) {
            if (!t->dead && t->due && t->due <= now) {
                struct timeval tv;
                timer_now(t->rtclock, &tv);
                t->due = 0;
                t->cb(&m->api, t, &tv, t->userdata);
                ran = 1;
            } else if (!t->dead && t->due && t->due < next) {
                next = t->due;
            }
        }
        if (ran) continue;
        cond_wait_until(&m->wake, &m->mutex, next);
    }
    pthread_mutex_unlock(&m->mutex);
    return NULL;
}

/* Time events take wall clock times, or monotonic ones flagged with
 * PA_TIMEVAL_RTCLOCK (pa_rtclock_now() is CLOCK_MONOTONIC on Linux). */
static uint64_t tv_to_mono(const struct timeval *tv, int *rtclock) {
    *rtclock = 0;
    if (!tv) return 0;
    int64_t due;
    if (tv->tv_usec & PA_TIMEVAL_RTCLOCK) {
        *rtclock = 1;
        due = (int64_t)tv->tv_sec * 1000000 + (tv->tv_usec & ~PA_TIMEVAL_RTCLOCK);
    } else {
        struct timeval wall;
        gettimeofday(&wall, NULL);
        int64_t delta = ((int64_t)tv->tv_sec - wall.tv_sec) * 1000000 + (tv->tv_usec - wall.tv_usec);
        due = (int64_t)now_usec() + delta;
    }
    return due > 0 ? (uint64_t)due : 1;
}

static pa_io_event *api_io_new(pa_mainloop_api *a, int fd, pa_io_event_flags_t e, pa_io_event_cb_t cb, void *u) {
    trace("io_new fd=%d (unsupported)", fd);
    return NULL;
}
static void api_io_enable(pa_io_event *e, pa_io_event_flags_t f) {}
static void api_io_free(pa_io_event *e) {}
static void api_io_set_destroy(pa_io_event *e, pa_io_event_destroy_cb_t cb) {}

static pa_time_event *api_time_new(pa_mainloop_api *a, const struct timeval *tv, pa_time_event_cb_t cb, void *u) {
    pa_threaded_mainloop *m = a->userdata;
    struct pa_time_event *t = calloc(1, sizeof *t);
    if (!t) return NULL;
    t->m = m;
    t->cb = cb;
    t->userdata = u;
    t->due = tv_to_mono(tv, &t->rtclock);
    t->next = m->timers;
    m->timers = t;
    pthread_cond_signal(&m->wake);
    return t;
}
static void api_time_restart(pa_time_event *t, const struct timeval *tv) {
    t->due = tv_to_mono(tv, &t->rtclock);
    pthread_cond_signal(&t->m->wake);
}
static void api_time_free(pa_time_event *t) {
    t->dead = 1; /* unlinked and freed by the loop thread */
    if (t->destroy) t->destroy(&t->m->api, t, t->userdata);
}
static void api_time_set_destroy(pa_time_event *t, pa_time_event_destroy_cb_t cb) { t->destroy = cb; }

static pa_defer_event *api_defer_new(pa_mainloop_api *a, pa_defer_event_cb_t cb, void *u) {
    pa_threaded_mainloop *m = a->userdata;
    struct pa_defer_event *d = calloc(1, sizeof *d);
    if (!d) return NULL;
    d->m = m;
    d->cb = cb;
    d->userdata = u;
    d->enabled = 1;
    d->next = m->defers;
    m->defers = d;
    pthread_cond_signal(&m->wake);
    return d;
}
static void api_defer_enable(pa_defer_event *d, int b) {
    d->enabled = b;
    pthread_cond_signal(&d->m->wake);
}
static void api_defer_free(pa_defer_event *d) {
    d->dead = 1; /* unlinked and freed by the loop thread */
    if (d->destroy) d->destroy(&d->m->api, d, d->userdata);
}
static void api_defer_set_destroy(pa_defer_event *d, pa_defer_event_destroy_cb_t cb) { d->destroy = cb; }
static void api_quit(pa_mainloop_api *a, int retval) {}

EXPORT pa_threaded_mainloop *pa_threaded_mainloop_new(void) {
    pa_threaded_mainloop *m = calloc(1, sizeof *m);
    if (!m) return NULL;
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&m->mutex, &attr);
    pthread_mutexattr_destroy(&attr);
    pthread_cond_init(&m->cond, NULL);
    cond_init(&m->wake);
    m->jobs_tail = &m->jobs;
    m->api.userdata = m;
    m->api.io_new = api_io_new;
    m->api.io_enable = api_io_enable;
    m->api.io_free = api_io_free;
    m->api.io_set_destroy = api_io_set_destroy;
    m->api.time_new = api_time_new;
    m->api.time_restart = api_time_restart;
    m->api.time_free = api_time_free;
    m->api.time_set_destroy = api_time_set_destroy;
    m->api.defer_new = api_defer_new;
    m->api.defer_enable = api_defer_enable;
    m->api.defer_free = api_defer_free;
    m->api.defer_set_destroy = api_defer_set_destroy;
    m->api.quit = api_quit;
    trace("threaded_mainloop_new %p", m);
    return m;
}

EXPORT int pa_threaded_mainloop_start(pa_threaded_mainloop *m) {
    trace("threaded_mainloop_start");
    m->quit = 0;
    if (pthread_create(&m->thread, NULL, loop_thread, m) != 0) return -1;
    m->running = 1;
    return 0;
}

EXPORT void pa_threaded_mainloop_stop(pa_threaded_mainloop *m) {
    trace("threaded_mainloop_stop");
    if (!m->running) return;
    pthread_mutex_lock(&m->mutex);
    m->quit = 1;
    pthread_cond_signal(&m->wake);
    pthread_mutex_unlock(&m->mutex);
    pthread_join(m->thread, NULL);
    m->running = 0;
}

EXPORT void pa_threaded_mainloop_free(pa_threaded_mainloop *m) {
    trace("threaded_mainloop_free");
    pa_threaded_mainloop_stop(m);
    /* Jobs that never ran drop their references without calling back. */
    pthread_mutex_lock(&m->mutex);
    while (m->jobs) {
        job *j = m->jobs;
        m->jobs = j->next;
        job_release(j);
    }
    for (struct pa_time_event *t = m->timers; t; t = t->next) t->dead = 1;
    for (struct pa_defer_event *d = m->defers; d; d = d->next) d->dead = 1;
    reap_events(m);
    pthread_mutex_unlock(&m->mutex);
    pthread_mutex_destroy(&m->mutex);
    pthread_cond_destroy(&m->cond);
    pthread_cond_destroy(&m->wake);
    free(m);
}

EXPORT void pa_threaded_mainloop_lock(pa_threaded_mainloop *m) { pthread_mutex_lock(&m->mutex); }
EXPORT void pa_threaded_mainloop_unlock(pa_threaded_mainloop *m) { pthread_mutex_unlock(&m->mutex); }
EXPORT void pa_threaded_mainloop_wait(pa_threaded_mainloop *m) { pthread_cond_wait(&m->cond, &m->mutex); }
EXPORT void pa_threaded_mainloop_signal(pa_threaded_mainloop *m, int wait_for_accept) {
    pthread_cond_broadcast(&m->cond);
}
EXPORT pa_mainloop_api *pa_threaded_mainloop_get_api(pa_threaded_mainloop *m) { return &m->api; }

/* ------------------------------------------------------------------ */
/* Operations complete immediately; callbacks are delivered on the loop. */

struct pa_operation {
    int refs;
    pa_operation_state_t state;
};

static pa_operation *op_new(void) {
    pa_operation *o = calloc(1, sizeof *o);
    if (!o) return NULL;
    o->refs = 1;
    o->state = PA_OPERATION_DONE;
    return o;
}
EXPORT pa_operation_state_t pa_operation_get_state(const pa_operation *o) { return o ? o->state : PA_OPERATION_DONE; }
EXPORT void pa_operation_unref(pa_operation *o) {
    if (o && --o->refs <= 0) free(o);
}

/* ------------------------------------------------------------------ */
/* Proplists are accepted and ignored. */

struct pa_proplist {
    int dummy;
};
EXPORT pa_proplist *pa_proplist_new(void) { return calloc(1, sizeof(pa_proplist)); }
EXPORT void pa_proplist_free(pa_proplist *p) { free(p); }
EXPORT int pa_proplist_sets(pa_proplist *p, const char *key, const char *value) {
    trace("proplist_sets %s=%s", key, value);
    return 0;
}

/* ------------------------------------------------------------------ */
/* Sink: converts float to s16 and delivers it on its own thread. The sink
 * thread is the only one that writes to the output fd, so control frames are
 * requested through flags and never interleave with PCM on the socket. The
 * fd itself is only assigned or closed with mu held. */

#define RING_FRAMES (44100 * 2) /* two seconds of input at most */
#define RETRY_US 500000         /* reconnect interval for the socket sink */
#define PROBE_US 200000         /* how often an idle sink checks the peer */
#define SEND_TIMEOUT_S 2        /* a peer that stops reading this long is dropped */

typedef struct sink {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    int16_t *ring; /* interleaved s16 */
    size_t cap_frames, head, len; /* in frames */
    unsigned channels, rate;
    int corked;
    int quit;
    int send_cork, send_flush; /* control frames the sink thread still owes */
    int underflow;             /* the sink drained the ring while playing */
    int fd;
    int file_mode, unix_mode;
    char unix_name[108];
    uint64_t next_connect;  /* sink thread only: monotonic us of the next connect */
    uint64_t played_frames; /* frames handed to the output (or dropped while down) */
    uint64_t latency_us;    /* estimated output latency after hand-off */
    pthread_t thread;
} sink;

static int write_all(int fd, const void *buf, size_t n, int sock) {
    const char *p = buf;
    while (n) {
        ssize_t w;
        if (sock) {
#ifdef MSG_NOSIGNAL
            /* A vanished reader must not kill Soloist with SIGPIPE. */
            w = send(fd, p, n, MSG_NOSIGNAL);
#else
            w = send(fd, p, n, 0); /* SO_NOSIGPIPE is set on the socket */
#endif
        } else {
            w = write(fd, p, n);
        }
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1; /* includes EAGAIN from SO_SNDTIMEO */
        }
        p += w;
        n -= (size_t)w;
    }
    return 0;
}

/* mu held: forget the connection and reconnect at once. */
static void sink_drop_locked(sink *s, const char *why) {
    trace("sink disconnected: %s", why);
    close(s->fd);
    s->fd = -1;
    s->next_connect = 0;
}

/* Sink thread only, mu not held. */
static void sink_frame(sink *s, char type, const void *payload, uint32_t len) {
    int fd = s->fd; /* only this thread changes it */
    if (fd < 0) return;
    if (s->file_mode) {
        if (type == 'P') write_all(fd, payload, len, 0);
        return;
    }
    unsigned char hdr[5] = {(unsigned char)type, len & 0xff, (len >> 8) & 0xff, (len >> 16) & 0xff, (len >> 24) & 0xff};
    if (write_all(fd, hdr, 5, 1) < 0 || (len && write_all(fd, payload, len, 1) < 0)) {
        const char *why = strerror(errno);
        pthread_mutex_lock(&s->mu);
        sink_drop_locked(s, why);
        pthread_mutex_unlock(&s->mu);
    }
}

/* Sink thread only, mu held: has the app closed its end? It never sends. */
static int peer_gone(int fd) {
    struct pollfd p = {fd, POLLIN, 0};
    if (poll(&p, 1, 0) <= 0) return 0;
    char b[64];
    ssize_t r = recv(fd, b, sizeof b, MSG_DONTWAIT);
    return r == 0 || (r < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR);
}

/* Sink thread only, mu not held: connect to the app and greet it. */
static void sink_connect(sink *s) {
    s->next_connect = now_usec() + RETRY_US;
    const char *name = s->unix_name;
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    size_t n = strlen(name);
    if (n >= sizeof sa.sun_path) return;
    memcpy(sa.sun_path, name, n);
    socklen_t salen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + n);
    if (name[0] == '@') {
#ifdef __linux__
        sa.sun_path[0] = 0; /* abstract namespace */
#else
        trace("abstract socket %s needs Linux", name);
        return;
#endif
    } else {
        salen += 1; /* pathname: include the terminating NUL */
    }
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return;
    fcntl(fd, F_SETFD, FD_CLOEXEC);
    /* Keep little audio in the kernel so cork and flush act quickly. */
    int sndbuf = 8192;
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof sndbuf);
    struct timeval tmo = {SEND_TIMEOUT_S, 0};
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tmo, sizeof tmo);
#ifdef SO_NOSIGPIPE
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
#endif
    if (connect(fd, (struct sockaddr *)&sa, salen) < 0) {
        trace("connect %s failed: %s", name, strerror(errno));
        close(fd);
        return;
    }
    trace("connected to %s", name);
    pthread_mutex_lock(&s->mu);
    s->fd = fd;
    int corked = s->corked;
    s->send_cork = s->send_flush = 0; /* a new connection starts clean */
    pthread_mutex_unlock(&s->mu);
    uint32_t h[2] = {s->rate, s->channels};
    sink_frame(s, 'H', h, sizeof h);
    if (corked) {
        unsigned char v = 1;
        sink_frame(s, 'C', &v, 1);
    }
}

static void sink_open(sink *s) {
    const char *out = getenv("FAKEPULSE_OUT");
    if (!out) out = "unix:@st15i-player-audio";
    if (!strncmp(out, "file:", 5)) {
        s->fd = open(out + 5, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        s->file_mode = 1;
        s->latency_us = 0;
    } else if (!strncmp(out, "unix:", 5)) {
        snprintf(s->unix_name, sizeof s->unix_name, "%s", out + 5);
        s->unix_mode = 1;
        const char *lat = getenv("FAKEPULSE_LATENCY_US");
        s->latency_us = lat && *lat ? strtoull(lat, NULL, 10) : 250000; /* socket + AudioTrack */
    }
    trace("sink_open %s fd=%d latency=%llu", out, s->fd, (unsigned long long)s->latency_us);
}

static void *sink_thread(void *arg) {
    sink *s = arg;
    size_t chunk = s->rate / 50; /* 20 ms */
    if (chunk < 1) chunk = 1;
    int16_t *buf = malloc(chunk * s->channels * sizeof(int16_t));
    uint64_t start = 0, sent = 0;

    pthread_mutex_lock(&s->mu);
    while (!s->quit && buf) {
        if (s->unix_mode && s->fd < 0 && now_usec() >= s->next_connect) {
            pthread_mutex_unlock(&s->mu);
            sink_connect(s);
            pthread_mutex_lock(&s->mu);
            continue;
        }
        if (s->send_flush || s->send_cork) {
            int flush = s->send_flush, cork = s->send_cork;
            unsigned char v = (unsigned char)s->corked;
            s->send_flush = s->send_cork = 0;
            pthread_mutex_unlock(&s->mu);
            if (flush) sink_frame(s, 'F', NULL, 0);
            if (cork) sink_frame(s, 'C', &v, 1);
            pthread_mutex_lock(&s->mu);
            continue;
        }
        if (s->corked || s->len == 0) {
            start = 0;
            if (!s->unix_mode) {
                pthread_cond_wait(&s->cv, &s->mu);
            } else if (s->fd >= 0 && peer_gone(s->fd)) {
                sink_drop_locked(s, "peer closed");
            } else {
                cond_wait_until(&s->cv, &s->mu, s->fd < 0 ? s->next_connect : now_usec() + PROBE_US);
            }
            continue;
        }
        size_t n = s->len < chunk ? s->len : chunk;
        for (size_t i = 0; i < n; i++) {
            size_t f = (s->head + i) % s->cap_frames;
            memcpy(buf + i * s->channels, s->ring + f * s->channels, s->channels * sizeof(int16_t));
        }
        s->head = (s->head + n) % s->cap_frames;
        s->len -= n;
        if (s->len == 0) s->underflow = 1;
        pthread_mutex_unlock(&s->mu);

        /* A socket paces itself through the app's AudioTrack. A file, or a
         * socket that is down (audio is dropped), is paced by the clock. */
        sink_frame(s, 'P', buf, (uint32_t)(n * s->channels * sizeof(int16_t)));
        if (s->file_mode || s->fd < 0) {
            if (!start) {
                start = now_usec();
                sent = 0;
            }
            sent += n;
            uint64_t due = start + sent * 1000000ull / s->rate;
            uint64_t now = now_usec();
            if (due > now) usleep((useconds_t)(due - now));
        } else {
            start = 0;
        }

        pthread_mutex_lock(&s->mu);
        s->played_frames += n;
        pthread_cond_broadcast(&s->cv);
    }
    pthread_mutex_unlock(&s->mu);
    free(buf);
    return NULL;
}

static sink *sink_new(unsigned rate, unsigned channels, int corked) {
    sink *s = calloc(1, sizeof *s);
    if (!s) return NULL;
    s->ring = calloc((size_t)RING_FRAMES * channels, sizeof(int16_t));
    if (!s->ring) {
        free(s);
        return NULL;
    }
    pthread_mutex_init(&s->mu, NULL);
    cond_init(&s->cv);
    s->rate = rate;
    s->channels = channels;
    s->corked = corked;
    s->cap_frames = RING_FRAMES;
    s->fd = -1;
    sink_open(s);
    if (pthread_create(&s->thread, NULL, sink_thread, s) != 0) {
        if (s->fd >= 0) close(s->fd);
        free(s->ring);
        free(s);
        return NULL;
    }
    return s;
}

static void sink_free(sink *s) {
    pthread_mutex_lock(&s->mu);
    s->quit = 1;
    if (s->unix_mode && s->fd >= 0) shutdown(s->fd, SHUT_RDWR); /* unblock a pending send */
    pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->mu);
    pthread_join(s->thread, NULL);
    if (s->fd >= 0) close(s->fd);
    pthread_mutex_destroy(&s->mu);
    pthread_cond_destroy(&s->cv);
    free(s->ring);
    free(s);
}

/* ------------------------------------------------------------------ */
/* Context */

struct pa_context {
    int refs;
    pa_threaded_mainloop *m;
    pa_context_state_t state;
    pa_context_notify_cb_t state_cb;
    void *state_ud;
    pa_context_subscribe_cb_t sub_cb;
    void *sub_ud;
    struct pa_stream *stream; /* Soloist plays one stream at a time */
};

static void ctx_set_state(pa_context *c, pa_context_state_t st) {
    if (c->state == st) return;
    c->state = st;
    trace("context state -> %d", st);
    if (c->state_cb) c->state_cb(c, c->state_ud);
}

static void job_ctx_state(job *j) {
    if (PA_CONTEXT_IS_GOOD(j->ctx->state)) ctx_set_state(j->ctx, (pa_context_state_t)j->arg);
}

static void job_ctx_success(job *j) {
    pa_context_success_cb_t cb = (pa_context_success_cb_t)j->cb;
    if (cb && PA_CONTEXT_IS_GOOD(j->ctx->state)) cb(j->ctx, 1, j->ud);
}

static void stream_terminate(pa_stream *s);

EXPORT pa_context *pa_context_new(pa_mainloop_api *api, const char *name) {
    pa_context *c = calloc(1, sizeof *c);
    if (!c) return NULL;
    c->refs = 1;
    c->m = api->userdata;
    c->state = PA_CONTEXT_UNCONNECTED;
    trace("context_new %s", name ? name : "");
    return c;
}
EXPORT pa_context *pa_context_ref(pa_context *c) {
    c->refs++;
    return c;
}
EXPORT void pa_context_unref(pa_context *c) {
    if (c && --c->refs <= 0) free(c);
}
EXPORT void pa_context_set_state_callback(pa_context *c, pa_context_notify_cb_t cb, void *ud) {
    trace("context_set_state_callback %p", (void *)cb);
    c->state_cb = cb;
    c->state_ud = ud;
}
EXPORT int pa_context_connect(pa_context *c, const char *server, pa_context_flags_t flags, const pa_spawn_api *api) {
    trace("context_connect server=%s flags=%x", server ? server : "(null)", flags);
    /* libpulse reports CONNECTING before pa_context_connect() returns. */
    ctx_set_state(c, PA_CONTEXT_CONNECTING);
    post(c->m, job_ctx_state, c, NULL, NULL, NULL, PA_CONTEXT_AUTHORIZING);
    post(c->m, job_ctx_state, c, NULL, NULL, NULL, PA_CONTEXT_SETTING_NAME);
    post(c->m, job_ctx_state, c, NULL, NULL, NULL, PA_CONTEXT_READY);
    return 0;
}
EXPORT void pa_context_disconnect(pa_context *c) {
    trace("context_disconnect");
    if (!PA_CONTEXT_IS_GOOD(c->state)) return;
    if (c->stream) stream_terminate(c->stream);
    ctx_set_state(c, PA_CONTEXT_TERMINATED);
}
EXPORT pa_context_state_t pa_context_get_state(const pa_context *c) { return c->state; }
EXPORT int pa_context_errno(const pa_context *c) { return 0; }

EXPORT void pa_context_set_subscribe_callback(pa_context *c, pa_context_subscribe_cb_t cb, void *ud) {
    c->sub_cb = cb;
    c->sub_ud = ud;
}

EXPORT pa_operation *pa_context_subscribe(pa_context *c, pa_subscription_mask_t m, pa_context_success_cb_t cb, void *ud) {
    trace("context_subscribe mask=%x", m);
    post(c->m, job_ctx_success, c, NULL, (void *)cb, ud, 0);
    return op_new();
}

/* ------------------------------------------------------------------ */
/* Stream */

struct pa_stream {
    pa_stream *poll_next; /* m->streams list: streams with a sink */
    int polled;
    int refs;
    pa_context *ctx; /* referenced */
    pa_sample_spec ss;
    pa_stream_state_t state;
    pa_stream_notify_cb_t state_cb, started_cb, underflow_cb;
    void *state_ud, *started_ud, *underflow_ud;
    pa_buffer_attr attr;
    pa_cvolume volume; /* what Soloist set; reported back, never applied */
    int corked;
    int started;
    uint64_t written_bytes; /* write index, in client format */
    pa_timing_info timing;
    sink *sink;
};

static size_t frame_bytes(const pa_sample_spec *ss) {
    size_t sample = ss->format == PA_SAMPLE_FLOAT32LE || ss->format == PA_SAMPLE_S32LE ? 4 : 2;
    return sample * ss->channels;
}

static void stream_set_state(pa_stream *s, pa_stream_state_t st) {
    if (s->state == st) return;
    s->state = st;
    trace("stream state -> %d", st);
    if (s->state_cb) s->state_cb(s, s->state_ud);
}

static void job_stream_state(job *j) {
    if (PA_STREAM_IS_GOOD(j->st->state)) stream_set_state(j->st, (pa_stream_state_t)j->arg);
}

static void job_stream_success(job *j) {
    pa_stream_success_cb_t cb = (pa_stream_success_cb_t)j->cb;
    if (cb && PA_STREAM_IS_GOOD(j->st->state)) cb(j->st, 1, j->ud);
}

static void job_stream_started(job *j) {
    if (j->st->started_cb && j->st->state == PA_STREAM_READY) j->st->started_cb(j->st, j->st->started_ud);
}

static void job_release(job *j) {
    if (j->st) pa_stream_unref(j->st);
    if (j->ctx) pa_context_unref(j->ctx);
    free(j);
}

/* Loop thread: report an underflow the sink thread flagged. */
static void stream_poll_underflow(pa_stream *s) {
    if (!s->sink) return;
    pthread_mutex_lock(&s->sink->mu);
    int u = s->sink->underflow;
    s->sink->underflow = 0;
    pthread_mutex_unlock(&s->sink->mu);
    if (!u || s->state != PA_STREAM_READY) return;
    trace("underflow%s", s->underflow_cb ? "" : " (no callback)");
    if (s->underflow_cb) s->underflow_cb(s, s->underflow_ud);
}

static void poll_streams(pa_threaded_mainloop *m) {
    for (pa_stream *s = m->streams; s; s = s->poll_next) stream_poll_underflow(s);
}

/* Stops the sink: nothing more is sent and the connection is closed. */
static void stream_stop_sink(pa_stream *s) {
    if (s->polled) {
        pa_threaded_mainloop *m = s->ctx->m;
        for (pa_stream **p = &m->streams; *p; p = &(*p)->poll_next) {
            if (*p == s) {
                *p = s->poll_next;
                break;
            }
        }
        s->polled = 0;
    }
    if (s->sink) {
        sink_free(s->sink);
        s->sink = NULL;
    }
}

static void stream_terminate(pa_stream *s) {
    if (!PA_STREAM_IS_GOOD(s->state)) return;
    stream_stop_sink(s);
    stream_set_state(s, PA_STREAM_TERMINATED);
}

EXPORT pa_stream *pa_stream_new_with_proplist(pa_context *c, const char *name, const pa_sample_spec *ss,
                                              const pa_channel_map *map, pa_proplist *p) {
    pa_stream *s = calloc(1, sizeof *s);
    if (!s) return NULL;
    s->refs = 1;
    s->ctx = pa_context_ref(c);
    s->ss = *ss;
    s->state = PA_STREAM_UNCONNECTED;
    pa_cvolume_set(&s->volume, ss->channels, PA_VOLUME_NORM);
    c->stream = s;
    trace("stream_new %s format=%d rate=%u ch=%u", name ? name : "", ss->format, ss->rate, ss->channels);
    return s;
}
EXPORT pa_stream *pa_stream_new(pa_context *c, const char *name, const pa_sample_spec *ss, const pa_channel_map *map) {
    return pa_stream_new_with_proplist(c, name, ss, map, NULL);
}
EXPORT pa_stream *pa_stream_ref(pa_stream *s) {
    s->refs++;
    return s;
}
EXPORT void pa_stream_unref(pa_stream *s) {
    if (!s || --s->refs > 0) return;
    stream_stop_sink(s);
    if (s->ctx->stream == s) s->ctx->stream = NULL;
    pa_context_unref(s->ctx);
    free(s);
}
EXPORT void pa_stream_set_state_callback(pa_stream *s, pa_stream_notify_cb_t cb, void *ud) {
    s->state_cb = cb;
    s->state_ud = ud;
}
EXPORT void pa_stream_set_started_callback(pa_stream *s, pa_stream_notify_cb_t cb, void *ud) {
    s->started_cb = cb;
    s->started_ud = ud;
}
EXPORT void pa_stream_set_underflow_callback(pa_stream *s, pa_stream_notify_cb_t cb, void *ud) {
    s->underflow_cb = cb;
    s->underflow_ud = ud;
}
EXPORT pa_stream_state_t pa_stream_get_state(const pa_stream *s) { return s->state; }
EXPORT uint32_t pa_stream_get_index(const pa_stream *s) { return 1; }

EXPORT int pa_stream_connect_playback(pa_stream *s, const char *dev, const pa_buffer_attr *attr,
                                      pa_stream_flags_t flags, const pa_cvolume *volume, pa_stream *sync) {
    if (s->state != PA_STREAM_UNCONNECTED) return -PA_ERR_BADSTATE;
    if (attr) s->attr = *attr;
    if (volume) s->volume = *volume;
    s->corked = (flags & PA_STREAM_START_CORKED) != 0;
    trace("connect_playback tlength=%u prebuf=%u minreq=%u maxlength=%u flags=%x corked=%d", s->attr.tlength,
          s->attr.prebuf, s->attr.minreq, s->attr.maxlength, flags, s->corked);
    s->sink = sink_new(s->ss.rate, s->ss.channels, s->corked);
    if (!s->sink) return -PA_ERR_INTERNAL;
    pa_threaded_mainloop *m = s->ctx->m;
    s->poll_next = m->streams;
    m->streams = s;
    s->polled = 1;
    stream_set_state(s, PA_STREAM_CREATING);
    post(m, job_stream_state, NULL, s, NULL, NULL, PA_STREAM_READY);
    return 0;
}

EXPORT int pa_stream_disconnect(pa_stream *s) {
    trace("stream_disconnect");
    if (!PA_STREAM_IS_GOOD(s->state)) return -PA_ERR_BADSTATE;
    stream_terminate(s);
    return 0;
}

static size_t target_frames(pa_stream *s) {
    size_t fb = frame_bytes(&s->ss);
    size_t t = s->attr.tlength && s->attr.tlength != (uint32_t)-1 ? s->attr.tlength / fb : s->ss.rate / 4;
    if (t < s->ss.rate / 10) t = s->ss.rate / 10;
    if (t > RING_FRAMES / 2) t = RING_FRAMES / 2;
    return t;
}

EXPORT size_t pa_stream_writable_size(const pa_stream *cs) {
    pa_stream *s = (pa_stream *)cs;
    if (!s->sink) return 0;
    pthread_mutex_lock(&s->sink->mu);
    size_t have = s->sink->len;
    pthread_mutex_unlock(&s->sink->mu);
    size_t t = target_frames(s);
    return have >= t ? 0 : (t - have) * frame_bytes(&s->ss);
}

static inline int16_t clamp16(float f) {
    if (f > 32767.0f) return 32767;
    if (f < -32768.0f) return -32768;
    return (int16_t)lrintf(f);
}

static int16_t convert(const pa_stream *s, const void *data, size_t idx) {
    switch (s->ss.format) {
    case PA_SAMPLE_FLOAT32LE: return clamp16(((const float *)data)[idx] * 32767.0f);
    case PA_SAMPLE_S16LE: return ((const int16_t *)data)[idx];
    case PA_SAMPLE_S32LE: return (int16_t)(((const int32_t *)data)[idx] >> 16);
    default: return 0;
    }
}

EXPORT int pa_stream_write(pa_stream *s, const void *data, size_t nbytes, pa_free_cb_t free_cb, int64_t offset,
                           pa_seek_mode_t seek) {
    sink *k = s->sink;
    if (!k) return -PA_ERR_BADSTATE;
    size_t fb = frame_bytes(&s->ss);
    size_t frames = nbytes / fb, done = 0;
    unsigned ch = s->ss.channels;

    pthread_mutex_lock(&k->mu);
    while (done < frames) {
        /* Block while the ring is full, like a server applying flow control;
         * a corked sink will not drain, so the rest is dropped instead. */
        while (k->len == k->cap_frames && !k->corked && !k->quit) pthread_cond_wait(&k->cv, &k->mu);
        if (k->len == k->cap_frames) {
            trace("ring full while corked: dropping %zu frames", frames - done);
            break;
        }
        size_t n = frames - done;
        if (n > k->cap_frames - k->len) n = k->cap_frames - k->len;
        size_t tail = (k->head + k->len) % k->cap_frames;
        for (size_t i = 0; i < n; i++) {
            int16_t *dst = k->ring + ((tail + i) % k->cap_frames) * ch;
            for (unsigned c = 0; c < ch; c++) dst[c] = convert(s, data, (done + i) * ch + c);
        }
        k->len += n;
        done += n;
        pthread_cond_broadcast(&k->cv);
    }
    pthread_mutex_unlock(&k->mu);

    s->written_bytes += done * fb;
    if (free_cb) free_cb((void *)data);

    if (!s->started && !s->corked && done) {
        s->started = 1;
        post(s->ctx->m, job_stream_started, NULL, s, NULL, NULL, 0);
    }
    return 0;
}

EXPORT pa_operation *pa_stream_cork(pa_stream *s, int b, pa_stream_success_cb_t cb, void *ud) {
    trace("stream_cork %d", b);
    s->corked = b;
    size_t queued = 0;
    if (s->sink) {
        pthread_mutex_lock(&s->sink->mu);
        s->sink->corked = b;
        s->sink->send_cork = 1;
        queued = s->sink->len;
        pthread_cond_broadcast(&s->sink->cv);
        pthread_mutex_unlock(&s->sink->mu);
    }
    if (!b && !s->started && queued) {
        s->started = 1;
        post(s->ctx->m, job_stream_started, NULL, s, NULL, NULL, 0);
    }
    post(s->ctx->m, job_stream_success, NULL, s, (void *)cb, ud, 0);
    return op_new();
}

EXPORT int pa_stream_is_corked(const pa_stream *s) { return s->corked; }

EXPORT pa_operation *pa_stream_flush(pa_stream *s, pa_stream_success_cb_t cb, void *ud) {
    trace("stream_flush");
    if (s->sink) {
        pthread_mutex_lock(&s->sink->mu);
        size_t dropped = s->sink->len;
        s->sink->len = 0;
        s->sink->send_flush = 1;
        pthread_cond_broadcast(&s->sink->cv);
        pthread_mutex_unlock(&s->sink->mu);
        /* Playback flush: the write index moves back to the read index. */
        s->written_bytes -= dropped * frame_bytes(&s->ss);
    }
    s->started = 0;
    post(s->ctx->m, job_stream_success, NULL, s, (void *)cb, ud, 0);
    return op_new();
}

/* PulseAudio semantics: read_index is what the sink has consumed, sink_usec
 * the output latency after it; clients subtract the latency themselves. */
static void fill_timing(pa_stream *s) {
    pa_timing_info *t = &s->timing;
    memset(t, 0, sizeof *t);
    gettimeofday(&t->timestamp, NULL);
    uint64_t played = 0, queued = 0, lat = 0;
    int connected = 0;
    if (s->sink) {
        pthread_mutex_lock(&s->sink->mu);
        played = s->sink->played_frames;
        queued = s->sink->len;
        lat = s->sink->latency_us;
        connected = s->sink->fd >= 0;
        pthread_mutex_unlock(&s->sink->mu);
    }
    t->synchronized_clocks = 1;
    t->sink_usec = lat;
    t->configured_sink_usec = lat;
    t->playing = s->sink && !s->corked && queued > 0 && connected;
    t->write_index = (int64_t)s->written_bytes;
    t->read_index = (int64_t)(played * frame_bytes(&s->ss));
}

EXPORT const pa_timing_info *pa_stream_get_timing_info(pa_stream *s) {
    fill_timing(s);
    return &s->timing;
}

EXPORT pa_operation *pa_stream_update_timing_info(pa_stream *s, pa_stream_success_cb_t cb, void *ud) {
    fill_timing(s);
    post(s->ctx->m, job_stream_success, NULL, s, (void *)cb, ud, 0);
    return op_new();
}

/* ------------------------------------------------------------------ */
/* Sink input introspection, used for volume. The volume is only stored and
 * reported back: the gain is always 1.0 and the app maps Soloist's volume onto
 * Android's STREAM_MUSIC volume instead (VolumeSync). */

static void job_sink_input_info(job *j) {
    pa_context *ctx = j->ctx;
    pa_sink_input_info_cb_t cb = (pa_sink_input_info_cb_t)j->cb;
    if (!cb || !PA_CONTEXT_IS_GOOD(ctx->state)) return;
    if (ctx->stream) {
        pa_sink_input_info info;
        memset(&info, 0, sizeof info);
        info.index = 1;
        info.name = "soloist";
        info.sample_spec = ctx->stream->ss;
        info.volume = ctx->stream->volume;
        info.has_volume = 1;
        info.volume_writable = 1;
        info.corked = ctx->stream->corked;
        pa_channel_map_init_auto(&info.channel_map, ctx->stream->ss.channels, PA_CHANNEL_MAP_DEFAULT);
        cb(ctx, &info, 0, j->ud);
    }
    cb(ctx, NULL, 1, j->ud);
}

EXPORT pa_operation *pa_context_get_sink_input_info(pa_context *c, uint32_t idx, pa_sink_input_info_cb_t cb, void *ud) {
    post(c->m, job_sink_input_info, c, NULL, (void *)cb, ud, 0);
    return op_new();
}

static void job_sub_event(job *j) {
    pa_context *ctx = j->ctx;
    if (ctx->sub_cb && PA_CONTEXT_IS_GOOD(ctx->state))
        ctx->sub_cb(ctx, PA_SUBSCRIPTION_EVENT_SINK_INPUT | PA_SUBSCRIPTION_EVENT_CHANGE, 1, ctx->sub_ud);
}

EXPORT pa_operation *pa_context_set_sink_input_volume(pa_context *c, uint32_t idx, const pa_cvolume *volume,
                                                      pa_context_success_cb_t cb, void *ud) {
    if (c->stream && volume) {
        c->stream->volume = *volume;
        trace("set_sink_input_volume avg=%u (gain stays 1.0)", pa_cvolume_avg(volume));
    }
    post(c->m, job_ctx_success, c, NULL, (void *)cb, ud, 0);
    post(c->m, job_sub_event, c, NULL, NULL, NULL, 0);
    return op_new();
}

/* ------------------------------------------------------------------ */
/* Helpers */

EXPORT pa_cvolume *pa_cvolume_set(pa_cvolume *a, unsigned channels, pa_volume_t v) {
    a->channels = (uint8_t)channels;
    for (unsigned i = 0; i < channels && i < PA_CHANNELS_MAX; i++) a->values[i] = v;
    return a;
}

EXPORT pa_volume_t pa_cvolume_avg(const pa_cvolume *a) {
    if (!a || !a->channels) return PA_VOLUME_MUTED;
    uint64_t sum = 0;
    for (unsigned i = 0; i < a->channels; i++) sum += a->values[i];
    return (pa_volume_t)(sum / a->channels);
}

EXPORT pa_channel_map *pa_channel_map_init_auto(pa_channel_map *m, unsigned channels, pa_channel_map_def_t def) {
    memset(m, 0, sizeof *m);
    m->channels = (uint8_t)channels;
    if (channels == 1) {
        m->map[0] = PA_CHANNEL_POSITION_MONO;
    } else if (channels >= 2) {
        m->map[0] = PA_CHANNEL_POSITION_FRONT_LEFT;
        m->map[1] = PA_CHANNEL_POSITION_FRONT_RIGHT;
    }
    return m;
}

EXPORT size_t pa_usec_to_bytes(pa_usec_t t, const pa_sample_spec *spec) {
    return (size_t)(t * spec->rate / PA_USEC_PER_SEC) * frame_bytes(spec);
}

EXPORT struct timeval *pa_gettimeofday(struct timeval *tv) {
    gettimeofday(tv, NULL);
    return tv;
}

EXPORT pa_usec_t pa_timeval_diff(const struct timeval *a, const struct timeval *b) {
    int64_t d = ((int64_t)a->tv_sec - b->tv_sec) * 1000000 + (a->tv_usec - b->tv_usec);
    return (pa_usec_t)(d < 0 ? -d : d);
}

EXPORT const char *pa_strerror(int error) { return error ? "fakepulse error" : "OK"; }
EXPORT const char *pa_get_library_version(void) { return "17.0.0-fakepulse"; }
