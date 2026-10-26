/*
 * fakepulse: a minimal libpulse.so.0 replacement for running Spotify Soloist
 * on Android. It implements only the client API Soloist resolves with dlsym,
 * converts the float PCM it receives to s16le and hands it to a sink:
 *
 *   FAKEPULSE_OUT=file:/path       write PCM to a file, paced in real time
 *   FAKEPULSE_OUT=unix:@name       stream framed PCM to an abstract unix socket
 *                                  (Linux only; the default is unix:@st15i-player-audio)
 *   FAKEPULSE_LATENCY_US=n         output latency reported for the socket sink
 *                                  (default 250000: socket plus AudioTrack buffer)
 *   FAKEPULSE_LOG=1                trace calls on stderr
 *
 * The socket sink writes blocking, so the app's AudioTrack provides the flow
 * control. If the app is not listening or goes away, audio is dropped in real
 * time and the connect is retried every 500 ms; each new connection starts
 * with an 'H' frame (and 'C' 1 if the stream is corked).
 *
 * Socket framing (little endian): u8 type, u32 length, payload.
 *   'H' header: u32 rate, u32 channels (s16le samples follow in 'P' frames)
 *   'P' pcm payload
 *   'F' flush (drop anything buffered)
 *   'C' cork: u8 1 = pause, 0 = resume
 */
#define _GNU_SOURCE
#include <pulse/pulseaudio.h>
#include <pulse/thread-mainloop.h>

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
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

/* ------------------------------------------------------------------ */
/* Main loop: one thread that runs queued callbacks and timers with the
 * loop lock held, the way libpulse's threaded main loop does.          */

typedef void (*job_fn)(void *a, void *b, void *c);

typedef struct job {
    job_fn fn;
    void *a, *b, *c;
    uint64_t due;
    struct job *next;
} job;

struct pa_time_event {
    pa_threaded_mainloop *m;
    pa_time_event_cb_t cb;
    void *userdata;
    uint64_t due; /* 0 = disabled */
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
    pthread_cond_t accept_cond;
    pthread_cond_t wake;
    pthread_t thread;
    int running;
    int quit;
    job *jobs;
    struct pa_time_event *timers;
    struct pa_defer_event *defers;
    pa_mainloop_api api;
};

static void post(pa_threaded_mainloop *m, uint64_t delay_us, job_fn fn, void *a, void *b, void *c) {
    job *j = calloc(1, sizeof *j);
    j->fn = fn;
    j->a = a;
    j->b = b;
    j->c = c;
    j->due = now_usec() + delay_us;
    pthread_mutex_lock(&m->mutex);
    job **p = &m->jobs;
    while (*p) p = &(*p)->next;
    *p = j;
    pthread_cond_signal(&m->wake);
    pthread_mutex_unlock(&m->mutex);
}

static void *loop_thread(void *arg) {
    pa_threaded_mainloop *m = arg;
    pthread_mutex_lock(&m->mutex);
    while (!m->quit) {
        uint64_t now = now_usec();
        uint64_t next = now + 20000;
        int ran = 0;

        for (job **p = &m->jobs; *p;) {
            job *j = *p;
            if (j->due <= now) {
                *p = j->next;
                j->fn(j->a, j->b, j->c);
                free(j);
                ran = 1;
                break; /* list may have changed under the callback */
            }
            if (j->due < next) next = j->due;
            p = &j->next;
        }
        if (ran) continue;

        for (struct pa_defer_event *d = m->defers; d; d = d->next) {
            if (d->enabled && !d->dead) {
                d->cb(&m->api, d, d->userdata);
                ran = 1;
            }
        }
        for (struct pa_time_event *t = m->timers; t; t = t->next) {
            if (!t->dead && t->due && t->due <= now) {
                struct timeval tv;
                gettimeofday(&tv, NULL);
                t->due = 0;
                t->cb(&m->api, t, &tv, t->userdata);
                ran = 1;
            } else if (!t->dead && t->due && t->due < next) {
                next = t->due;
            }
        }
        if (ran) continue;

        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        uint64_t wait = next > now ? next - now : 0;
        ts.tv_sec += wait / 1000000;
        ts.tv_nsec += (wait % 1000000) * 1000;
        if (ts.tv_nsec >= 1000000000) {
            ts.tv_sec++;
            ts.tv_nsec -= 1000000000;
        }
        pthread_cond_timedwait(&m->wake, &m->mutex, &ts);
    }
    pthread_mutex_unlock(&m->mutex);
    return NULL;
}

