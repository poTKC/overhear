package jp.tkc.st15iplayer;

import java.util.List;

import jp.tkc.st15iplayer.soloist.PlayerState;

/**
 * Jumps to an entry of the upcoming queue. Soloist has no command for that,
 * so this sends skip_next once per entry, one at a time: each skip waits for
 * its track_changed (PlayerState.getTrackChanges) and, while more are needed,
 * for the queue_changed that follows, where the target is looked up again by
 * uid (by URI at the expected place when it has none). Counting changes
 * rather than looking for the URI makes a duplicate earlier in the queue
 * harmless, and an entry added or removed in front of the target only
 * changes the number of skips left.
 *
 * Audio is muted (AudioTrack volume, not Soloist's) from the start until the
 * target plays, so the skipped tracks are never heard. The target always
 * ends playing: play is sent on arrival when the jump started paused (Soloist
 * 1.3.10 already starts playing on skip_next while paused; the play makes it
 * explicit) or the target arrives paused. The unmute waits until the target
 * plays at speed > 0 after a stall (buffering, or the speed-0 sync of the new
 * track), not its track_changed: the track may still hold the previous
 * track's audio until then.
 *
 * Gives up (unmutes, sends nothing else) when a step takes longer than the
 * timeout, the target leaves the queue, the track changes when no skip is
 * pending (someone else skipped), playback is paused elsewhere, or the device
 * is no longer the active one. onUserInput and onDisconnected end it at once,
 * unmuted: the user's command (or nothing) wins.
 *
 * Pure Java for the host tests. Not thread-safe: one thread (the service's
 * main thread) calls everything, and effects run synchronously.
 */
final class QueueJump {
    interface Effects {
        void skip();

        void mute();

        void unmute();

        void play();

        /** Called once, after unmute: ok when the target is the current track. */
        void finished(boolean ok, String why);
    }

    interface Clock {
        long nowMs();
    }

    static final long DEFAULT_TIMEOUT_MS = 5000;

    private enum Phase {
        /** A skip is out; waiting for its track_changed. */
        STEP,
        /** The skip landed, more are needed; waiting for the new queue. */
        QUEUE,
        /** On the target; waiting for it to play before unmuting. */
        SETTLE,
        DONE
    }

    private final int index;
    private final String uid;
    private final String uri;
    private final Clock clock;
    private final long timeoutMs;
    private final Effects fx;

    private Phase phase;
    private boolean wasPlaying;
    /** Skips still needed, the one in flight included. */
    private int remaining;
    private int skipsSent;
    private long deadline;
    /** What the last state showed. */
    private int trackChanges;
    private List<PlayerState.QueueItem> queue;
    private PlayerState.Status status;
    /** SETTLE: a state after arriving showed the target not yet playing. */
    private boolean sawStall;
    /** The result reported when settling ends. */
    private String resultWhy;

    /**
     * @param index the entry's place in the upcoming queue (0: the next track)
     * @param uid its queue uid, or null
     * @param uri its track URI
     */
    QueueJump(int index, String uid, String uri, Clock clock, long timeoutMs, Effects fx) {
        if (index < 0) throw new IllegalArgumentException("index " + index);
        if (uri == null) throw new IllegalArgumentException("no URI");
        this.index = index;
        this.uid = uid;
        this.uri = uri;
        this.clock = clock;
        this.timeoutMs = timeoutMs;
        this.fx = fx;
    }

    /**
     * Mutes and sends the first skip. False (and no effects) when the entry is
     * not at index in s's queue any more, or this device is not active.
     */
    boolean start(PlayerState s) {
        if (phase != null) throw new IllegalStateException("started twice");
        List<PlayerState.QueueItem> q = s.getQueue();
        if (!s.isActive() || index >= q.size() || !matches(q.get(index))) return false;
        PlayerState.Status st = s.getStatus();
        wasPlaying = st == PlayerState.Status.PLAYING || st == PlayerState.Status.BUFFERING;
        trackChanges = s.getTrackChanges();
        queue = q;
        status = st;
        remaining = index + 1;
        fx.mute();
        skip();
        return true;
    }

    boolean isRunning() {
        return phase != null && phase != Phase.DONE;
    }

    int getIndex() {
        return index;
    }

    String getUid() {
        return uid;
    }

    String getUri() {
        return uri;
    }

