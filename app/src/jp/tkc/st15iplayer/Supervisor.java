package jp.tkc.st15iplayer;

/**
 * When to start, restart and give up on Soloist: the decisions behind
 * PlayerService, kept pure Java (host tests). Not thread-safe: the service
 * calls every method on its main thread and does the work in Effects.
 *
 * - An unrequested exit restarts after 1, 2, 4 ... 60 s; the delay starts
 *   over once a run lasted STABLE_MS.
 * - An expired build (exit 10) re-downloads at once, then with the same
 *   backoff, and gives up after MAX_REFRESHES expiries without a stable run
 *   (the CDN would otherwise be hit forever).
 * - Nothing starts while Wi-Fi is down. When Wi-Fi comes back a running
 *   Soloist is restarted (its zeroconf binds to the interfaces at start and
 *   resolv.conf may have changed); a pending backoff is cut short.
 * - A start that fails for good (no API key, ...) gives up until begin().
 */
final class Supervisor {
    static final long BASE_MS = 1000;
    static final long MAX_MS = 60000;
    /** A run this long resets the backoff and the refresh count. */
    static final long STABLE_MS = 60000;
    static final int MAX_REFRESHES = 3;

    enum State {
        /** Not started yet. */
        IDLE,
        /** Wants Soloist, waiting for Wi-Fi. */
        WAIT_WIFI,
        /** Effects.start running (install, download, fork). */
        STARTING,
        RUNNING,
        /** Effects.stop sent to restart; starts again on its exit. */
        RESTARTING,
        /** Waiting for the timer before the next start. */
        BACKOFF,
        /** Gave up; begin() tries again. */
        FAILED,
        SHUTDOWN
    }

    /** The service side; every call returns at once (the work is asynchronous). */
    interface Effects {
        /** Install if needed (re-download with refresh), then start; report onStarted or onStartFailed. */
        void start(boolean refresh);

        /** Stop the running Soloist; its exit comes as onExit(requested = true). */
        void stop();

        /** (Re)arm the one timer; onTimer when it fires. */
        void schedule(long delayMs);

        void cancelTimer();

        /** Gave up; tell the user why. */
        void failed(String reason);
    }

    private final Effects fx;
    private State state = State.IDLE;
    private boolean wifiUp;
    private int failures;
    private int refreshes;
    private boolean pendingRefresh;
    private boolean restartAfterStart;
    private long startedAt;

    Supervisor(Effects fx) {
        this.fx = fx;
    }

    /** 1 s doubling per failure, capped at MAX_MS. */
    static long backoffMs(int failures) {
        if (failures >= 6) return MAX_MS;
        return Math.min(MAX_MS, BASE_MS << failures);
    }

    State state() {
        return state;
    }

    boolean isWifiUp() {
        return wifiUp;
    }

    /** The service wants Soloist running. No-op unless idle or failed. */
    void begin(boolean wifiUp) {
        if (state == State.SHUTDOWN) return;
        this.wifiUp = wifiUp;
        if (state != State.IDLE && state != State.FAILED) return;
        failures = 0;
        refreshes = 0;
        pendingRefresh = false;
        tryStart(false);
    }

    void onStarted(long now) {
        if (state == State.SHUTDOWN) {
            fx.stop();
            return;
        }
        if (state != State.STARTING) return;
        startedAt = now;
        pendingRefresh = false;
        if (restartAfterStart) {
            restartAfterStart = false;
            state = State.RESTARTING;
            fx.stop();
            return;
        }
        state = State.RUNNING;
    }

    /** @param retry false when retrying cannot help (e.g. no API key) */
    void onStartFailed(String reason, boolean retry) {
        if (state != State.STARTING) return;
        if (!retry) {
            restartAfterStart = false;
            giveUp(reason);
            return;
        }
        if (restartAfterStart) {
            // Wi-Fi came back mid-start; the failure may be from before that.
            restartAfterStart = false;
            tryStart(pendingRefresh);
            return;
        }
        backoff(pendingRefresh);
    }

    void onExit(int code, boolean expired, boolean requested, long now) {
        if (state == State.RESTARTING) {
            tryStart(false);
            return;
        }
        if (state != State.RUNNING || requested) return;
        if (now - startedAt >= STABLE_MS) {
            failures = 0;
            refreshes = 0;
        }
        if (!expired) {
            backoff(false);
            return;
        }
        refreshes++;
        if (refreshes > MAX_REFRESHES) {
            giveUp("Soloist build expired and " + MAX_REFRESHES + " re-downloads did not help");
        } else if (refreshes == 1) {
            tryStart(true);
        } else {
            backoff(true);
        }
    }

    void onTimer() {
        if (state == State.BACKOFF) tryStart(pendingRefresh);
    }

    void onWifi(boolean up) {
        if (state == State.SHUTDOWN || up == wifiUp) return;
        wifiUp = up;
        if (!up) {
            if (state == State.BACKOFF) {
                fx.cancelTimer();
                state = State.WAIT_WIFI;
            }
            return; // a running or starting Soloist is left alone until Wi-Fi returns
        }
        switch (state) {
            case WAIT_WIFI:
                tryStart(pendingRefresh);
                break;
            case BACKOFF:
                fx.cancelTimer();
                tryStart(pendingRefresh);
                break;
            case RUNNING:
                state = State.RESTARTING;
                fx.stop();
                break;
            case STARTING:
                restartAfterStart = true;
                break;
            default:
                break;
        }
    }

    /** Start Soloist afresh now (debug, settings change): like Wi-Fi coming back, or a retry. */
    void restart() {
        switch (state) {
            case RUNNING:
                state = State.RESTARTING;
                fx.stop();
                break;
            case BACKOFF:
                fx.cancelTimer();
                tryStart(pendingRefresh);
                break;
            case STARTING:
                restartAfterStart = true;
                break;
            case IDLE:
            case FAILED:
                begin(wifiUp);
                break;
            default:
                break;
        }
    }

    void shutdown() {
        if (state == State.SHUTDOWN) return;
        state = State.SHUTDOWN;
        fx.cancelTimer();
        fx.stop();
    }

    private void tryStart(boolean refresh) {
        pendingRefresh = refresh;
        if (!wifiUp) {
            state = State.WAIT_WIFI;
            return;
        }
        state = State.STARTING;
        fx.start(refresh);
    }

    private void backoff(boolean refresh) {
        pendingRefresh = refresh;
        if (!wifiUp) {
            state = State.WAIT_WIFI;
            return;
        }
        state = State.BACKOFF;
        fx.schedule(backoffMs(failures++));
    }

    private void giveUp(String reason) {
        state = State.FAILED;
        fx.failed(reason);
    }
}