static uint64_t tv_to_mono(const struct timeval *tv) {
    if (!tv) return 0;
    struct timeval wall;
    gettimeofday(&wall, NULL);
    int64_t delta = ((int64_t)tv->tv_sec - wall.tv_sec) * 1000000 + (tv->tv_usec - wall.tv_usec);
    int64_t due = (int64_t)now_usec() + delta;
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
    t->m = m;
    t->cb = cb;
    t->userdata = u;
    t->due = tv_to_mono(tv);
    t->next = m->timers;
    m->timers = t;
    pthread_cond_signal(&m->wake);
    return t;
}
static void api_time_restart(pa_time_event *t, const struct timeval *tv) {
    t->due = tv_to_mono(tv);
    pthread_cond_signal(&t->m->wake);
}
static void api_time_free(pa_time_event *t) {
    t->dead = 1;
    if (t->destroy) t->destroy(&t->m->api, t, t->userdata);
}
static void api_time_set_destroy(pa_time_event *t, pa_time_event_destroy_cb_t cb) { t->destroy = cb; }

static pa_defer_event *api_defer_new(pa_mainloop_api *a, pa_defer_event_cb_t cb, void *u) {
    pa_threaded_mainloop *m = a->userdata;
    struct pa_defer_event *d = calloc(1, sizeof *d);
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
    d->dead = 1;
    if (d->destroy) d->destroy(&d->m->api, d, d->userdata);
}
static void api_defer_set_destroy(pa_defer_event *d, pa_defer_event_destroy_cb_t cb) { d->destroy = cb; }
static void api_quit(pa_mainloop_api *a, int retval) {}

EXPORT pa_threaded_mainloop *pa_threaded_mainloop_new(void) {
    pa_threaded_mainloop *m = calloc(1, sizeof *m);
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&m->mutex, &attr);
    pthread_cond_init(&m->cond, NULL);
    pthread_cond_init(&m->accept_cond, NULL);
    pthread_cond_init(&m->wake, NULL);
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
    m->running = 1;
    return pthread_create(&m->thread, NULL, loop_thread, m) == 0 ? 0 : -1;
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
    /* Timers and jobs may still be referenced by the caller; leak them. */
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
 * thread is the only one that touches the output fd, so control frames are
 * requested through flags and never interleave with PCM on the socket. */

#define RING_FRAMES (44100 * 2) /* two seconds of input at most */
#define RETRY_US 500000         /* reconnect interval for the unix sink */

typedef struct sink {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    int16_t *ring; /* interleaved s16 */
    size_t cap_frames, head, len; /* in frames */
    unsigned channels, rate;
    int corked;
    int quit;
    int send_cork, send_flush; /* control frames the sink thread still owes */
    int fd;
    int file_mode, unix_mode;
    char unix_name[108];
    uint64_t next_connect; /* monotonic us of the next connect attempt */
    uint64_t played_frames; /* frames handed to the output */
    uint64_t latency_us; /* estimated output latency after hand-off */
    pthread_t thread;
} sink;

static int write_all(int fd, const void *buf, size_t n, int sock) {
    const char *p = buf;
    while (n) {
#ifdef __linux__
        /* MSG_NOSIGNAL: a vanished reader must not kill Soloist with SIGPIPE. */
        ssize_t w = sock ? send(fd, p, n, MSG_NOSIGNAL) : write(fd, p, n);
#else
        ssize_t w = write(fd, p, n);
#endif
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += w;
        n -= (size_t)w;
    }
    return 0;
}

