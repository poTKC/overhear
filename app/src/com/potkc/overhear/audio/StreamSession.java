package com.potkc.overhear.audio;

/**
 * Turns the frames of one fakepulse connection into track and audio focus
 * operations. Pure Java (the Android side is behind Track, Focus and Host) so
 * it runs in the host tests.
 *
 * Playback starts lazily: 'H' and an uncork only make the session ready, and
 * the first 'P' after them requests focus and plays. A reconnect while corked
 * ('H' then 'C' 1) therefore never touches focus or plays. PCM that cannot be
 * played (before 'H', while corked, after an unsupported 'H', or after close)
 * is discarded rather than blocking the reader, which must keep draining the
 * socket. Fakepulse is paced only by the socket while connected, so audio of
 * an unsupported format is discarded at the rate it would play; otherwise
 * Soloist would run ahead of real time.
 *
 * A failed write releases the track and throws, so the client is dropped and
 * reconnects with a new 'H' (and gets a new track, e.g. after mediaserver
 * restarts).
 *
 * All methods except close() run on the reading thread.
 */
public final class StreamSession implements FrameReader.Listener {
    private static final long STATS_INTERVAL_MS = 30000;

    /** An output stream of s16le PCM, created stopped. */
    public interface Track {
        void play();

        void pause();

        /** Drops queued audio; only called while paused. */
        void flush();

        /** Blocks until written; returns the bytes written, 0 if stopped. */
        int write(byte[] buf, int off, int len);

        /** Frames played so far, for logging. */
        long head();

        void release();
    }

    public interface Focus {
        /** @return whether focus was granted */
        boolean request();

        void abandon();
    }

    public interface Host {
        /** @return a stopped track, or null if the format is not supported */
        Track createTrack(int rate, int channels);

        void onPlaying(boolean playing);

        void log(String msg);

        void debug(String msg);

        long uptimeMs();

        void sleep(long ms);
    }

    private final Host host;
    private final Focus focus;

    private volatile boolean closed;
    private Track track;
    private int rate, channels;
    private boolean unsupported;
    private boolean corked;
    private boolean started; // play() called since the last pause
    private boolean reported; // onPlaying(true) sent
    private boolean focused;
    private String discardReason;
    private long bytesPerSecond;
    private long paceBytes; // discarded but not yet slept for
    private long frames, bytes, discarded, statsAt;

    public StreamSession(Host host, Focus focus) {
        this.host = host;
        this.focus = focus;
    }

    /** Any thread: stop writing; PCM still being read is discarded. */
    public void close() {
        closed = true;
    }

    /** Releases the track and focus at the end of the connection. Idempotent. */
    public void finish() {
        if (track != null || frames > 0) logStats("final");
        releaseTrack();
        if (focused) {
            focus.abandon();
            focused = false;
        }
        setReported(false);
    }

    @Override
    public void header(int rate, int channels) {
        host.log("H rate " + rate + " channels " + channels);
        if (rate <= 0 || channels <= 0) {
            throw new IllegalArgumentException("unusable header: rate " + rate + ", channels " + channels);
        }
        if (track != null && rate == this.rate && channels == this.channels) return;
        releaseTrack();
        this.rate = rate;
        this.channels = channels;
        unsupported = false;
        discardReason = null;
        bytesPerSecond = (long) rate * channels * 2; // < 2^63 for int inputs
        paceBytes = 0;
        Track t = channels == 1 || channels == 2 ? host.createTrack(rate, channels) : null;
        if (t == null) {
            unsupported = true;
            host.log("unsupported format " + rate + " Hz, " + channels + " channels");
            return;
        }
        track = t;
    }

    @Override
    public void pcm(byte[] buf, int off, int len) {
        String why = closed ? "after close" : track == null ? (unsupported ? "of unsupported format" : "before header")
                : corked ? "while corked" : null;
        if (why != null) {
            if (!why.equals(discardReason)) {
                discardReason = why;
                host.log("discarding PCM " + why);
            }
            discarded++;
            if (unsupported && !closed) pace(len);
            return;
        }
        discardReason = null;
        if (!started) {
            if (!focused) {
                focused = focus.request();
                host.log("audio focus " + (focused ? "granted" : "denied"));
            }
            track.play();
            started = true;
        }
        int wrote = 0;
        while (len > 0 && !closed) {
            int w = track.write(buf, off, len);
            if (w < 0) {
                host.log("track write failed: " + w + ", dropping client");
                releaseTrack();
                throw new IllegalStateException("AudioTrack.write returned " + w);
            }
            if (w == 0) break; // stopped
            off += w;
            len -= w;
            wrote += w;
        }
        bytes += wrote;
        if (len == 0) frames++;
        if (wrote > 0) setReported(true);
        long now = host.uptimeMs();
        if (statsAt == 0) statsAt = now;
        if (now - statsAt >= STATS_INTERVAL_MS) {
            statsAt = now;
            logStats("stats");
        }
    }

    @Override
    public void flush() {
        host.log("F flush");
        if (track == null) return;
        track.pause();
        track.flush();
        if (started) track.play();
    }

    @Override
    public void cork(boolean c) {
        host.log("C " + (c ? "1 pause" : "0 resume"));
        corked = c;
        if (!c) return; // play on the next PCM
        if (track != null && started) track.pause();
        started = false;
        if (focused) {
            focus.abandon();
            focused = false;
        }
        setReported(false);
    }

    /** Sleeps for as long as len bytes of the current format would play. */
    private void pace(int len) {
        paceBytes += len;
        long ms = paceBytes * 1000 / bytesPerSecond;
        if (ms <= 0) return;
        paceBytes -= ms * bytesPerSecond / 1000;
        host.sleep(ms);
    }

    private void releaseTrack() {
        if (track == null) return;
        Track t = track;
        track = null;
        started = false;
        t.release();
    }

    private void setReported(boolean p) {
        if (reported == p) return;
        reported = p;
        host.onPlaying(p);
    }

    private void logStats(String what) {
        long head = track != null ? track.head() : -1;
        double secs = rate > 0 && channels > 0 ? bytes / (double) (rate * channels * 2) : 0;
        host.debug(String.format("%s: %d P frames, %d bytes (%.1f s), head %d, discarded %d",
                what, frames, bytes, secs, head, discarded));
    }
}
