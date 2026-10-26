/*
 * Host test for fakepulse. Loads a libpulse.so.0 with dlopen and drives it the
 * way Spotify Soloist does (call order taken from a FAKEPULSE_LOG trace on the
 * device).
 *
 *   harness <libpulse.so.0> <symbols.txt> <out.raw>
 *
 * 1. File sink (FAKEPULSE_OUT=file:<out.raw>): states, real-time pacing, PCM,
 *    cork, flush, timing and underflow.
 * 2. Teardown with operations still queued, twice (ASan finds use-after-free).
 * 3. Socket sink (FAKEPULSE_OUT=unix:<out.raw>.sock): a listener thread parses
 *    the frames while the test corks, flushes, drops and wedges the connection.
 */
#include <pulse/pulseaudio.h>

#include <dlfcn.h>
#include <errno.h>
#include <stdatomic.h>
#include <math.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define RATE 44100
#define CH 2
#define IN_FRAME (CH * sizeof(float))
#define OUT_FRAME (CH * sizeof(int16_t))
#define SOLOIST_FLAGS ((pa_stream_flags_t)0x200b) /* includes PA_STREAM_START_CORKED */

/* Every function the harness calls, resolved from the library under test. */
#define FUNCS(X)                                                                                                       \
    X(pa_threaded_mainloop_new)                                                                                        \
    X(pa_threaded_mainloop_start)                                                                                      \
    X(pa_threaded_mainloop_stop)                                                                                       \
    X(pa_threaded_mainloop_free)                                                                                       \
    X(pa_threaded_mainloop_lock)                                                                                       \
    X(pa_threaded_mainloop_unlock)                                                                                     \
    X(pa_threaded_mainloop_wait)                                                                                       \
    X(pa_threaded_mainloop_signal)                                                                                     \
    X(pa_threaded_mainloop_get_api)                                                                                    \
    X(pa_context_new)                                                                                                  \
    X(pa_context_set_state_callback)                                                                                   \
    X(pa_context_connect)                                                                                              \
    X(pa_context_get_state)                                                                                            \
    X(pa_context_subscribe)                                                                                            \
    X(pa_context_set_sink_input_volume)                                                                                \
    X(pa_context_disconnect)                                                                                           \
    X(pa_context_unref)                                                                                                \
    X(pa_proplist_new)                                                                                                 \
    X(pa_proplist_sets)                                                                                                \
    X(pa_proplist_free)                                                                                                \
    X(pa_stream_new_with_proplist)                                                                                     \
    X(pa_stream_set_state_callback)                                                                                    \
    X(pa_stream_set_started_callback)                                                                                  \
    X(pa_stream_set_underflow_callback)                                                                                \
    X(pa_stream_connect_playback)                                                                                      \
    X(pa_stream_get_state)                                                                                             \
    X(pa_stream_get_index)                                                                                             \
    X(pa_stream_cork)                                                                                                  \
    X(pa_stream_flush)                                                                                                 \
    X(pa_stream_writable_size)                                                                                         \
    X(pa_stream_write)                                                                                                 \
    X(pa_stream_get_timing_info)                                                                                       \
    X(pa_stream_update_timing_info)                                                                                    \
    X(pa_stream_disconnect)                                                                                            \
    X(pa_stream_unref)                                                                                                 \
    X(pa_operation_unref)                                                                                              \
    X(pa_cvolume_set)

#define DECL(n) static __typeof__(n) *f_##n;
FUNCS(DECL)

static int failures;

#define CHECK(cond, ...)                                                                                               \
    do {                                                                                                               \
        if (cond) {                                                                                                    \
            printf("ok   ");                                                                                           \
        } else {                                                                                                       \
            printf("FAIL ");                                                                                           \
            failures++;                                                                                                \
        }                                                                                                              \
        printf(__VA_ARGS__);                                                                                           \
        putchar('\n');                                                                                                 \
        fflush(stdout);                                                                                                \
    } while (0)

static double now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static void sleep_ms(unsigned ms) {
    struct timespec ts = {ms / 1000, (long)(ms % 1000) * 1000000L};
    nanosleep(&ts, NULL);
}

static long file_size(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 ? (long)st.st_size : -1;
}

static void drop(pa_operation *o) {
    if (o) f_pa_operation_unref(o);
}