/* Sink thread only. On a socket error the connection is dropped and retried. */
static void sink_frame(sink *s, char type, const void *payload, uint32_t len) {
    if (s->fd < 0) return;
    if (s->file_mode) {
        if (type == 'P') write_all(s->fd, payload, len, 0);
        return;
    }
    unsigned char hdr[5] = {(unsigned char)type, len & 0xff, (len >> 8) & 0xff, (len >> 16) & 0xff, (len >> 24) & 0xff};
    if (write_all(s->fd, hdr, 5, 1) < 0 || (len && write_all(s->fd, payload, len, 1) < 0)) {
        trace("sink write failed: %s", strerror(errno));
        close(s->fd);
        s->fd = -1;
        s->next_connect = now_usec() + RETRY_US;
    }
}

/* Sink thread only: connect to the app's abstract socket and greet it. */
static void sink_connect(sink *s) {
    s->next_connect = now_usec() + RETRY_US;
#ifdef __linux__
    const char *name = s->unix_name;
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    size_t n = strlen(name);
    if (n >= sizeof sa.sun_path) n = sizeof sa.sun_path - 1;
    memcpy(sa.sun_path, name, n);
    if (name[0] == '@') sa.sun_path[0] = 0; /* abstract namespace */
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return;
    /* Keep little audio in the kernel so cork and flush act quickly. */
    int sndbuf = 8192;
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof sndbuf);
    if (connect(fd, (struct sockaddr *)&sa, (socklen_t)(offsetof(struct sockaddr_un, sun_path) + n)) < 0) {
        trace("connect %s failed: %s", name, strerror(errno));
        close(fd);
        return;
    }
    trace("connected to %s", name);
    s->fd = fd;
    uint32_t h[2] = {s->rate, s->channels};
    sink_frame(s, 'H', h, sizeof h);
    if (s->corked) {
        unsigned char v = 1;
        sink_frame(s, 'C', &v, 1);
    }
#else
    trace("unix sink needs Linux abstract sockets");
#endif
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

static void cond_wait_until(pthread_cond_t *cv, pthread_mutex_t *mu, uint64_t due_mono) {
    uint64_t now = now_usec();
    uint64_t wait = due_mono > now ? due_mono - now : 0;
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += (time_t)(wait / 1000000);
    ts.tv_nsec += (long)(wait % 1000000) * 1000;
    if (ts.tv_nsec >= 1000000000) {
        ts.tv_sec++;
        ts.tv_nsec -= 1000000000;
    }
    pthread_cond_timedwait(cv, mu, &ts);
}

