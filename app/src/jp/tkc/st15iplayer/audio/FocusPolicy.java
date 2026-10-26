package jp.tkc.st15iplayer.audio;

/**
 * What an audio focus change means for Soloist. Pure Java (host tests);
 * AudioSink owns the only instance and turns actions into AudioSink.FocusHandler
 * calls (pause/resume Soloist) and track volume (duck).
 *
 *   LOSS            pause if playing (no auto resume); not playing: give
 *                   focus back (it may still be held from a transient pause)
 *   LOSS_TRANSIENT  pause if playing and resume on the next GAIN; keep focus
 *                   through the cork meanwhile (holdOnPause), or the GAIN
 *                   would never arrive
 *   ..._CAN_DUCK    lower the volume until GAIN; keeps playing
 *   GAIN            resume if we paused, restore the volume
 *
 * If a pause cannot be sent (Soloist's API is down), onPauseFailed mutes
 * the track instead until focus returns.
 *
 * The audio socket is never paused here: pausing goes through Soloist, which
 * corks fakepulse, so the reader keeps draining. Thread-safe.
 */
public final class FocusPolicy {
    // AudioManager's values (pure Java cannot reference them).
    public static final int GAIN = 1;
    public static final int LOSS = -1;
    public static final int LOSS_TRANSIENT = -2;
    public static final int LOSS_TRANSIENT_CAN_DUCK = -3;

    public enum Action {
        NONE,
        /** Ask Soloist to pause. */
        PAUSE,
        /** Ask Soloist to play (we paused it for a transient loss). */
        RESUME,
        /** Lower the track volume. */
        DUCK,
        /** Restore the track volume. */
        UNDUCK,
        /** Abandon focus now (we may still hold it from a transient pause). */
        ABANDON
    }

    /** Track volume while ducked. */
    public static final float DUCK_VOLUME = 0.2f;

    private boolean pausedByUs;
    private boolean ducked;
    private boolean muted;

    /** @param playing audio is being written (AudioSink.Listener.onPlaying) */
    public synchronized Action onChange(int change, boolean playing) {
        switch (change) {
            case LOSS:
                pausedByUs = false;
                ducked = false;
                return playing ? Action.PAUSE : Action.ABANDON;
            case LOSS_TRANSIENT:
                if (!playing) return Action.NONE;
                pausedByUs = true;
                return Action.PAUSE;
            case LOSS_TRANSIENT_CAN_DUCK:
                ducked = true;
                return Action.DUCK;
            case GAIN:
                boolean wasDucked = ducked || muted;
                ducked = false;
                muted = false;
                if (pausedByUs) {
                    pausedByUs = false;
                    return Action.RESUME;
                }
                return wasDucked ? Action.UNDUCK : Action.NONE;
            default:
                return Action.NONE;
        }
    }

    /**
     * Our own focus request was granted: playback resumed without us and we
     * hold focus again, so nobody is ducking us. Returns whether we were
     * ducked (restore the volume).
     */
    public synchronized boolean onGranted() {
        boolean wasDucked = ducked || muted;
        pausedByUs = false;
        ducked = false;
        muted = false;
        return wasDucked;
    }

    /** True while paused for a transient loss: do not abandon focus on cork. */
    public synchronized boolean holdOnPause() {
        return pausedByUs;
    }

    /**
     * A PAUSE could not be sent (Soloist's API is not connected): the track
     * is muted instead, until focus comes back or we play again on our own.
     */
    public synchronized void onPauseFailed() {
        muted = true;
    }

    public synchronized boolean isMuted() {
        return muted;
    }

    /** The track volume for the current focus state: 0 muted, DUCK_VOLUME ducked, else 1. */
    public synchronized float volume() {
        return muted ? 0f : ducked ? DUCK_VOLUME : 1f;
    }

    public synchronized boolean isDucked() {
        return ducked;
    }
}
