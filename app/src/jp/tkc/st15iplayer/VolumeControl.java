package jp.tkc.st15iplayer;

/**
 * The player screen's volume: throttles set_volume while the user drags or
 * holds a volume key, and keeps showing the user's value for a moment so
 * echoes of older commands do not make the slider jump back. Times are any
 * monotonic milliseconds. Pure Java (host tests); main thread only.
 */
final class VolumeControl {
    /** At most one set_volume per this while the value keeps changing. */
    static final long INTERVAL_MS = 300;
    /** The user's value wins over the server's for this long after a change. */
    static final long HOLD_MS = 1500;

    private int server = -1;
    private int local = -1;
    private int pending = -1;
    private long lastChange = Long.MIN_VALUE / 2;
    private long lastSent = Long.MIN_VALUE / 2;
    private boolean dragging;

    /** volume_changed / state volume (-1 unknown). */
    void onServer(int volume) {
        server = volume;
    }

    void setDragging(boolean d) {
        dragging = d;
    }

    /** The value to show. */
    int display(long now) {
        if (local >= 0 && (dragging || now - lastChange < HOLD_MS)) return local;
        return server;
    }

    /**
     * The user picked v (clamped to 0..100). Returns how long to wait before
     * flush(): 0 means flush now.
     */
    long change(int v, long now) {
        local = Math.max(0, Math.min(100, v));
        pending = local;
        lastChange = now;
        long wait = lastSent + INTERVAL_MS - now;
        return wait > 0 ? wait : 0;
    }

    /** A volume key: delta from what is shown. Like change(), or -1 if the volume is unknown. */
    long step(int delta, long now) {
        int base = display(now);
        if (base < 0) return -1;
        return change(base + delta, now);
    }

    /** The value to send now, or -1 if nothing is pending. */
    int flush(long now) {
        int v = pending;
        if (v < 0) return -1;
        pending = -1;
        lastSent = now;
        return v;
    }
}
