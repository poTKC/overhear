package com.potkc.overhear;

/**
 * When the service stops itself for not playing: once it has been idle
 * (not playing, no player or library screen visible, no play here or jump
 * under way: the caller decides) for timeoutMs without a break, counted from
 * when it became idle or from the last reset (a control, the end of a play
 * here or jump), whichever is later. timeoutMs 0 means never.
 * elapsedRealtime milliseconds, passed in. Pure Java (host tests).
 */
final class IdleStop {
    /** When the current idle stretch began (or was reset); -1 while not idle. */
    private long idleSince = -1;
    private long deadline = -1;

    /**
     * Records whether the service is idle at nowMs; returns when it should
     * stop (shouldStop from then on), or -1 for not at all (not idle, or
     * timeoutMs 0). The timeout is read per call, so a new setting applies
     * to the stretch under way.
     */
    long update(long nowMs, boolean idle, long timeoutMs) {
        if (!idle) idleSince = -1;
        else if (idleSince < 0) idleSince = nowMs;
        deadline = idleSince < 0 || timeoutMs <= 0 ? -1 : idleSince + timeoutMs;
        return deadline;
    }

    /** User input: the countdown starts over at the next update. */
    void reset() {
        idleSince = -1;
        deadline = -1;
    }

    /** The deadline of the last update has passed. */
    boolean shouldStop(long nowMs) {
        return deadline >= 0 && nowMs >= deadline;
    }

    /** A setting in minutes as a timeout (0 or less: never). */
    static long timeoutMs(int minutes) {
        return minutes <= 0 ? 0 : minutes * 60000L;
    }
}
