/*
 * Host test for fakepulse. Loads a libpulse.so.0 with dlopen and drives it the
 * way Spotify Soloist does (call order taken from a FAKEPULSE_LOG trace on the
 * device), with FAKEPULSE_OUT=file:<path> so the PCM can be inspected.
 *
 *   harness <libpulse.so.0> <symbols.txt> <out.raw>
 */
#include <pulse/pulseaudio.h>

#include <dlfcn.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define RATE 44100
#define CH 2
#define IN_FRAME (CH * sizeof(float))
#define OUT_FRAME (CH * sizeof(int16_t))

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
    X(pa_stream_connect_playback)                                                                                      \
    X(pa_stream_get_state)                                                                                             \
    X(pa_stream_get_index)                                                                                             \
    X(pa_stream_cork)                                                                                                  \
    X(pa_stream_flush)                                                                                                 \
    X(pa_stream_writable_size)                                                                                         \
    X(pa_stream_write)                                                                                                 \
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

static pa_threaded_mainloop *ml;

static void context_state_cb(pa_context *c, void *ud) { f_pa_threaded_mainloop_signal(ml, 0); }
static void stream_state_cb(pa_stream *s, void *ud) { f_pa_threaded_mainloop_signal(ml, 0); }

/* 0.5 s of silence followed by 0.5 s of a 1 kHz tone at half scale. */
static float *make_signal(size_t frames) {
    float *buf = malloc(frames * IN_FRAME);
    for (size_t i = 0; i < frames; i++) {
        float v = i < frames / 2 ? 0.0f : 0.5f * (float)sin(2 * M_PI * 1000.0 * i / RATE);
        for (int c = 0; c < CH; c++) buf[i * CH + c] = v;
    }
    return buf;
}