static void *sink_thread(void *arg) {
    sink *s = arg;
    const size_t chunk = s->rate / 50; /* 20 ms */
    int16_t *buf = malloc(chunk * s->channels * sizeof(int16_t));
    uint64_t start = 0, sent = 0;

    pthread_mutex_lock(&s->mu);
    while (!s->quit) {
        int connected = s->fd >= 0;
        if (s->unix_mode && !connected && now_usec() >= s->next_connect) {
            s->send_cork = s->send_flush = 0; /* a new connection starts clean */
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
            if (s->unix_mode && !connected)
                cond_wait_until(&s->cv, &s->mu, s->next_connect);
            else
                pthread_cond_wait(&s->cv, &s->mu);
            continue;
        }
        size_t n = s->len < chunk ? s->len : chunk;
        for (size_t i = 0; i < n; i++) {
            size_t f = (s->head + i) % s->cap_frames;
            memcpy(buf + i * s->channels, s->ring + f * s->channels, s->channels * sizeof(int16_t));
        }
        s->head = (s->head + n) % s->cap_frames;
        s->len -= n;
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
    pthread_mutex_init(&s->mu, NULL);
    pthread_cond_init(&s->cv, NULL);
    s->rate = rate;
    s->channels = channels;
    s->corked = corked;
    s->cap_frames = RING_FRAMES;
    s->ring = calloc(s->cap_frames * channels, sizeof(int16_t));
    s->fd = -1;
    sink_open(s);
    pthread_create(&s->thread, NULL, sink_thread, s);
    return s;
}

static void sink_free(sink *s) {
    pthread_mutex_lock(&s->mu);
    s->quit = 1;
    pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->mu);
    if (s->unix_mode && s->fd >= 0) shutdown(s->fd, SHUT_RDWR); /* unblock a pending send */
    pthread_join(s->thread, NULL);
    if (s->fd >= 0) close(s->fd);
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

static void job_ctx_state(void *a, void *b, void *c) {
    pa_context *ctx = a;
    ctx->state = (pa_context_state_t)(intptr_t)b;
    trace("context state -> %d", ctx->state);
    if (ctx->state_cb) ctx->state_cb(ctx, ctx->state_ud);
}

EXPORT pa_context *pa_context_new(pa_mainloop_api *api, const char *name) {
    pa_context *c = calloc(1, sizeof *c);
    c->refs = 1;
    c->m = api->userdata;
    c->state = PA_CONTEXT_UNCONNECTED;
    trace("context_new %s", name ? name : "");
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
    job_ctx_state(c, (void *)(intptr_t)PA_CONTEXT_CONNECTING, NULL);
    post(c->m, 0, job_ctx_state, c, (void *)(intptr_t)PA_CONTEXT_AUTHORIZING, NULL);
    post(c->m, 0, job_ctx_state, c, (void *)(intptr_t)PA_CONTEXT_SETTING_NAME, NULL);
    post(c->m, 0, job_ctx_state, c, (void *)(intptr_t)PA_CONTEXT_READY, NULL);
    return 0;
}
EXPORT void pa_context_disconnect(pa_context *c) {
    trace("context_disconnect");
    c->state = PA_CONTEXT_TERMINATED;
    if (c->state_cb) c->state_cb(c, c->state_ud);
}
EXPORT pa_context_state_t pa_context_get_state(const pa_context *c) { return c->state; }
EXPORT int pa_context_errno(const pa_context *c) { return 0; }

EXPORT void pa_context_set_subscribe_callback(pa_context *c, pa_context_subscribe_cb_t cb, void *ud) {
    c->sub_cb = cb;
    c->sub_ud = ud;
}

static void job_success(void *a, void *b, void *c) {
    pa_context_success_cb_t cb = (pa_context_success_cb_t)b;
    if (cb) cb(a, 1, c);
}

EXPORT pa_operation *pa_context_subscribe(pa_context *c, pa_subscription_mask_t m, pa_context_success_cb_t cb, void *ud) {
    trace("context_subscribe mask=%x", m);
    post(c->m, 0, job_success, c, (void *)cb, ud);
    return op_new();
}

/* ------------------------------------------------------------------ */
/* Stream */

struct pa_stream {
    int refs;
    pa_context *ctx;
    pa_sample_spec ss;
    pa_stream_state_t state;
    pa_stream_notify_cb_t state_cb, started_cb, underflow_cb;
    void *state_ud, *started_ud, *underflow_ud;
    pa_buffer_attr attr;
    pa_cvolume volume;
    float gain;
    int corked;
    int started;
    uint64_t written_bytes; /* in client format */
    pa_timing_info timing;
    sink *sink;
};

static size_t frame_bytes(const pa_sample_spec *ss) {
    size_t sample = ss->format == PA_SAMPLE_FLOAT32LE || ss->format == PA_SAMPLE_S32LE ? 4 : 2;
    return sample * ss->channels;
}

static void job_stream_state(void *a, void *b, void *c) {
    pa_stream *s = a;
    s->state = (pa_stream_state_t)(intptr_t)b;
    trace("stream state -> %d", s->state);
    if (s->state_cb) s->state_cb(s, s->state_ud);
}

static void job_stream_notify(void *a, void *b, void *c) {
    pa_stream_notify_cb_t cb = (pa_stream_notify_cb_t)b;
    if (cb) cb(a, c);
}

EXPORT pa_stream *pa_stream_new_with_proplist(pa_context *c, const char *name, const pa_sample_spec *ss,
                                              const pa_channel_map *map, pa_proplist *p) {
    pa_stream *s = calloc(1, sizeof *s);
    s->refs = 1;
    s->ctx = c;
    s->ss = *ss;
    s->state = PA_STREAM_UNCONNECTED;
    s->gain = 1.0f;
    pa_cvolume_set(&s->volume, ss->channels, PA_VOLUME_NORM);
    c->stream = s;
    trace("stream_new %s format=%d rate=%u ch=%u", name ? name : "", ss->format, ss->rate, ss->channels);
    return s;
}
EXPORT pa_stream *pa_stream_new(pa_context *c, const char *name, const pa_sample_spec *ss, const pa_channel_map *map) {
    return pa_stream_new_with_proplist(c, name, ss, map, NULL);
}
EXPORT void pa_stream_unref(pa_stream *s) {
    if (!s || --s->refs > 0) return;
    if (s->ctx && s->ctx->stream == s) s->ctx->stream = NULL;
    if (s->sink) sink_free(s->sink);
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

static void apply_volume(pa_stream *s) {
    double v = (double)pa_cvolume_avg(&s->volume) / PA_VOLUME_NORM;
    s->gain = (float)(v * v * v); /* PulseAudio volumes are cubic */
}

EXPORT int pa_stream_connect_playback(pa_stream *s, const char *dev, const pa_buffer_attr *attr,
                                      pa_stream_flags_t flags, const pa_cvolume *volume, pa_stream *sync) {
    if (attr) s->attr = *attr;
    if (volume) {
        s->volume = *volume;
        apply_volume(s);
    }
    s->corked = (flags & PA_STREAM_START_CORKED) != 0;
    trace("connect_playback tlength=%u prebuf=%u minreq=%u maxlength=%u flags=%x corked=%d", s->attr.tlength,
          s->attr.prebuf, s->attr.minreq, s->attr.maxlength, flags, s->corked);
    s->sink = sink_new(s->ss.rate, s->ss.channels, s->corked);
    job_stream_state(s, (void *)(intptr_t)PA_STREAM_CREATING, NULL);
    post(s->ctx->m, 0, job_stream_state, s, (void *)(intptr_t)PA_STREAM_READY, NULL);
    return 0;
}

EXPORT int pa_stream_disconnect(pa_stream *s) {
    trace("stream_disconnect");
    s->state = PA_STREAM_TERMINATED;
    if (s->state_cb) s->state_cb(s, s->state_ud);
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

static inline int16_t to_s16(float f) {
    f *= 32767.0f;
    if (f > 32767.0f) return 32767;
    if (f < -32768.0f) return -32768;
    return (int16_t)lrintf(f);
}

EXPORT int pa_stream_write(pa_stream *s, const void *data, size_t nbytes, pa_free_cb_t free_cb, int64_t offset,
                           pa_seek_mode_t seek) {
    sink *k = s->sink;
    size_t fb = frame_bytes(&s->ss);
    size_t frames = nbytes / fb;
    unsigned ch = s->ss.channels;

    pthread_mutex_lock(&k->mu);
    /* Block while the ring is full, like a server applying flow control. */
    while (k->len + frames > k->cap_frames && !k->quit) pthread_cond_wait(&k->cv, &k->mu);
    size_t tail = (k->head + k->len) % k->cap_frames;
    for (size_t i = 0; i < frames; i++) {
        int16_t *dst = k->ring + ((tail + i) % k->cap_frames) * ch;
        for (unsigned c = 0; c < ch; c++) {
            if (s->ss.format == PA_SAMPLE_FLOAT32LE) {
                dst[c] = to_s16(((const float *)data)[i * ch + c] * s->gain);
            } else if (s->ss.format == PA_SAMPLE_S16LE) {
                dst[c] = (int16_t)(((const int16_t *)data)[i * ch + c] * s->gain);
            } else if (s->ss.format == PA_SAMPLE_S32LE) {
                dst[c] = (int16_t)((((const int32_t *)data)[i * ch + c] >> 16) * s->gain);
            }
        }
    }
    k->len += frames;
    pthread_cond_broadcast(&k->cv);
    pthread_mutex_unlock(&k->mu);

    s->written_bytes += frames * fb;
    if (free_cb) free_cb((void *)data);

    if (!s->started && !s->corked) {
        s->started = 1;
        post(s->ctx->m, 0, job_stream_notify, s, (void *)s->started_cb, s->started_ud);
    }
    return 0;
}

EXPORT pa_operation *pa_stream_cork(pa_stream *s, int b, pa_stream_success_cb_t cb, void *ud) {
    trace("stream_cork %d", b);
    s->corked = b;
    if (s->sink) {
        pthread_mutex_lock(&s->sink->mu);
        s->sink->corked = b;
        s->sink->send_cork = 1;
        pthread_cond_broadcast(&s->sink->cv);
        pthread_mutex_unlock(&s->sink->mu);
    }
    if (!b && !s->started && s->sink && s->sink->len) {
        s->started = 1;
        post(s->ctx->m, 0, job_stream_notify, s, (void *)s->started_cb, s->started_ud);
    }
    post(s->ctx->m, 0, job_success, s, (void *)cb, ud);
    return op_new();
}

EXPORT int pa_stream_is_corked(const pa_stream *s) { return s->corked; }

EXPORT pa_operation *pa_stream_flush(pa_stream *s, pa_stream_success_cb_t cb, void *ud) {
    trace("stream_flush");
    if (s->sink) {
        pthread_mutex_lock(&s->sink->mu);
        size_t dropped = s->sink->len;
        s->sink->len = 0;
        s->sink->played_frames += dropped; /* read index jumps to write index */
        s->sink->send_flush = 1;
        pthread_cond_broadcast(&s->sink->cv);
        pthread_mutex_unlock(&s->sink->mu);
    }
    s->started = 0;
    post(s->ctx->m, 0, job_success, s, (void *)cb, ud);
    return op_new();
}

static void fill_timing(pa_stream *s) {
    pa_timing_info *t = &s->timing;
    memset(t, 0, sizeof *t);
    gettimeofday(&t->timestamp, NULL);
    size_t fb = frame_bytes(&s->ss);
    uint64_t played = 0, queued = 0, lat = 0;
    if (s->sink) {
        pthread_mutex_lock(&s->sink->mu);
        played = s->sink->played_frames;
        queued = s->sink->len;
        lat = s->sink->latency_us;
        pthread_mutex_unlock(&s->sink->mu);
    }
    uint64_t lat_frames = lat * s->ss.rate / 1000000;
    uint64_t heard = played > lat_frames ? played - lat_frames : 0;
    t->synchronized_clocks = 1;
    t->sink_usec = lat;
    t->playing = !s->corked && (queued > 0 || played > 0);
    t->write_index = (int64_t)s->written_bytes;
    t->read_index = (int64_t)(heard * fb);
    t->configured_sink_usec = lat;
}

EXPORT const pa_timing_info *pa_stream_get_timing_info(pa_stream *s) {
    fill_timing(s);
    return &s->timing;
}

EXPORT pa_operation *pa_stream_update_timing_info(pa_stream *s, pa_stream_success_cb_t cb, void *ud) {
    fill_timing(s);
    post(s->ctx->m, 0, job_success, s, (void *)cb, ud);
    return op_new();
}

/* ------------------------------------------------------------------ */
/* Sink input introspection, used for volume. */

static void job_sink_input_info(void *a, void *b, void *c) {
    pa_context *ctx = a;
    pa_sink_input_info_cb_t cb = (pa_sink_input_info_cb_t)b;
    if (!cb) return;
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
        cb(ctx, &info, 0, c);
    }
    cb(ctx, NULL, 1, c);
}

EXPORT pa_operation *pa_context_get_sink_input_info(pa_context *c, uint32_t idx, pa_sink_input_info_cb_t cb, void *ud) {
    post(c->m, 0, job_sink_input_info, c, (void *)cb, ud);
    return op_new();
}

static void job_sub_event(void *a, void *b, void *c) {
    pa_context *ctx = a;
    if (ctx->sub_cb) ctx->sub_cb(ctx, PA_SUBSCRIPTION_EVENT_SINK_INPUT | PA_SUBSCRIPTION_EVENT_CHANGE, 1, ctx->sub_ud);
}

EXPORT pa_operation *pa_context_set_sink_input_volume(pa_context *c, uint32_t idx, const pa_cvolume *volume,
                                                      pa_context_success_cb_t cb, void *ud) {
    if (c->stream && volume) {
        c->stream->volume = *volume;
        apply_volume(c->stream);
        trace("set_sink_input_volume avg=%u gain=%.3f", pa_cvolume_avg(volume), c->stream->gain);
    }
    post(c->m, 0, job_success, c, (void *)cb, ud);
    post(c->m, 0, job_sub_event, c, NULL, NULL);
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
