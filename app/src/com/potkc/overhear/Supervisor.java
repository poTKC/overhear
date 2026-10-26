package com.potkc.overhear;

import java.util.regex.Pattern;

/**
 * When to start, restart and give up on Soloist: the decisions behind
 * PlayerService, kept pure Java (host tests). Not thread-safe: the service
 * calls every method on its main thread and does the work in Effects.
 *
 * - Every start gets a generation; results and exits carry it, and those
 *   of an older run are ignored. An exit that arrives before the start is
 *   reported (the process died at once) is latched and applied then.
 * - An unrequested exit restarts after 1, 2, 4 ... 60 s; the delay starts
 *   over once a run lasted STABLE_MS. After MAX_SHORT_RUNS runs in a row
 *   that each lasted under SHORT_RUN_MS it gives up (bad key, broken
 *   install): retrying forever only burns battery. The same goes for
 *   MAX_SHORT_RUNS retryable start failures in a row.
 * - An expired build (exit 10) re-downloads at once, then with the same
 *   backoff; at most MAX_REFRESHES re-downloads per REFRESH_WINDOW_MS,
 *   then it gives up (the CDN would otherwise be hit forever).
 * - Nothing starts while Wi-Fi is down. When Wi-Fi comes back a running
 *   Soloist is restarted (its zeroconf binds to the interfaces at start and
 *   resolv.conf may have changed); a pending backoff is cut short.
 * - Giving up (FAILED) is terminal until the user acts (begin() or
 *   restart()), with one exception: the non-fatal give-ups (MAX_SHORT_RUNS
 *   short runs or start failures in a row) try again on their own after
 *   RETRY_COOLDOWN_MS or when Wi-Fi comes up again (a new network may be
 *   all it took). Fatal ones (a start that fails for good, e.g. no API key;
 *   short runs after Soloist reported a login/key error; the refresh cap)
 *   wait for the user.
 * - Effects.setActive reports whether Soloist is wanted right now (starting,
 *   running, restarting or backing off, with Wi-Fi up): the service holds
 *   its wake lock exactly then.
 */
final class Supervisor {
    static final long BASE_MS = 1000;
    static final long MAX_MS = 60000;
    /** A run this long resets the backoff. */
    static final long STABLE_MS = 60000;
    static final long SHORT_RUN_MS = 10000;
    static final int MAX_SHORT_RUNS = 10;
    static final int MAX_REFRESHES = 3;
    static final long REFRESH_WINDOW_MS = 24L * 3600 * 1000;
    /** How long a non-fatal give-up waits before trying again by itself. */
    static final long RETRY_COOLDOWN_MS = 30L * 60 * 1000;
    static final String KEEPS_FAILING = "Soloist \u306e\u8d77\u52d5\u306b\u5931\u6557\u3057\u7d9a\u3051\u3066\u3044\u307e\u3059";
    static final String KEEPS_EXITING = "Soloist \u304c\u7d42\u4e86\u3057\u7d9a\u3051\u3066\u3044\u307e\u3059";

