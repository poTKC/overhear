package jp.tkc.st15iplayer;

import jp.tkc.st15iplayer.soloist.PlayerState;

/**
 * The seek bar's position right after the user seeks: Soloist's last sync
 * still has the old position, so the target is shown (advancing while
 * playing) until a newer sync arrives or HOLD_MS pass. Wall-clock
 * milliseconds, like PlayerState.positionAt. Pure Java (host tests).
 */
final class SeekHold {
    static final long HOLD_MS = 3000;

    private long target = -1;
    private long at;
    private long stateTs;

    /** The user sought to targetMs at nowMs, when the state's last sync was stateTimestampMs. */
    void seeked(long targetMs, long nowMs, long stateTimestampMs) {
        target = targetMs;
        at = nowMs;
        stateTs = stateTimestampMs;
    }

    /** The position to show at nowMs. */
    long position(PlayerState s, long nowMs) {
        if (target >= 0 && nowMs - at < HOLD_MS && s.getPositionTimestampMs() == stateTs) {
            long p = target + (s.getStatus() == PlayerState.Status.PLAYING ? nowMs - at : 0);
            long dur = s.getDurationMs();
            return dur > 0 ? Math.min(p, dur) : p;
        }
        target = -1;
        return s.positionAt(nowMs);
    }
}