    /** Skips confirmed so far. */
    int getSteps() {
        return skipsSent - (phase == Phase.STEP ? 1 : 0);
    }

    boolean wasPlaying() {
        return wasPlaying;
    }

    /** Until onTimer should run (0 when due); -1 when not running. */
    long msUntilDeadline() {
        if (!isRunning()) return -1;
        return Math.max(0, deadline - clock.nowMs());
    }

    /** Every state snapshot while running. */
    void onState(PlayerState s) {
        if (!isRunning()) return;
        if (!s.isActive()) {
            end(false, "no longer the active device");
            return;
        }
        int changes = s.getTrackChanges() - trackChanges;
        trackChanges = s.getTrackChanges();
        boolean queueChanged = s.getQueue() != queue;
        queue = s.getQueue();
        PlayerState.Status before = status;
        status = s.getStatus();
        boolean pausedNow = before != PlayerState.Status.PAUSED && status == PlayerState.Status.PAUSED;

        if (changes != 0) {
            if (phase != Phase.STEP || changes != 1) {
                // Not our skip (or a state from a new connection).
                end(phase == Phase.SETTLE, phase == Phase.SETTLE ? "the track changed while settling"
                        : "the track changed without a skip");
                return;
            }
            remaining--;
            if (remaining == 0) {
                arrive(s);
                return;
            }
            phase = Phase.QUEUE;
            if (!queueChanged) return;
        }

        switch (phase) {
            case STEP:
            case QUEUE:
                if (pausedNow) {
                    end(false, "paused elsewhere");
                    return;
                }
                if (phase == Phase.QUEUE && queueChanged) checkQueue(s.getQueue());
                break;
            case SETTLE:
                if (pausedNow) {
                    end(true, "paused elsewhere on the target");
                } else if (status == PlayerState.Status.PLAYING && s.getSpeed() > 0) {
                    if (sawStall) end(true, resultWhy);
                } else {
                    sawStall = true;
                }
                break;
            default:
                break;
        }
    }

    /** No state for too long: give up a step, or stop waiting to settle. */
    void onTimer() {
        if (!isRunning() || clock.nowMs() < deadline) return;
        if (phase == Phase.SETTLE) {
            end(true, resultWhy + " (no playing seen, unmuted after the timeout)");
        } else {
            end(false, "timed out after " + getSteps() + " of " + (getSteps() + remaining) + " skips");
        }
    }

    /** The user pressed a control: their command wins, unmute now. */
    void onUserInput() {
        if (isRunning()) end(phase == Phase.SETTLE, "user input");
    }

    void onDisconnected() {
        if (isRunning()) end(false, "disconnected");
    }

    // ---

    private boolean matches(PlayerState.QueueItem item) {
        if (uid != null) return uid.equals(item.uid);
        return uri.equals(item.uri);
    }

    private void skip() {
        phase = Phase.STEP;
        deadline = clock.nowMs() + timeoutMs;
        skipsSent++;
        fx.skip();
    }

    /** After a confirmed skip with more to go: find the target again, then skip. */
    private void checkQueue(List<PlayerState.QueueItem> q) {
        int at = -1;
        if (uid != null) {
            for (int i = 0; i < q.size(); i++) {
                if (uid.equals(q.get(i).uid)) {
                    at = i;
                    break;
                }
            }
            if (at >= 0 && !uri.equals(q.get(at).uri)) at = -1;
        } else if (remaining <= q.size() && uri.equals(q.get(remaining - 1).uri)) {
            at = remaining - 1;
        }
        if (at < 0) {
            end(false, "the target left the queue");
            return;
        }
        remaining = at + 1;
        skip();
    }

    /** The last skip landed. */
    private void arrive(PlayerState s) {
        if (!uri.equals(s.getTrackUri())) {
            end(false, "landed on " + s.getTrackUri() + " instead of " + uri);
            return;
        }
        resultWhy = "reached " + uri + " in " + skipsSent + " skips";
        // The target always plays, whatever the state before the jump.
        phase = Phase.SETTLE;
        deadline = clock.nowMs() + timeoutMs;
        sawStall = false;
        if (!wasPlaying || status == PlayerState.Status.PAUSED) fx.play();
    }

    private void end(boolean ok, String why) {
        phase = Phase.DONE;
        fx.unmute();
        fx.finished(ok, why);
    }
}