/* ------------------------------------------------------------------ */
/* A client: main loop, context and one stream, with callback counters. */

typedef struct client {
    pa_threaded_mainloop *m;
    pa_context *c;
    pa_stream *s;
    int torn;       /* set once the client has torn everything down */
    int late;       /* callbacks delivered after teardown */
    int ops;        /* operation callbacks delivered */
    int underflows; /* underflow callbacks delivered */
} client;

static void context_state_cb(pa_context *c, void *ud) {
    client *k = ud;
    if (k->torn) k->late++;
    f_pa_threaded_mainloop_signal(k->m, 0);
}
static void stream_state_cb(pa_stream *s, void *ud) {
    client *k = ud;
    if (k->torn) k->late++;
    f_pa_threaded_mainloop_signal(k->m, 0);
}
static void underflow_cb(pa_stream *s, void *ud) {
    client *k = ud;
    if (k->torn) k->late++;
    k->underflows++;
}
static void started_cb(pa_stream *s, void *ud) {
    client *k = ud;
    if (k->torn) k->late++;
}
static void stream_op_cb(pa_stream *s, int ok, void *ud) {
    client *k = ud;
    if (k->torn) k->late++;
    k->ops++;
}
static void context_op_cb(pa_context *c, int ok, void *ud) {
    client *k = ud;
    if (k->torn) k->late++;
    k->ops++;
}

/* Context and stream set up the way Soloist does it. With wait, returns once
 * both are READY; the stream starts corked. Leaves the loop locked. */
static void client_open(client *k, int wait) {
    memset(k, 0, sizeof *k);
    k->m = f_pa_threaded_mainloop_new();
    f_pa_threaded_mainloop_start(k->m);
    f_pa_threaded_mainloop_lock(k->m);
    k->c = f_pa_context_new(f_pa_threaded_mainloop_get_api(k->m), "spotify");
    f_pa_context_set_state_callback(k->c, context_state_cb, k);
    f_pa_context_connect(k->c, NULL, PA_CONTEXT_NOAUTOSPAWN, NULL);
    if (wait) {
        double deadline = now_s() + 2;
        pa_context_state_t cs;
        while ((cs = f_pa_context_get_state(k->c)) != PA_CONTEXT_READY && PA_CONTEXT_IS_GOOD(cs) &&
               now_s() < deadline)
            f_pa_threaded_mainloop_wait(k->m);
    }
    drop(f_pa_context_subscribe(k->c, PA_SUBSCRIPTION_MASK_SINK_INPUT, NULL, NULL));
    pa_proplist *pl = f_pa_proplist_new();
    f_pa_proplist_sets(pl, PA_PROP_MEDIA_ROLE, "music");
    pa_sample_spec ss = {PA_SAMPLE_FLOAT32LE, RATE, CH};
    k->s = f_pa_stream_new_with_proplist(k->c, "Spotify", &ss, NULL, pl);
    f_pa_proplist_free(pl);
    f_pa_stream_set_state_callback(k->s, stream_state_cb, k);
    f_pa_stream_set_started_callback(k->s, started_cb, k);
    f_pa_stream_set_underflow_callback(k->s, underflow_cb, k);
    pa_buffer_attr attr = {(uint32_t)-1, (uint32_t)-1, 0, (uint32_t)-1, (uint32_t)-1};
    f_pa_stream_connect_playback(k->s, NULL, &attr, SOLOIST_FLAGS, NULL, NULL);
    if (wait) {
        double deadline = now_s() + 2;
        pa_stream_state_t st;
        while ((st = f_pa_stream_get_state(k->s)) != PA_STREAM_READY && PA_STREAM_IS_GOOD(st) && now_s() < deadline)
            f_pa_threaded_mainloop_wait(k->m);
        drop(f_pa_stream_cork(k->s, 1, NULL, NULL));
        pa_cvolume vol;
        f_pa_cvolume_set(&vol, CH, PA_VOLUME_NORM);
        drop(f_pa_context_set_sink_input_volume(k->c, f_pa_stream_get_index(k->s), &vol, NULL, NULL));
    }
}

/* Soloist's per-track teardown; expects the loop locked and unlocks it. */
static void client_teardown(client *k) {
    f_pa_stream_disconnect(k->s);
    f_pa_stream_unref(k->s);
    f_pa_context_disconnect(k->c);
    f_pa_context_unref(k->c);
    k->torn = 1;
    f_pa_threaded_mainloop_unlock(k->m);
}

