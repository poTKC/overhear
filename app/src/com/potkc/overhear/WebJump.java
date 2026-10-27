package com.potkc.overhear;

import java.util.concurrent.atomic.AtomicInteger;

import com.potkc.overhear.soloist.PlayerState;

/**
 * A queue jump through the Web API: one play of the context from the tapped
 * track (QueueRoute decides when). The play goes out as a Spotify call, which
 * waits behind the calls before it on the single Spotify thread, so it carries
 * a Ticket: the call claims it (Ticket.begin) right before the PUT and skips
 * the PUT when the jump has already ended.
 *
 * Ends as:
 * - done: the call succeeded (Soloist then reports the new track);
 * - the call failed, but the target is the current track (a failed call may
 *   still have been applied): done; otherwise it checks once more after
 *   recheckMs, then falls back to QueueJump for the entry (Effects
 *   .startQueueJumpFallback), or fails if that cannot start;
 * - failed, with no fallback, when the call has not answered timeoutMs after
 *   it began (a skip sequence that late would surprise), or has not even
 *   begun timeoutMs after start (queued behind slow calls: the Ticket is
 *   cancelled so the PUT never goes out);
 * - failed at once on onUserInput (a control pressed): the user's command
 *   wins. A PUT that has begun cannot be taken back; one still queued is
 *   cancelled.
 *
 * Every callback after the end is ignored, so late answers do nothing.
 *
 * Pure Java for the host tests. Not thread-safe, except Ticket: one thread
 * (the service's main thread) calls everything else, and effects run
 * synchronously.
 */
final class WebJump {
    interface Clock {
        /** Monotonic; also read on the Spotify thread (Ticket.begin). */
        long nowMs();
    }

    interface Effects {
        /**
         * Queues the play. The call must run ticket.begin() right before the
         * PUT and skip the PUT if it returns false; its answer comes back
         * through onPlayDone / onPlayFailed (ignored once the jump ended).
         */
        void sendPlay(Ticket ticket);

        /** Starts QueueJump for the entry; false if it cannot (the entry moved). */
        boolean startQueueJumpFallback();

        void log(String message);

        /** Called once, unless the fallback started (QueueJump then reports itself). */
        void finished(boolean ok, String why);
    }

    /** Shared with the Spotify call, which may run on another thread. */
    static final class Ticket {
        private static final int QUEUED = 0;
        private static final int STARTED = 1;
        private static final int CANCELLED = 2;

        private final Clock clock;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private volatile long startedMs;

        Ticket(Clock clock) {
            this.clock = clock;
        }

        /** On the calling thread, right before the PUT: false (skip it) if the jump ended first. */
        boolean begin() {
            startedMs = clock.nowMs();
            return state.compareAndSet(QUEUED, STARTED);
        }

        boolean isStarted() {
            return state.get() == STARTED;
        }

        boolean isCancelled() {
            return state.get() == CANCELLED;
        }

        /** Cancels if not begun; false if the call has already begun. */
        boolean cancel() {
            return state.compareAndSet(QUEUED, CANCELLED);
        }

        long startedMs() {
            return startedMs;
        }
    }

    static final long DEFAULT_TIMEOUT_MS = 8000;
    static final long DEFAULT_RECHECK_MS = 1500;

    private enum Phase {
        /** The play is queued or out. */
        CALL,
        /** The call failed and the target was not playing yet: waiting to look again. */
        RECHECK,
        DONE
    }

    private final int index;
    private final String uid;
    private final String uri;
    private final Clock clock;
    private final long timeoutMs;
    private final long recheckMs;
    private final Effects fx;
    private final Ticket ticket;

    private Phase phase;
    private long startMs;
    private long recheckAt;
    private String trackUri;

    /**
     * @param index the entry's place in the upcoming queue
     * @param uid its queue uid, or null
     * @param uri its track URI
     */
    WebJump(int index, String uid, String uri, Clock clock, long timeoutMs, long recheckMs, Effects fx) {
        if (uri == null) throw new IllegalArgumentException("no URI");
        this.index = index;
        this.uid = uid;
        this.uri = uri;
        this.clock = clock;
        this.timeoutMs = timeoutMs;
        this.recheckMs = recheckMs;
        this.fx = fx;
        this.ticket = new Ticket(clock);
    }

    /** Sends the play. s is the state now (for the track). */
    void start(PlayerState s) {
        if (phase != null) throw new IllegalStateException("started twice");
        phase = Phase.CALL;
        startMs = clock.nowMs();
        trackUri = s.getTrackUri();
        fx.sendPlay(ticket);
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

    long getStartMs() {
        return startMs;
    }

    /** Until onTimer should run (0 when due); -1 when not running. */
    long msUntilDeadline() {
        if (!isRunning()) return -1;
        long at = deadline();
        if (phase == Phase.RECHECK) at = Math.min(at, recheckAt);
        return Math.max(0, at - clock.nowMs());
    }

    /** Every state snapshot (a new, empty one on a disconnect) while running. */
    void onState(PlayerState s) {
        if (isRunning()) trackUri = s.getTrackUri();
    }

    void onPlayDone() {
        if (phase != Phase.CALL) return;
        end(true, "done");
    }

    void onPlayFailed(String why) {
        if (phase != Phase.CALL) return;
        if (uri.equals(trackUri)) {
            end(true, "failed (" + why + "), but the target plays");
            return;
        }
        fx.log("failed (" + why + "), checking again shortly");
        phase = Phase.RECHECK;
        recheckAt = clock.nowMs() + recheckMs;
    }

    void onTimer() {
        if (!isRunning()) return;
        long now = clock.nowMs();
        if (phase == Phase.CALL && !ticket.isStarted() && now >= deadline()) {
            if (ticket.cancel()) {
                end(false, "not started in " + timeoutMs + " ms (queued behind other calls), cancelled");
                return;
            }
            // It has just begun: its own deadline counts from now on.
        }
        if (now >= deadline()) {
            end(false, "no answer in " + timeoutMs + " ms, giving up");
            return;
        }
        if (phase == Phase.RECHECK && now >= recheckAt) {
            if (uri.equals(trackUri)) {
                end(true, "the target plays after all");
                return;
            }
            fx.log("skipping there instead");
            phase = Phase.DONE;
            if (!fx.startQueueJumpFallback()) fx.finished(false, "the entry left the queue, no fallback");
        }
    }

    /** A control was pressed: the user's command wins. */
    void onUserInput() {
        if (!isRunning()) return;
        boolean queued = ticket.cancel();
        end(false, queued ? "ended by a control (the play was still queued, cancelled)" : "ended by a control");
    }

    // ---

    /** timeoutMs after the call began; while it is still queued, timeoutMs after start (then it is cancelled). */
    private long deadline() {
        return (ticket.isStarted() ? ticket.startedMs() : startMs) + timeoutMs;
    }

    private void end(boolean ok, String why) {
        phase = Phase.DONE;
        ticket.cancel();
        fx.finished(ok, why);
    }
}