    /** Soloist error events worth showing: login and key problems, not its startup noise. */
    private static final Pattern AUTH_ERROR = Pattern.compile(
            "(?i)login|credential|unauthori[sz]ed|api[ _-]?key|auth(entication)? failed|forbidden");
    private static final Pattern AUTH_OK = Pattern.compile("(?i)\\b(succeeded|successful(ly)?|success|logged in)\\b");

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
        /** Gave up; begin() or restart() tries again. */
        FAILED,
        SHUTDOWN
    }

    /** The service side; every call returns at once (the work is asynchronous). */
    interface Effects {
        /**
         * Install if needed (re-download with refresh), then start; report
         * onStarted or onStartFailed, and the run's exit, with gen.
         */
        void start(int gen, boolean refresh);

        /**
         * Stop the running Soloist. Its exit comes as onExit(requested = true)
         * with its gen, also when it was already gone (then right away).
         */
        void stop();

        /** (Re)arm the one timer; onTimer when it fires. */
        void schedule(long delayMs);

        void cancelTimer();

        /**
         * Gave up; tell the user why. With autoRetry a schedule() for the
         * cooldown follows; else only the user can retry.
         */
        void failed(String reason, boolean autoRetry);

        /** Soloist is wanted now (hold the wake lock) or not. */
        void setActive(boolean active);
    }

    private final Effects fx;
    private State state = State.IDLE;
    private boolean wifiUp;
    private boolean active;
    private int failures;
    private int shortRuns;
    /** Retryable start failures in a row; reset by a successful start. */
    private int startFailures;
    /** When the last re-downloads were started, newest last; 0 = unused. */
    private final long[] refreshTimes = new long[MAX_REFRESHES];
    private boolean pendingRefresh;
    private boolean restartAfterStart;
    private long startedAt;
    /** The current run; results and exits of other generations are stale. */
    private int gen;
    private boolean exitSeen;
    // An exit latched while STARTING.
    private boolean latched;
    private boolean latchedExpired;
    private boolean latchedRequested;
    /** FAILED will try again by itself (timer or Wi-Fi). */
    private boolean retryable;
    /** The current run reported a login or key error. */
    private boolean authFailed;
    /** FAILED because of the settings (key): the user should open setup. */
    private boolean settingsFault;

    Supervisor(Effects fx) {
        this.fx = fx;
    }

    /** 1 s doubling per failure, capped at MAX_MS. */
    static long backoffMs(int failures) {
        if (failures >= 6) return MAX_MS;
        return Math.min(MAX_MS, BASE_MS << failures);
    }

    /** True for Soloist error messages about the login or API key. */
    static boolean isAuthError(String message) {
        if (message == null) return false;
        if (message.contains("command requires authentication")) return false; // before login completes
        return AUTH_ERROR.matcher(message).find() && !AUTH_OK.matcher(message).find();
    }

    State state() {
        return state;
    }

    /** FAILED, and it will try again by itself after the cooldown or on Wi-Fi. */
    boolean willRetry() {
        return state == State.FAILED && retryable;
    }

    /**
     * FAILED because of the settings: a start that cannot work (no or bad
     * API key: onStartFailed with retry = false) or Soloist exiting over
     * and over after a login error. Retrying will not help; setup might.
     */
    boolean needsSetup() {
        return state == State.FAILED && settingsFault;
    }

    /** Soloist reported a login or API key error during the current run. */
    void onAuthError() {
        authFailed = true;
    }

    boolean isWifiUp() {
        return wifiUp;
    }

    /** The service wants Soloist running. No-op unless idle or failed. */
    void begin(boolean wifiUp) {
        if (state == State.SHUTDOWN) return;
        if (state != State.IDLE && state != State.FAILED) {
            // Already going: a fresher Wi-Fi reading is a Wi-Fi transition.
            onWifi(wifiUp);
            return;
        }
        if (retryable) {
            // The user did not wait for the cooldown.
            retryable = false;
            fx.cancelTimer();
        }
        this.wifiUp = wifiUp;
        failures = 0;
        shortRuns = 0;
        startFailures = 0;
        tryStart(false);
    }

    void onStarted(int g, long now) {
        if (g != gen) return;
        if (state == State.SHUTDOWN) {
            fx.stop();
            return;
        }
        if (state != State.STARTING) return;
        startedAt = now;
        pendingRefresh = false;
        startFailures = 0;
        if (latched) {
            latched = false;
            if (restartAfterStart || latchedRequested) {
                // The start (and any re-download it did) succeeded, so the
                // next one needs no refresh: pendingRefresh stays dropped.
                restartAfterStart = false;
                tryStart(false);
                return;
            }
            setState(State.RUNNING);
            crashed(latchedExpired, now);
            return;
        }
        if (restartAfterStart) {
            restartAfterStart = false;
            setState(State.RESTARTING);
            fx.stop();
            return;
        }
        setState(State.RUNNING);
    }

    /** @param retry false when retrying cannot help (e.g. no API key) */
    void onStartFailed(int g, String reason, boolean retry) {
        if (g != gen || state != State.STARTING) return;
        latched = false;
        if (!retry) {
            restartAfterStart = false;
            giveUp(reason, false, true);
            return;
        }
        if (++startFailures >= MAX_SHORT_RUNS) {
            restartAfterStart = false;
            giveUp(KEEPS_FAILING, true, false);
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

    void onExit(int g, int code, boolean expired, boolean requested, long now) {
        if (g != gen || exitSeen) return;
        exitSeen = true;
        switch (state) {
            case STARTING:
                latched = true;
                latchedExpired = expired;
                latchedRequested = requested;
                return;
            case RESTARTING:
                tryStart(false);
                return;
            case RUNNING:
                if (!requested) crashed(expired, now);
                return;
            default:
                return;
        }
    }

    /** An unrequested exit of the RUNNING run. */
    private void crashed(boolean expired, long now) {
        long ran = now - startedAt;
        if (ran >= STABLE_MS) failures = 0;
        if (ran < SHORT_RUN_MS) shortRuns++;
        else shortRuns = 0;
        if (shortRuns >= MAX_SHORT_RUNS) {
            // Exiting after a login error: retrying will not fix the key.
            giveUp(KEEPS_EXITING, !authFailed, authFailed);
            return;
        }
        if (!expired) {
            backoff(false);
            return;
        }
        int recent = 0;
        for (long t : refreshTimes) {
            if (t != 0 && now - t < REFRESH_WINDOW_MS) recent++;
        }
        if (recent >= MAX_REFRESHES) {
            giveUp("Soloist \u306e\u671f\u9650\u5207\u308c\u304c\u518d\u30c0\u30a6\u30f3\u30ed\u30fc\u30c9\u3067\u76f4\u308a\u307e\u305b\u3093", false, false);
            return;
        }
        System.arraycopy(refreshTimes, 1, refreshTimes, 0, MAX_REFRESHES - 1);
        refreshTimes[MAX_REFRESHES - 1] = now == 0 ? 1 : now;
        if (recent == 0) tryStart(true);
        else backoff(true);
    }

    void onTimer() {
        if (state == State.BACKOFF) tryStart(pendingRefresh);
        else if (willRetry()) retryAfterGivingUp();
    }

    private void retryAfterGivingUp() {
        retryable = false;
        begin(wifiUp);
    }

    void onWifi(boolean up) {
        if (state == State.SHUTDOWN || up == wifiUp) return;
        wifiUp = up;
        if (!up) {
            if (state == State.BACKOFF) {
                fx.cancelTimer();
                setState(State.WAIT_WIFI);
            }
            // A running or starting Soloist is left alone until Wi-Fi returns.
            updateActive();
            return;
        }
        switch (state) {
            case FAILED:
                if (retryable) begin(wifiUp); // cancels the cooldown
                break;
            case WAIT_WIFI:
                tryStart(pendingRefresh);
                break;
            case BACKOFF:
                fx.cancelTimer();
                tryStart(pendingRefresh);
                break;
            case RUNNING:
                setState(State.RESTARTING);
                fx.stop();
                break;
            case STARTING:
                restartAfterStart = true;
                break;
            default:
                break;
        }
        updateActive();
    }

    /**
     * Start Soloist afresh now (debug, settings change): like Wi-Fi coming
     * back, or a retry after giving up. Forgets earlier short runs.
     */
    void restart() {
        shortRuns = 0;
        startFailures = 0;
        switch (state) {
            case RUNNING:
                setState(State.RESTARTING);
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
                break; // RESTARTING: its exit starts again; WAIT_WIFI: Wi-Fi does
        }
    }

    void shutdown() {
        if (state == State.SHUTDOWN) return;
        setState(State.SHUTDOWN);
        fx.cancelTimer();
        fx.stop();
    }

    private void tryStart(boolean refresh) {
        pendingRefresh = refresh;
        if (!wifiUp) {
            setState(State.WAIT_WIFI);
            return;
        }
        gen++;
        exitSeen = false;
        latched = false;
        authFailed = false;
        setState(State.STARTING);
        fx.start(gen, refresh);
    }

    private void backoff(boolean refresh) {
        pendingRefresh = refresh;
        if (!wifiUp) {
            setState(State.WAIT_WIFI);
            return;
        }
        setState(State.BACKOFF);
        fx.schedule(backoffMs(failures++));
    }

    private void giveUp(String reason, boolean autoRetry, boolean settings) {
        retryable = autoRetry;
        settingsFault = settings;
        setState(State.FAILED);
        fx.failed(reason, autoRetry);
        if (autoRetry) fx.schedule(RETRY_COOLDOWN_MS);
    }

    private void setState(State s) {
        state = s;
        updateActive();
    }

    private void updateActive() {
        boolean a = wifiUp && (state == State.STARTING || state == State.RUNNING
                || state == State.RESTARTING || state == State.BACKOFF);
        if (a == active) return;
        active = a;
        fx.setActive(a);
    }
}