static void client_free(client *k) {
    f_pa_threaded_mainloop_stop(k->m);
    f_pa_threaded_mainloop_free(k->m);
}

static void cork(client *k, int b) {
    f_pa_threaded_mainloop_lock(k->m);
    drop(f_pa_stream_cork(k->s, b, NULL, NULL));
    f_pa_threaded_mainloop_unlock(k->m);
}

static pa_timing_info timing(client *k) {
    f_pa_threaded_mainloop_lock(k->m);
    pa_timing_info t = *f_pa_stream_get_timing_info(k->s);
    f_pa_threaded_mainloop_unlock(k->m);
    return t;
}

/* Writes frames as a client polling writable_size would; returns when queued. */
static void write_frames(client *k, const float *buf, size_t frames) {
    size_t off = 0, total = frames * IN_FRAME;
    while (off < total) {
        f_pa_threaded_mainloop_lock(k->m);
        size_t n = f_pa_stream_writable_size(k->s);
        if (n > total - off) n = total - off;
        n -= n % IN_FRAME;
        if (n) f_pa_stream_write(k->s, (const char *)buf + off, n, NULL, 0, PA_SEEK_RELATIVE);
        f_pa_threaded_mainloop_unlock(k->m);
        off += n;
        if (!n) sleep_ms(5);
    }
}

/* Keeps the stream fed with silence for ms milliseconds. */
static void pump(client *k, unsigned ms) {
    static float silence[RATE / 10 * CH];
    double end = now_s() + ms / 1000.0;
    while (now_s() < end) {
        f_pa_threaded_mainloop_lock(k->m);
        size_t n = f_pa_stream_writable_size(k->s);
        if (n > sizeof silence) n = sizeof silence;
        n -= n % IN_FRAME;
        if (n) f_pa_stream_write(k->s, silence, n, NULL, 0, PA_SEEK_RELATIVE);
        f_pa_threaded_mainloop_unlock(k->m);
        sleep_ms(5);
    }
}

/* 0.5 s of silence followed by 0.5 s of a 1 kHz tone at half scale. */
static float *make_signal(size_t frames) {
    float *buf = malloc(frames * IN_FRAME);
    for (size_t i = 0; i < frames; i++) {
        float v = i < frames / 2 ? 0.0f : 0.5f * (float)sin(2 * M_PI * 1000.0 * i / RATE);
        for (int c = 0; c < CH; c++) buf[i * CH + c] = v;
    }
    return buf;
}

/* ------------------------------------------------------------------ */

