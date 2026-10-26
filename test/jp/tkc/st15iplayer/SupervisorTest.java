package jp.tkc.st15iplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class SupervisorTest {
    private final List<String> calls = new ArrayList<String>();
    private Supervisor s;

    private final Supervisor.Effects fx = new Supervisor.Effects() {
        @Override public void start(boolean refresh) { calls.add(refresh ? "start refresh" : "start"); }
        @Override public void stop() { calls.add("stop"); }
        @Override public void schedule(long delayMs) { calls.add("schedule " + delayMs); }
        @Override public void cancelTimer() { calls.add("cancel"); }
        @Override public void failed(String reason) { calls.add("failed " + reason); }
    };

    @Before public void setUp() {
        s = new Supervisor(fx);
    }

    private String take() {
        String all = calls.toString();
        calls.clear();
        return all;
    }

    private void running(long now) {
        s.begin(true);
        s.onStarted(now);
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
        s.onStarted(0);
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
        s.onExit(139, false, false, 5000);
        assertEquals("[schedule 1000]", take());
        assertEquals(Supervisor.State.BACKOFF, s.state());
        s.onTimer();
        assertEquals("[start]", take());
        s.onStarted(10000);
        s.onExit(139, false, false, 11000);
        assertEquals("[schedule 2000]", take());
        s.onTimer();
        assertEquals("[start]", take());
        s.onStarted(20000);
        s.onExit(139, false, false, 21000);
        assertEquals("[schedule 4000]", take());
        s.onTimer();
        assertEquals("[start]", take());
        s.onStarted(30000);
        // Ran 60 s: back to 1 s.
        s.onExit(139, false, false, 90000);
        assertEquals("[schedule 1000]", take());
    }

    @Test public void requestedExitDoesNotRestart() {
        running(0);
        s.onExit(0, false, true, 5000);
        assertEquals("[]", take());
    }

    @Test public void expiredRefreshesAtOnceThenBacksOffThenGivesUp() {
        running(0);
        s.onExit(10, true, false, 1000);
        assertEquals("[start refresh]", take());
        s.onStarted(2000);
        s.onExit(10, true, false, 3000);
        assertEquals("[schedule 1000]", take());
        s.onTimer();
        assertEquals("[start refresh]", take());
        s.onStarted(5000);
        s.onExit(10, true, false, 6000);
        assertEquals("[schedule 2000]", take());
        s.onTimer();
        assertEquals("[start refresh]", take());
        s.onStarted(9000);
        s.onExit(10, true, false, 9500);
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
        s.onExit(10, true, false, 1000);
        s.onStarted(2000);
        s.onExit(10, true, false, 3000);
        s.onTimer();
        s.onStarted(5000);
        take();
        s.onExit(10, true, false, 5000 + 90L * 24 * 3600 * 1000);
        assertEquals("[start refresh]", take());
    }

    @Test public void failedStartRetriesWithBackoffKeepingRefresh() {
        running(0);
        s.onExit(10, true, false, 1000);
        take();
        s.onStartFailed("download failed", true);
        assertEquals("[schedule 1000]", take());
        s.onTimer();
        assertEquals("[start refresh]", take());
        s.onStartFailed("download failed", true);
        assertEquals("[schedule 2000]", take());
    }

    @Test public void fatalStartFailureGivesUp() {
        s.begin(true);
        take();
        s.onStartFailed("no API key", false);
        assertEquals("[failed no API key]", take());
        assertEquals(Supervisor.State.FAILED, s.state());
    }

    @Test public void crashWhileWifiDownWaitsForWifi() {
        running(0);
        s.onWifi(false);
        assertEquals("[]", take()); // a running Soloist is left alone
        s.onExit(139, false, false, 5000);
        assertEquals("[]", take());
        assertEquals(Supervisor.State.WAIT_WIFI, s.state());
        s.onWifi(true);
        assertEquals("[start]", take());
    }

    @Test public void wifiDownDuringBackoffCancelsTheTimer() {
        running(0);
        s.onExit(139, false, false, 5000);
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
        s.onExit(143, false, true, 5000);
        assertEquals("[start]", take());
    }

    @Test public void restartAlsoHandlesAnUnrequestedExitWhileStopping() {
        running(0);
        s.onWifi(false);
        s.onWifi(true);
        take();
        s.onExit(139, false, false, 5000); // crashed before the SIGTERM landed
        assertEquals("[start]", take());
    }

    @Test public void wifiBackDuringStartRestartsOnceStarted() {
        s.begin(true);
        s.onWifi(false);
        s.onWifi(true);
        assertEquals("[start]", take());
        s.onStarted(1000);
        assertEquals("[stop]", take());
        s.onExit(143, false, true, 2000);
        assertEquals("[start]", take());
        s.onStarted(3000);
        assertEquals("[]", take());
        assertEquals(Supervisor.State.RUNNING, s.state());
    }

    @Test public void wifiBackDuringStartThatFailsStartsAtOnce() {
        s.begin(true);
        s.onWifi(false);
        s.onWifi(true);
        take();
        s.onStartFailed("dns", true);
        assertEquals("[start]", take());
    }

    @Test public void wifiBackDuringBackoffStartsAtOnce() {
        running(0);
        s.onExit(139, false, false, 5000);
        take();
        s.onWifi(false);
        s.onWifi(true);
        assertEquals("[cancel, start]", take());
    }

    @Test public void retryAfterFailureRestartsTheSupervisor() {
        s.begin(true);
        s.onStartFailed("no API key", false);
        take();
        s.begin(true);
        assertEquals("[start]", take());
    }

    @Test public void shutdownStopsEverythingAndIgnoresLateEvents() {
        running(0);
        s.shutdown();
        assertEquals("[cancel, stop]", take());
        s.onExit(139, false, true, 5000);
        s.onStartFailed("x", true);
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
        s.onStarted(1000); // the worker finished the fork after shutdown
        assertEquals("[stop]", take());
    }

    @Test public void restartStopsAndStartsARunningSoloist() {
        running(0);
        s.restart();
        assertEquals("[stop]", take());
        s.onExit(143, false, true, 1000);
        assertEquals("[start]", take());
    }

    @Test public void restartCutsBackoffShortAndRetriesAFailure() {
        running(0);
        s.onExit(139, false, false, 1000);
        take();
        s.restart();
        assertEquals("[cancel, start]", take());
        s.onStartFailed("no API key", false);
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
}
