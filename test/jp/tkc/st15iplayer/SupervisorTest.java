package jp.tkc.st15iplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class SupervisorTest {
    private final List<String> calls = new ArrayList<String>();
    private Supervisor s;

    private final List<Boolean> active = new ArrayList<Boolean>();
    private int gen = -1;

    private final Supervisor.Effects fx = new Supervisor.Effects() {
        @Override public void start(int g, boolean refresh) {
            gen = g;
            calls.add(refresh ? "start refresh" : "start");
        }
        @Override public void setActive(boolean a) { active.add(a); }
        @Override public void stop() { calls.add("stop"); }
        @Override public void schedule(long delayMs) { calls.add("schedule " + delayMs); }
        @Override public void cancelTimer() { calls.add("cancel"); }
        @Override public void failed(String reason, boolean autoRetry) {
            calls.add("failed " + reason + (autoRetry ? " (retry)" : ""));
        }
    };

    @Before public void setUp() {
        s = new Supervisor(fx);
    }

    private void started(long now) { s.onStarted(gen, now); }
    private void exit(int code, boolean expired, boolean requested, long now) {
        s.onExit(gen, code, expired, requested, now);
    }
    private void startFailed(String reason, boolean retry) { s.onStartFailed(gen, reason, retry); }

    private String take() {
        String all = calls.toString();
        calls.clear();
        return all;
    }

    private void running(long now) {
        s.begin(true);
        started(now);
        calls.clear();
    }

    @Test public void backoffDoublesToSixtySeconds() {
        assertEquals(1000, Supervisor.backoffMs(0));
        assertEquals(2000, Supervisor.backoffMs(1));
        assertEquals(4000, Supervisor.backoffMs(2));
        assertEquals(32000, Supervisor.backoffMs(5));
        assertEquals(60000, Supervisor.backoffMs(6));
        assertEquals(60000, Supervisor.backoffMs(100));
    }

    @Test public void beginStartsWhenWifiIsUp() {
        s.begin(true);
        assertEquals("[start]", take());
        assertEquals(Supervisor.State.STARTING, s.state());
        started(0);
        assertEquals(Supervisor.State.RUNNING, s.state());
        s.begin(true); // already going
        assertEquals("[]", take());
    }

    @Test public void beginWaitsForWifi() {
        s.begin(false);
        assertEquals("[]", take());
        assertEquals(Supervisor.State.WAIT_WIFI, s.state());
        s.onWifi(true);
        assertEquals("[start]", take());
    }

    @Test public void crashesBackOffAndResetAfterAStableRun() {
        running(0);
        exit(139, false, false, 5000);
        assertEquals("[schedule 1000]", take());
        assertEquals(Supervisor.State.BACKOFF, s.state());
        s.onTimer();
        assertEquals("[start]", take());
        started(10000);
        exit(139, false, false, 11000);
        assertEquals("[schedule 2000]", take());
        s.onTimer();
        assertEquals("[start]", take());
        started(20000);
        exit(139, false, false, 21000);
        assertEquals("[schedule 4000]", take());
        s.onTimer();
        assertEquals("[start]", take());
        started(30000);
        // Ran 60 s: back to 1 s.
        exit(139, false, false, 90000);
        assertEquals("[schedule 1000]", take());
    }

    @Test public void requestedExitDoesNotRestart() {
        running(0);
        exit(0, false, true, 5000);
        assertEquals("[]", take());
    }

    @Test public void expiredRefreshesAtOnceThenBacksOffThenGivesUp() {
        running(0);
        exit(10, true, false, 1000);
        assertEquals("[start refresh]", take());
        started(2000);
        exit(10, true, false, 3000);
        assertEquals("[schedule 1000]", take());
        s.onTimer();
        assertEquals("[start refresh]", take());
        started(5000);
        exit(10, true, false, 6000);
        assertEquals("[schedule 2000]", take());
        s.onTimer();
        assertEquals("[start refresh]", take());
        started(9000);
        exit(10, true, false, 9500);
        String c = take();
        assertTrue(c, c.startsWith("[failed "));
        assertEquals(Supervisor.State.FAILED, s.state());
        s.onTimer();
        s.onWifi(false);
        s.onWifi(true);
        assertEquals("[]", take());
    }

    @Test public void expiredAfterAStableRunRefreshesAgain() {
        running(0);
        exit(10, true, false, 1000);
        started(2000);
        exit(10, true, false, 3000);
        s.onTimer();
        started(5000);
        take();
        exit(10, true, false, 5000 + 90L * 24 * 3600 * 1000);
        assertEquals("[start refresh]", take());
    }

    @Test public void failedStartRetriesWithBackoffKeepingRefresh() {
        running(0);
        exit(10, true, false, 1000);
        take();
        startFailed("download failed", true);
        assertEquals("[schedule 1000]", take());
        s.onTimer();
        assertEquals("[start refresh]", take());
        startFailed("download failed", true);
        assertEquals("[schedule 2000]", take());
    }

    @Test public void fatalStartFailureGivesUp() {
        s.begin(true);
        take();
        startFailed("no API key", false);
        assertEquals("[failed no API key]", take());
        assertEquals(Supervisor.State.FAILED, s.state());
    }

    @Test public void crashWhileWifiDownWaitsForWifi() {
        running(0);
        s.onWifi(false);
        assertEquals("[]", take()); // a running Soloist is left alone
        exit(139, false, false, 5000);
        assertEquals("[]", take());
        assertEquals(Supervisor.State.WAIT_WIFI, s.state());
        s.onWifi(true);
        assertEquals("[start]", take());
    }

    @Test public void wifiDownDuringBackoffCancelsTheTimer() {
        running(0);
        exit(139, false, false, 5000);
        take();
        s.onWifi(false);
        assertEquals("[cancel]", take());
        assertEquals(Supervisor.State.WAIT_WIFI, s.state());
        s.onTimer(); // a late timer is ignored
        assertEquals("[]", take());
        s.onWifi(true);
        assertEquals("[start]", take());
    }

    @Test public void wifiBackRestartsARunningSoloist() {
        running(0);
        s.onWifi(false);
        s.onWifi(true);
        assertEquals("[stop]", take());
        assertEquals(Supervisor.State.RESTARTING, s.state());
        s.onWifi(true); // no change
        assertEquals("[]", take());
        exit(143, false, true, 5000);
        assertEquals("[start]", take());
    }

    @Test public void restartAlsoHandlesAnUnrequestedExitWhileStopping() {
        running(0);
        s.onWifi(false);
        s.onWifi(true);
        take();
        exit(139, false, false, 5000); // crashed before the SIGTERM landed
        assertEquals("[start]", take());
    }

    @Test public void wifiBackDuringStartRestartsOnceStarted() {
        s.begin(true);
        s.onWifi(false);
        s.onWifi(true);
        assertEquals("[start]", take());
        started(1000);
        assertEquals("[stop]", take());
        exit(143, false, true, 2000);
        assertEquals("[start]", take());
        started(3000);
        assertEquals("[]", take());
        assertEquals(Supervisor.State.RUNNING, s.state());
    }

    @Test public void wifiBackDuringStartThatFailsStartsAtOnce() {
        s.begin(true);
        s.onWifi(false);
        s.onWifi(true);
        take();
        startFailed("dns", true);
        assertEquals("[start]", take());
    }

    @Test public void wifiBackDuringBackoffStartsAtOnce() {
        running(0);
        exit(139, false, false, 5000);
        take();
        s.onWifi(false);
        s.onWifi(true);
        assertEquals("[cancel, start]", take());
    }

    @Test public void retryAfterFailureRestartsTheSupervisor() {
        s.begin(true);
        startFailed("no API key", false);
        take();
        s.begin(true);
        assertEquals("[start]", take());
    }

    @Test public void shutdownStopsEverythingAndIgnoresLateEvents() {
        running(0);
        s.shutdown();
        assertEquals("[cancel, stop]", take());
        exit(139, false, true, 5000);
        startFailed("x", true);
        s.onTimer();
        s.onWifi(false);
        s.onWifi(true);
        s.begin(true);
        assertEquals("[]", take());
        assertEquals(Supervisor.State.SHUTDOWN, s.state());
    }

    @Test public void startedAfterShutdownIsStopped() {
        s.begin(true);
        s.shutdown();
        take();
        started(1000); // the worker finished the fork after shutdown
        assertEquals("[stop]", take());
    }

    @Test public void restartStopsAndStartsARunningSoloist() {
        running(0);
        s.restart();
        assertEquals("[stop]", take());
        exit(143, false, true, 1000);
        assertEquals("[start]", take());
    }

    @Test public void restartCutsBackoffShortAndRetriesAFailure() {
        running(0);
        exit(139, false, false, 1000);
        take();
        s.restart();
        assertEquals("[cancel, start]", take());
        startFailed("no API key", false);
        take();
        s.restart();
        assertEquals("[start]", take());
    }

    @Test public void restartWithoutWifiWaits() {
        s.begin(false);
        s.restart();
        assertEquals("[]", take());
        assertEquals(Supervisor.State.WAIT_WIFI, s.state());
    }

    // --- exits racing the start (C1) ---

    @Test public void exitBeforeStartedBacksOff() {
        s.begin(true);
        take();
        exit(139, false, false, 500); // crashed before the worker reported the start
        assertEquals("[]", take());
        started(1000);
        assertEquals("[schedule 1000]", take());
        assertEquals(Supervisor.State.BACKOFF, s.state());
    }

    @Test public void expiredBeforeStartedRefreshes() {
        s.begin(true);
        take();
        exit(10, true, false, 500);
        started(1000);
        assertEquals("[start refresh]", take());
    }

    @Test public void exitBeforeStartedWithPendingRestartStartsAgain() {
        s.begin(true);
        s.onWifi(false);
        s.onWifi(true); // restart wanted once started
        take();
        exit(143, false, false, 500);
        started(1000);
        assertEquals("[start]", take());
        assertEquals(Supervisor.State.STARTING, s.state());
    }

    @Test public void staleExitsAreIgnored() {
        running(0);
        int old = gen;
        s.restart();
        s.onExit(old, 143, false, true, 1000);
        assertEquals("[stop, start]", take());
        s.onExit(old, 139, false, false, 2000); // the same run reported twice
        s.onStarted(old, 2000);
        assertEquals("[]", take());
        assertEquals(Supervisor.State.STARTING, s.state());
        started(3000);
        assertEquals(Supervisor.State.RUNNING, s.state());
    }

    @Test public void restartWhileRestartingWaitsForTheExit() {
        running(0);
        s.restart();
        s.restart();
        assertEquals("[stop]", take());
        exit(143, false, true, 1000);
        assertEquals("[start]", take());
    }

    @Test public void restartWhileWaitingForWifiKeepsWaiting() {
        s.begin(false);
        s.restart();
        s.restart();
        assertEquals("[]", take());
        assertEquals(Supervisor.State.WAIT_WIFI, s.state());
        s.onWifi(true);
        assertEquals("[start]", take());
    }

    // --- active (locks) ---

    @Test public void activeFollowsStateAndWifi() {
        s.begin(true);
        assertEquals("[true]", active.toString());
        started(0);
        s.onWifi(false); // Wi-Fi down while RUNNING
        assertEquals("[true, false]", active.toString());
        s.onWifi(true); // restart
        assertEquals("[true, false, true]", active.toString());
        exit(143, false, true, 1000);
        started(2000);
        exit(139, false, false, 3000); // backoff is still active
        assertEquals("[true, false, true]", active.toString());
        s.shutdown();
        assertEquals("[true, false, true, false]", active.toString());
    }

    @Test public void failedIsInactiveAndDebugRestartReactivates() {
        s.begin(true);
        startFailed("no API key", false);
        assertEquals("[true, false]", active.toString());
        s.restart();
        assertEquals("[true, false, true]", active.toString());
    }

    @Test public void waitingForWifiIsInactive() {
        s.begin(false);
        assertEquals("[]", active.toString());
        s.onWifi(true);
        assertEquals("[true]", active.toString());
    }

    // --- endless crash loops (I4) ---

    @Test public void givesUpAfterTenShortRuns() {
        s.begin(true);
        long t = 0;
        for (int i = 0; i < 9; i++) {
            started(t);
            exit(1, false, false, t + 2000);
            s.onTimer();
            t += 100000;
        }
        take();
        started(t);
        exit(1, false, false, t + 2000);
        String c = take();
        assertEquals("[failed " + Supervisor.KEEPS_EXITING + " (retry), schedule "
                + Supervisor.RETRY_COOLDOWN_MS + "]", c);
        assertEquals(Supervisor.State.FAILED, s.state());
    }

    @Test public void aLongerRunResetsTheShortRunCount() {
        s.begin(true);
        long t = 0;
        for (int i = 0; i < 9; i++) {
            started(t);
            exit(1, false, false, t + 2000);
            s.onTimer();
            t += 100000;
        }
        started(t);
        exit(1, false, false, t + 20000); // 20 s: not short
        s.onTimer();
        t += 100000;
        for (int i = 0; i < 9; i++) {
            started(t);
            exit(1, false, false, t + 2000);
            s.onTimer();
            t += 100000;
        }
        assertEquals(Supervisor.State.STARTING, s.state());
    }

    @Test public void debugRestartResetsTheShortRunCount() {
        s.begin(true);
        long t = 0;
        for (int i = 0; i < 9; i++) {
            started(t);
            exit(1, false, false, t + 2000);
            s.onTimer();
            t += 100000;
        }
        started(t);
        s.restart();
        exit(143, false, true, t + 1000);
        started(t + 2000);
        exit(1, false, false, t + 3000);
        assertEquals(Supervisor.State.BACKOFF, s.state());
    }

    // --- refresh cap (M1) ---

    @Test public void refreshesAreCappedPerDayEvenWithStableRuns() {
        running(0);
        long t = 0;
        String last = "";
        for (int i = 0; i < 4; i++) {
            t += 120000; // each run is stable (2 min)
            exit(10, true, false, t);
            last = take();
            s.onTimer();
            started(t + 1000);
            take();
        }
        assertTrue(last, last.startsWith("[failed "));
    }

    @Test public void refreshesSpreadOverDaysNeverGiveUp() {
        running(0);
        long t = 0;
        for (int i = 0; i < 6; i++) {
            t += 25L * 3600 * 1000;
            exit(10, true, false, t);
            assertEquals("[start refresh]", take());
            started(t + 1000);
        }
    }

    // --- errors (I4) ---

    @Test public void recognisesAuthErrorsButNotTheStartupNoise() {
        assertTrue(Supervisor.isAuthError("login failed: bad credentials"));
        assertTrue(Supervisor.isAuthError("Invalid API key"));
        assertTrue(Supervisor.isAuthError("unauthorized"));
        assertTrue(Supervisor.isAuthError("request failed\nlogin failed: bad credentials"));
        assertFalse(Supervisor.isAuthError("login succeeded"));
        assertFalse(Supervisor.isAuthError("Login successful"));
        assertFalse(Supervisor.isAuthError("command requires authentication"));
        assertFalse(Supervisor.isAuthError("track unavailable"));
    }

    // --- begin() goes through the Wi-Fi transitions ---

    @Test public void beginWithWifiUpLeavesWaitWifi() {
        s.begin(false);
        s.begin(true); // ACTION_START seen Wi-Fi before the debounced check did
        assertEquals("[start]", take());
        assertEquals(Supervisor.State.STARTING, s.state());
        s.onWifi(true); // the debounced check: no change
        assertEquals("[]", take());
    }

    @Test public void beginAfterWifiDropRestartsARunningSoloist() {
        running(0);
        s.onWifi(false);
        s.begin(true);
        assertEquals("[stop]", take());
        assertEquals(Supervisor.State.RESTARTING, s.state());
    }

    // --- start failures count toward giving up ---

    @Test public void givesUpAfterTenStartFailuresInARow() {
        s.begin(true);
        for (int i = 0; i < 9; i++) {
            startFailed("download failed", true);
            s.onTimer();
        }
        take();
        startFailed("download failed", true);
        String c = take();
        assertTrue(c, c.startsWith("[failed "));
        assertEquals(Supervisor.State.FAILED, s.state());
    }

    @Test public void aSuccessfulStartResetsStartFailures() {
        s.begin(true);
        for (int i = 0; i < 9; i++) {
            startFailed("download failed", true);
            s.onTimer();
        }
        started(0);
        exit(1, false, false, 100000); // a long run, then failures again
        s.onTimer();
        for (int i = 0; i < 9; i++) {
            startFailed("download failed", true);
            s.onTimer();
        }
        assertEquals(Supervisor.State.STARTING, s.state());
    }

    // --- giving up: fatal stays failed, non-fatal retries after a cooldown ---

    private void giveUpOnStartFailures() {
        s.begin(true);
        for (int i = 0; i < Supervisor.MAX_SHORT_RUNS - 1; i++) {
            startFailed("download failed", true);
            s.onTimer();
        }
        startFailed("download failed", true);
        assertEquals(Supervisor.State.FAILED, s.state());
    }

    @Test public void tenStartFailuresRetryAfterTheCooldown() {
        giveUpOnStartFailures();
        String c = take();
        assertTrue(c, c.endsWith("failed " + Supervisor.KEEPS_FAILING + " (retry), schedule "
                + Supervisor.RETRY_COOLDOWN_MS + "]"));
        assertTrue(s.willRetry());
        s.onTimer();
        assertEquals("[start]", take());
        assertEquals(Supervisor.State.STARTING, s.state());
        // A fresh count: nine more failures do not give up.
        for (int i = 0; i < Supervisor.MAX_SHORT_RUNS - 1; i++) {
            startFailed("download failed", true);
            s.onTimer();
        }
        assertEquals(Supervisor.State.STARTING, s.state());
    }

    @Test public void nonFatalGiveUpRetriesWhenWifiComesBack() {
        giveUpOnStartFailures();
        take();
        s.onWifi(false);
        assertEquals("[]", take());
        assertEquals(Supervisor.State.FAILED, s.state());
        s.onWifi(true);
        assertEquals("[cancel, start]", take());
    }

    @Test public void fatalGiveUpIgnoresTimerAndWifi() {
        s.begin(true);
        startFailed("no API key", false);
        assertEquals("[start, failed no API key]", take());
        assertFalse(s.willRetry());
        s.onTimer();
        s.onWifi(false);
        s.onWifi(true);
        assertEquals("[]", take());
        assertEquals(Supervisor.State.FAILED, s.state());
        s.restart(); // the user taps retry
        assertEquals("[start]", take());
    }

    @Test public void shortRunsAfterAnAuthErrorAreFatal() {
        s.begin(true);
        long t = 0;
        for (int i = 0; i < Supervisor.MAX_SHORT_RUNS; i++) {
            started(t);
            s.onAuthError();
            exit(1, false, false, t + 2000);
            if (i < Supervisor.MAX_SHORT_RUNS - 1) s.onTimer();
            t += 100000;
        }
        String c = take();
        assertTrue(c, c.endsWith("failed " + Supervisor.KEEPS_EXITING + "]"));
        s.onTimer();
        assertFalse(s.willRetry());
        s.onWifi(false);
        s.onWifi(true);
        assertEquals(Supervisor.State.FAILED, s.state());
    }

    @Test public void anAuthErrorOfAnEarlierRunDoesNotMakeTheGiveUpFatal() {
        s.begin(true);
        long t = 0;
        for (int i = 0; i < Supervisor.MAX_SHORT_RUNS; i++) {
            started(t);
            if (i == 0) s.onAuthError();
            exit(1, false, false, t + 2000);
            if (i < Supervisor.MAX_SHORT_RUNS - 1) s.onTimer();
            t += 100000;
        }
        assertTrue(s.willRetry());
    }

    @Test public void refreshCapIsFatal() {
        running(0);
        long t = 0;
        for (int i = 0; i < 4; i++) {
            t += 120000;
            exit(10, true, false, t);
            if (s.state() == Supervisor.State.FAILED) break;
            s.onTimer();
            started(t + 1000);
        }
        assertEquals(Supervisor.State.FAILED, s.state());
        assertFalse(s.willRetry());
        s.onTimer();
        assertEquals(Supervisor.State.FAILED, s.state());
    }

    @Test public void userRetryCancelsThePendingCooldown() {
        giveUpOnStartFailures();
        take();
        s.restart();
        assertEquals("[cancel, start]", take());
        assertFalse(s.willRetry());
    }

    // --- which give-ups need the settings ---

    @Test public void aStartThatCannotWorkNeedsSetup() {
        s.begin(true);
        startFailed("no API key", false);
        assertTrue(s.needsSetup());
        s.restart();
        assertFalse(s.needsSetup());
    }

    @Test public void shortRunsAfterALoginErrorNeedSetup() {
        s.begin(true);
        long t = 0;
        for (int i = 0; i < Supervisor.MAX_SHORT_RUNS; i++) {
            started(t);
            s.onAuthError();
            exit(1, false, false, t + 2000);
            if (i < Supervisor.MAX_SHORT_RUNS - 1) s.onTimer();
            t += 100000;
        }
        assertTrue(s.needsSetup());
    }

    @Test public void otherGiveUpsDoNotNeedSetup() {
        giveUpOnStartFailures();
        assertFalse(s.needsSetup());
        s.restart();
        // The refresh cap: fatal, but not the settings' fault.
        running(0);
        Supervisor r = s;
        long t = 0;
        for (int i = 0; i < 4 && r.state() != Supervisor.State.FAILED; i++) {
            t += 120000;
            exit(10, true, false, t);
            if (r.state() == Supervisor.State.FAILED) break;
            r.onTimer();
            started(t + 1000);
        }
        assertEquals(Supervisor.State.FAILED, r.state());
        assertFalse(r.needsSetup());
        assertFalse(new Supervisor(fx).needsSetup()); // idle
    }
}