static void test_file(const char *out) {
    char env[1024];
    snprintf(env, sizeof env, "file:%s", out);
    setenv("FAKEPULSE_OUT", env, 1);
    unlink(out);

    client k;
    memset(&k, 0, sizeof k);
    k.m = f_pa_threaded_mainloop_new();
    f_pa_threaded_mainloop_start(k.m);
    f_pa_threaded_mainloop_lock(k.m);
    k.c = f_pa_context_new(f_pa_threaded_mainloop_get_api(k.m), "spotify");
    f_pa_context_set_state_callback(k.c, context_state_cb, &k);
    f_pa_context_connect(k.c, NULL, PA_CONTEXT_NOAUTOSPAWN, NULL);
    pa_context_state_t cs = f_pa_context_get_state(k.c);
    CHECK(cs == PA_CONTEXT_CONNECTING, "context is CONNECTING right after connect (state %d)", cs);
    f_pa_context_disconnect(k.c);
    f_pa_context_unref(k.c);
    f_pa_threaded_mainloop_unlock(k.m);
    client_free(&k);

    client_open(&k, 1);
    cs = f_pa_context_get_state(k.c);
    CHECK(cs == PA_CONTEXT_READY, "context reaches READY (state %d)", cs);
    pa_stream_state_t st = f_pa_stream_get_state(k.s);
    CHECK(st == PA_STREAM_READY, "stream reaches READY (state %d)", st);
    size_t max_writable = f_pa_stream_writable_size(k.s);
    CHECK(max_writable > 0, "writable_size before any write is %zu", max_writable);
    drop(f_pa_stream_cork(k.s, 0, NULL, NULL));
    f_pa_threaded_mainloop_unlock(k.m);

    /* 1 s of audio must reach the file in real time. */
    float *sig = make_signal(RATE);
    double t0 = now_s();
    write_frames(&k, sig, RATE);
    long want = (long)(RATE * OUT_FRAME);
    while (file_size(out) < want && now_s() - t0 < 3) sleep_ms(2);
    double took = now_s() - t0;
    long size = file_size(out);
    CHECK(size == want, "file holds %ld bytes after 1 s of audio (want %ld)", size, want);
    CHECK(took > 0.9 && took < 1.1, "1 s of audio took %.3f s to play", took);

    FILE *f = fopen(out, "rb");
    int16_t *pcm = malloc((size_t)want);
    size_t got = f ? fread(pcm, 1, (size_t)want, f) : 0;
    if (f) fclose(f);
    int quiet_peak = 0, tone_peak = 0;
    for (size_t i = 0; i < got / sizeof(int16_t); i++) {
        int v = abs(pcm[i]);
        if (i < got / sizeof(int16_t) / 2) {
            if (v > quiet_peak) quiet_peak = v;
        } else if (v > tone_peak) {
            tone_peak = v;
        }
    }
    free(pcm);
    CHECK(quiet_peak == 0 && tone_peak > 16000 && tone_peak < 16500,
          "PCM is s16: silence peak %d, tone peak %d (want 0 and ~16384)", quiet_peak, tone_peak);

    /* The ring has run dry: one underflow, not playing, read index at the end. */
    sleep_ms(60);
    pa_timing_info t = timing(&k);
    long in_bytes = (long)(RATE * IN_FRAME);
    CHECK(k.underflows == 1, "underflow reported once when the ring ran dry (%d)", k.underflows);
    CHECK(!t.playing, "timing says not playing while the ring is empty");
    CHECK(t.read_index == in_bytes && t.write_index == in_bytes,
          "timing read/write index are the bytes played/written (%lld/%lld, want %ld)", (long long)t.read_index,
          (long long)t.write_index, in_bytes);
    CHECK(t.sink_usec == 0, "file sink reports no output latency (%llu)", (unsigned long long)t.sink_usec);

    /* Cork with audio queued: the file must stop growing. */
    write_frames(&k, sig, max_writable / IN_FRAME);
    sleep_ms(100);
    long playing = file_size(out);
    CHECK(playing > size, "queued audio is playing before cork (%ld -> %ld bytes)", size, playing);
    CHECK(timing(&k).playing, "timing says playing while audio flows");
    cork(&k, 1);
    sleep_ms(50); /* let a chunk already handed to the sink finish */
    long corked = file_size(out);
    sleep_ms(300);
    long later = file_size(out);
    CHECK(later == corked, "corked file does not grow (%ld -> %ld bytes)", corked, later);

    /* A write bigger than the whole ring while corked must not block. */
    size_t big = 3 * RATE;
    float *huge = calloc(big, IN_FRAME);
    f_pa_threaded_mainloop_lock(k.m);
    t0 = now_s();
    f_pa_stream_write(k.s, huge, big * IN_FRAME, NULL, 0, PA_SEEK_RELATIVE);
    took = now_s() - t0;
    f_pa_threaded_mainloop_unlock(k.m);
    free(huge);
    CHECK(took < 0.5, "3 s write into a corked stream returns (%.3f s)", took);

    /* Flush moves the write index back to the read index; all is writable. */
    f_pa_threaded_mainloop_lock(k.m);
    pa_timing_info tb = *f_pa_stream_get_timing_info(k.s);
    size_t before = f_pa_stream_writable_size(k.s);
    drop(f_pa_stream_flush(k.s, NULL, NULL));
    size_t after = f_pa_stream_writable_size(k.s);
    pa_timing_info ta = *f_pa_stream_get_timing_info(k.s);
    drop(f_pa_stream_cork(k.s, 0, NULL, NULL));
    f_pa_threaded_mainloop_unlock(k.m);
    CHECK(before < max_writable && after == max_writable, "flush restores writable_size (%zu -> %zu, max %zu)", before,
          after, max_writable);
    CHECK(tb.write_index > tb.read_index && ta.read_index == tb.read_index && ta.write_index == tb.read_index,
          "flush: read index stays (%lld -> %lld), write index drops to it (%lld -> %lld)", (long long)tb.read_index,
          (long long)ta.read_index, (long long)tb.write_index, (long long)ta.write_index);
    sleep_ms(100);
    CHECK(file_size(out) == later, "nothing plays after flush and uncork");
    CHECK(k.underflows == 1, "no underflow from cork or flush (%d)", k.underflows);

    f_pa_threaded_mainloop_lock(k.m);
    client_teardown(&k);
    client_free(&k);
    free(sig);
}