/* Writes frames as a client polling writable_size would; returns when queued. */
static void write_frames(pa_stream *s, const float *buf, size_t frames) {
    size_t off = 0, total = frames * IN_FRAME;
    while (off < total) {
        f_pa_threaded_mainloop_lock(ml);
        size_t n = f_pa_stream_writable_size(s);
        if (n > total - off) n = total - off;
        n -= n % IN_FRAME;
        if (n) f_pa_stream_write(s, (const char *)buf + off, n, NULL, 0, PA_SEEK_RELATIVE);
        f_pa_threaded_mainloop_unlock(ml);
        off += n;
        if (!n) sleep_ms(5);
    }
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

static void drop(pa_operation *o) {
    if (o) f_pa_operation_unref(o);
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: %s <libpulse.so.0> <symbols.txt> <out.raw>\n", argv[0]);
        return 2;
    }
    const char *out = argv[3];
    char env[1024];
    snprintf(env, sizeof env, "file:%s", out);
    setenv("FAKEPULSE_OUT", env, 1);
    unlink(out);

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

    /* Context: CONNECTING synchronously, READY after waiting on the loop. */
    ml = f_pa_threaded_mainloop_new();
    f_pa_threaded_mainloop_start(ml);
    f_pa_threaded_mainloop_lock(ml);
    pa_context *ctx = f_pa_context_new(f_pa_threaded_mainloop_get_api(ml), "spotify");
    f_pa_context_set_state_callback(ctx, context_state_cb, NULL);
    f_pa_context_connect(ctx, NULL, PA_CONTEXT_NOAUTOSPAWN, NULL);
    pa_context_state_t cs = f_pa_context_get_state(ctx);
    CHECK(cs == PA_CONTEXT_CONNECTING, "context is CONNECTING right after connect (state %d)", cs);
    double deadline = now_s() + 2;
    while ((cs = f_pa_context_get_state(ctx)) != PA_CONTEXT_READY && PA_CONTEXT_IS_GOOD(cs) && now_s() < deadline)
        f_pa_threaded_mainloop_wait(ml);
    CHECK(cs == PA_CONTEXT_READY, "context reaches READY (state %d)", cs);

    /* Stream, set up the way Soloist does it. */
    drop(f_pa_context_subscribe(ctx, PA_SUBSCRIPTION_MASK_SINK_INPUT, NULL, NULL));
    pa_proplist *pl = f_pa_proplist_new();
    f_pa_proplist_sets(pl, PA_PROP_MEDIA_ROLE, "music");
    pa_sample_spec ss = {PA_SAMPLE_FLOAT32LE, RATE, CH};
    pa_stream *s = f_pa_stream_new_with_proplist(ctx, "Spotify", &ss, NULL, pl);
    f_pa_proplist_free(pl);
    f_pa_stream_set_state_callback(s, stream_state_cb, NULL);
    pa_buffer_attr attr = {(uint32_t)-1, (uint32_t)-1, 0, (uint32_t)-1, (uint32_t)-1};
    f_pa_stream_connect_playback(s, NULL, &attr, (pa_stream_flags_t)0x200b, NULL, NULL);
    pa_stream_state_t st;
    deadline = now_s() + 2;
    while ((st = f_pa_stream_get_state(s)) != PA_STREAM_READY && PA_STREAM_IS_GOOD(st) && now_s() < deadline)
        f_pa_threaded_mainloop_wait(ml);
    CHECK(st == PA_STREAM_READY, "stream reaches READY (state %d)", st);
    size_t max_writable = f_pa_stream_writable_size(s);
    CHECK(max_writable > 0, "writable_size before any write is %zu", max_writable);

    drop(f_pa_stream_cork(s, 1, NULL, NULL));
    pa_cvolume vol;
    f_pa_cvolume_set(&vol, CH, PA_VOLUME_NORM);
    drop(f_pa_context_set_sink_input_volume(ctx, f_pa_stream_get_index(s), &vol, NULL, NULL));
    drop(f_pa_stream_cork(s, 0, NULL, NULL));
    f_pa_threaded_mainloop_unlock(ml);

    /* 1 s of audio must reach the file in real time. */
    float *sig = make_signal(RATE);
    double t0 = now_s();
    write_frames(s, sig, RATE);
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
    CHECK(quiet_peak == 0 && tone_peak > 16000 && tone_peak < 16500,
          "PCM is s16: silence peak %d, tone peak %d (want 0 and ~16384)", quiet_peak, tone_peak);

    /* Cork with audio queued: the file must stop growing. */
    write_frames(s, sig, max_writable / IN_FRAME);
    sleep_ms(100);
    long playing = file_size(out);
    CHECK(playing > size, "queued audio is playing before cork (%ld -> %ld bytes)", size, playing);
    f_pa_threaded_mainloop_lock(ml);
    drop(f_pa_stream_cork(s, 1, NULL, NULL));
    f_pa_threaded_mainloop_unlock(ml);
    sleep_ms(50); /* let a chunk already handed to the sink finish */
    long corked = file_size(out);
    sleep_ms(300);
    long later = file_size(out);
    CHECK(later == corked, "corked file does not grow (%ld -> %ld bytes)", corked, later);

    /* Flush drops what is queued, so all of the buffer is writable again. */
    f_pa_threaded_mainloop_lock(ml);
    size_t before = f_pa_stream_writable_size(s);
    drop(f_pa_stream_flush(s, NULL, NULL));
    size_t after = f_pa_stream_writable_size(s);
    drop(f_pa_stream_cork(s, 0, NULL, NULL));
    f_pa_threaded_mainloop_unlock(ml);
    CHECK(before < max_writable && after == max_writable, "flush restores writable_size (%zu -> %zu, max %zu)", before,
          after, max_writable);
    sleep_ms(100);
    CHECK(file_size(out) == later, "nothing plays after flush and uncork");

    f_pa_threaded_mainloop_lock(ml);
    f_pa_stream_disconnect(s);
    f_pa_stream_unref(s);
    f_pa_context_disconnect(ctx);
    f_pa_context_unref(ctx);
    f_pa_threaded_mainloop_unlock(ml);
    f_pa_threaded_mainloop_stop(ml);
    f_pa_threaded_mainloop_free(ml);
    free(sig);
    free(pcm);

    printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
    return failures ? 1 : 0;
}