static void test_teardown(const char *out) {
    char env[1024];
    snprintf(env, sizeof env, "file:%s", out);
    setenv("FAKEPULSE_OUT", env, 1);

    /* A played track, then operations queued right before disconnect, unref
     * and stop: none may complete afterwards or touch freed objects. */
    client k;
    client_open(&k, 1);
    drop(f_pa_stream_cork(k.s, 0, NULL, NULL));
    f_pa_threaded_mainloop_unlock(k.m);
    pump(&k, 100);
    f_pa_threaded_mainloop_lock(k.m);
    drop(f_pa_stream_cork(k.s, 1, stream_op_cb, &k));
    drop(f_pa_stream_flush(k.s, stream_op_cb, &k));
    drop(f_pa_stream_update_timing_info(k.s, stream_op_cb, &k));
    pa_cvolume vol;
    f_pa_cvolume_set(&vol, CH, PA_VOLUME_NORM / 2);
    drop(f_pa_context_set_sink_input_volume(k.c, f_pa_stream_get_index(k.s), &vol, context_op_cb, &k));
    client_teardown(&k);
    client_free(&k);
    CHECK(k.ops == 0 && k.late == 0, "teardown with queued operations: %d op and %d late callbacks (want 0)", k.ops,
          k.late);

    /* Torn down before the loop ever ran: the queued READY transitions must
     * not resurrect the context or the stream. */
    client_open(&k, 0);
    client_teardown(&k);
    sleep_ms(100);
    client_free(&k);
    CHECK(k.late == 0, "teardown before READY: %d late callbacks (want 0)", k.late);
}

/* ------------------------------------------------------------------ */
/* Socket listener: logs every frame except runs of 'P', which it counts. */

typedef struct ev {
    char type; /* frame type, or 'A' accepted, 'E' peer closed, 'K' kicked */
    uint32_t a, b;
    double t;
} ev;

static struct {
    pthread_mutex_t mu;
    int lfd, cfd;
    ev evs[512];
    int n;
    long pbytes;
    _Atomic int kick, wedge, quit; /* set by the test, read by the listener */
} L = {.mu = PTHREAD_MUTEX_INITIALIZER, .lfd = -1, .cfd = -1};

static void log_ev(char type, uint32_t a, uint32_t b) {
    pthread_mutex_lock(&L.mu);
    if (!(type == 'P' && L.n && L.evs[L.n - 1].type == 'P') && L.n < 512) L.evs[L.n++] = (ev){type, a, b, now_s()};
    pthread_mutex_unlock(&L.mu);
}

static int read_full(int fd, void *b, size_t n) {
    char *p = b;
    while (n) {
        ssize_t r = read(fd, p, n);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return -1;
        p += r;
        n -= (size_t)r;
    }
    return 0;
}

static void *listener(void *arg) {
    static unsigned char buf[1 << 20];
    while (!L.quit) {
        if (L.kick && L.cfd >= 0) {
            close(L.cfd);
            L.cfd = -1;
            L.kick = 0;
            log_ev('K', 0, 0);
        }
        int accepting = L.cfd < 0 || L.wedge; /* a wedged peer never reads again */
        struct pollfd p = {accepting ? L.lfd : L.cfd, POLLIN, 0};
        if (poll(&p, 1, 20) <= 0) continue;
        if (accepting) {
            int fd = accept(L.lfd, NULL, NULL);
            if (fd < 0) continue;
            if (L.cfd >= 0) close(L.cfd);
            L.cfd = fd;
            L.wedge = 0;
            log_ev('A', 0, 0);
            continue;
        }
        unsigned char h[5];
        int bad = read_full(L.cfd, h, 5) < 0;
        uint32_t len = bad ? 0 : h[1] | h[2] << 8 | h[3] << 16 | (uint32_t)h[4] << 24;
        if (bad || len > sizeof buf || (len && read_full(L.cfd, buf, len))) {
            close(L.cfd);
            L.cfd = -1;
            log_ev('E', 0, 0);
            continue;
        }
        uint32_t a = 0, b = 0;
        if (h[0] == 'H' && len >= 8) memcpy(&a, buf, 4), memcpy(&b, buf + 4, 4);
        if (h[0] == 'C' && len >= 1) a = buf[0];
        if (h[0] == 'P') {
            pthread_mutex_lock(&L.mu);
            L.pbytes += len;
            pthread_mutex_unlock(&L.mu);
        }
        log_ev((char)h[0], a, b);
    }
    return NULL;
}

/* Index of the first event of this type at or after `from`, waiting up to ms. */
static int wait_ev(char type, int from, unsigned ms) {
    double end = now_s() + ms / 1000.0;
    for (;;) {
        pthread_mutex_lock(&L.mu);
        int found = -1;
        for (int i = from; i < L.n && found < 0; i++)
            if (L.evs[i].type == type) found = i;
        pthread_mutex_unlock(&L.mu);
        if (found >= 0 || now_s() > end) return found;
        sleep_ms(2);
    }
}

static int ev_count(void) {
    pthread_mutex_lock(&L.mu);
    int n = L.n;
    pthread_mutex_unlock(&L.mu);
    return n;
}

static ev ev_at(int i) {
    pthread_mutex_lock(&L.mu);
    ev e = L.evs[i];
    pthread_mutex_unlock(&L.mu);
    return e;
}

static long pbytes(void) {
    pthread_mutex_lock(&L.mu);
    long n = L.pbytes;
    pthread_mutex_unlock(&L.mu);
    return n;
}

static void test_socket(const char *out) {
    char path[512];
    snprintf(path, sizeof path, "%s.sock", out);
    unlink(path);
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    if (strlen(path) >= sizeof sa.sun_path) {
        CHECK(0, "socket: path %s is too long for a unix socket", path);
        return;
    }
    sa.sun_family = AF_UNIX;
    snprintf(sa.sun_path, sizeof sa.sun_path, "%s", path);
    L.lfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (bind(L.lfd, (struct sockaddr *)&sa, sizeof sa) < 0 || listen(L.lfd, 4) < 0) {
        perror(path);
        failures++;
        return;
    }
    pthread_t th;
    pthread_create(&th, NULL, listener, NULL);

    char env[600];
    snprintf(env, sizeof env, "unix:%s", path);
    setenv("FAKEPULSE_OUT", env, 1);
    setenv("FAKEPULSE_LATENCY_US", "123000", 1);

    client k;
    client_open(&k, 1);
    drop(f_pa_stream_cork(k.s, 0, NULL, NULL));
    f_pa_threaded_mainloop_unlock(k.m);
    pump(&k, 200);
    int h = wait_ev('H', 0, 1000);
    ev e = h >= 0 ? ev_at(h) : (ev){0};
    CHECK(wait_ev('A', 0, 0) == 0 && h > 0 && e.a == RATE && e.b == CH, "socket: H frame with rate %u, %u channels",
          e.a, e.b);
    CHECK(wait_ev('P', h + 1, 1000) > h, "socket: P frames follow H");

    /* Cork: C 1, then no PCM even though the client keeps writing. */
    int from = ev_count();
    cork(&k, 1);
    int c = wait_ev('C', from, 1000);
    CHECK(c >= 0 && ev_at(c).a == 1, "socket: cork sends C 1");
    sleep_ms(50);
    long p1 = pbytes();
    pump(&k, 200);
    CHECK(pbytes() == p1 && wait_ev('P', c + 1, 0) < 0, "socket: no P while corked (%ld -> %ld bytes)", p1, pbytes());
    pa_timing_info t = timing(&k);
    CHECK(t.read_index == p1 * 2 && t.sink_usec == 123000,
          "socket: read index = bytes handed off (%lld, want %ld), sink_usec = FAKEPULSE_LATENCY_US (%llu)",
          (long long)t.read_index, p1 * 2, (unsigned long long)t.sink_usec);

    from = ev_count();
    f_pa_threaded_mainloop_lock(k.m);
    drop(f_pa_stream_flush(k.s, NULL, NULL));
    f_pa_threaded_mainloop_unlock(k.m);
    CHECK(wait_ev('F', from, 1000) >= 0, "socket: flush sends F");

    /* The app goes away while corked: reconnect resends H and C 1. */
    from = ev_count();
    double t0 = now_s();
    L.kick = 1;
    int a = wait_ev('A', from, 1500);
    h = a >= 0 ? wait_ev('H', a, 500) : -1;
    c = h >= 0 ? wait_ev('C', h, 500) : -1;
    CHECK(h > a && c > h && ev_at(c).a == 1 && ev_at(c).t - t0 < 1.2,
          "socket: reconnect after close sends H then C 1 (%.3f s)", c >= 0 ? ev_at(c).t - t0 : -1.0);

    from = ev_count();
    cork(&k, 0);
    pump(&k, 100);
    c = wait_ev('C', from, 500);
    CHECK(c >= 0 && ev_at(c).a == 0 && wait_ev('P', c, 500) > c, "socket: uncork sends C 0 and PCM resumes");

    /* The app goes away mid-stream: no SIGPIPE, and a new H. */
    from = ev_count();
    t0 = now_s();
    L.kick = 1;
    pump(&k, 200);
    a = wait_ev('A', from, 1500);
    h = a >= 0 ? wait_ev('H', a, 500) : -1;
    CHECK(h > a && ev_at(h).t - t0 < 1.2, "socket: reconnect while playing sends H (%.3f s)",
          h >= 0 ? ev_at(h).t - t0 : -1.0);
    CHECK(wait_ev('P', h + 1, 500) > h, "socket: PCM resumes after reconnect");

    /* The app stops reading but keeps the socket open: give up after the send
     * timeout and reconnect. */
    from = ev_count();
    t0 = now_s();
    L.wedge = 1;
    a = -1;
    while (a < 0 && now_s() - t0 < 4) {
        pump(&k, 100);
        a = wait_ev('A', from, 0);
    }
    h = a >= 0 ? wait_ev('H', a, 500) : -1;
    CHECK(h > a, "socket: wedged app is dropped and reconnected (%.3f s)", h >= 0 ? ev_at(h).t - t0 : -1.0);

    /* Disconnect stops the sink: the app sees the connection close. */
    from = ev_count();
    f_pa_threaded_mainloop_lock(k.m);
    f_pa_stream_disconnect(k.s);
    f_pa_threaded_mainloop_unlock(k.m);
    CHECK(wait_ev('E', from, 300) >= 0, "socket: stream disconnect closes the connection");
    f_pa_threaded_mainloop_lock(k.m);
    f_pa_stream_unref(k.s);
    f_pa_context_disconnect(k.c);
    f_pa_context_unref(k.c);
    k.torn = 1;
    f_pa_threaded_mainloop_unlock(k.m);
    client_free(&k);

    L.quit = 1;
    pthread_join(th, NULL);
    close(L.lfd);
    if (L.cfd >= 0) close(L.cfd);
    unlink(path);
    unsetenv("FAKEPULSE_LATENCY_US");
}

static int check_symbols(void *lib, const char *list) {
    FILE *f = fopen(list, "r");
    if (!f) {
        perror(list);
        return -1;
    }
    char line[128];
    int missing = 0, count = 0;
    while (fgets(line, sizeof line, f)) {
        line[strcspn(line, "\r\n")] = 0;
        if (!*line) continue;
        count++;
        if (!dlsym(lib, line)) {
            printf("     missing symbol %s\n", line);
            missing++;
        }
    }
    fclose(f);
    CHECK(missing == 0, "all %d symbols Soloist resolves are exported", count);
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: %s <libpulse.so.0> <symbols.txt> <out.raw>\n", argv[0]);
        return 2;
    }
    alarm(30); /* a hang is a failure */

    void *lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        fprintf(stderr, "dlopen: %s\n", dlerror());
        return 1;
    }
#define LOAD(n)                                                                                                        \
    if (!(f_##n = (__typeof__(n) *)dlsym(lib, #n))) {                                                                  \
        fprintf(stderr, "dlsym %s failed\n", #n);                                                                      \
        return 1;                                                                                                      \
    }
    FUNCS(LOAD)
    if (check_symbols(lib, argv[2]) < 0) return 1;

    test_file(argv[3]);
    test_teardown(argv[3]);
    test_socket(argv[3]);

    printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
    return failures ? 1 : 0;
}
