package com.potkc.overhear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.potkc.overhear.soloist.PlayerState;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class WebJumpTest {
    private static final long TIMEOUT = 8000;
    private static final long RECHECK = 1500;
    private static final String TARGET = "spotify:track:target";

    private long now = 1000;
    private final List<String> fx = new ArrayList<String>();
    private WebJump.Ticket ticket;
    private boolean fallbackPossible = true;
    private Boolean ok;
    private String why;
    private WebJump jump;

    private final WebJump.Clock clock = new WebJump.Clock() {
        @Override public long nowMs() {
            return now;
        }
    };

    private final WebJump.Effects effects = new WebJump.Effects() {
        @Override public void sendPlay(WebJump.Ticket t) {
            fx.add("sendPlay");
            ticket = t;
        }

        @Override public boolean startQueueJumpFallback() {
            fx.add("fallback");
            return fallbackPossible;
        }

        @Override public void log(String message) {
        }

        @Override public void finished(boolean success, String reason) {
            if (ok != null) throw new AssertionError("finished twice");
            fx.add("finished");
            ok = success;
            why = reason;
        }
    };

    @Before public void setUp() throws Exception {
        jump = new WebJump(3, "uid3", TARGET, clock, TIMEOUT, RECHECK, effects);
        jump.start(state("spotify:track:cur"));
    }

    private static PlayerState state(String trackUri) throws Exception {
        PlayerState s = new PlayerState();
        s.apply(new JSONObject("{\"type\":\"playback_state\",\"status\":\"playing\",\"is_active\":true,"
                + "\"item\":{\"uri\":\"" + trackUri + "\"}}"));
        return s.copy();
    }

    /** The Spotify thread picks the call up after waitMs in the queue; true if it sends the PUT. */
    private boolean callBegins(long waitMs) {
        now += waitMs;
        return ticket.begin();
    }

    /** Advances to the next timer the service would schedule, and runs it. */
    private void tick() {
        long ms = jump.msUntilDeadline();
        assertTrue("timer scheduled", ms >= 0);
        now += ms;
        jump.onTimer();
    }

    private void assertFx(String... expected) {
        assertEquals(Arrays.asList(expected), fx);
    }

    @Test public void success() {
        assertFx("sendPlay");
        assertTrue(jump.isRunning());
        assertTrue(callBegins(500));
        now += 3000;
        jump.onPlayDone();
        assertFx("sendPlay", "finished");
        assertEquals(Boolean.TRUE, ok);
        assertFalse(jump.isRunning());
        assertEquals(-1, jump.msUntilDeadline());
    }

    @Test public void failedButTheTargetIsAlreadyCurrent() throws Exception {
        assertTrue(callBegins(0));
        jump.onState(state(TARGET));
        jump.onPlayFailed("timeout");
        assertFx("sendPlay", "finished");
        assertEquals(Boolean.TRUE, ok);
    }

    @Test public void failedThenTheRecheckFindsTheTarget() throws Exception {
        assertTrue(callBegins(0));
        now += 2000;
        jump.onPlayFailed("HTTP 502");
        assertNull(ok);
        assertEquals(RECHECK, jump.msUntilDeadline());
        now += 700;
        jump.onState(state(TARGET));
        jump.onTimer(); // not due yet
        assertNull(ok);
        tick();
        assertFx("sendPlay", "finished");
        assertEquals(Boolean.TRUE, ok);
    }

    @Test public void failedThenFallsBackToQueueJump() {
        assertTrue(callBegins(0));
        jump.onPlayFailed("HTTP 502");
        tick();
        // QueueJump reports for itself: no finished here.
        assertFx("sendPlay", "fallback");
        assertNull(ok);
        assertFalse(jump.isRunning());
        jump.onTimer();
        jump.onPlayDone();
        assertFx("sendPlay", "fallback");
    }

    @Test public void fallbackNotPossibleFails() {
        fallbackPossible = false;
        assertTrue(callBegins(0));
        jump.onPlayFailed("HTTP 502");
        tick();
        assertFx("sendPlay", "fallback", "finished");
        assertEquals(Boolean.FALSE, ok);
    }

    @Test public void timesOutWithNoAnswerAfterTheCallBegan() {
        assertTrue(callBegins(5000));
        // The deadline counts from when the call began, not from the tap.
        assertEquals(TIMEOUT, jump.msUntilDeadline());
        now += TIMEOUT - 1;
        jump.onTimer();
        assertNull(ok);
        tick();
        assertFx("sendPlay", "finished");
        assertEquals(Boolean.FALSE, ok);
        jump.onPlayDone(); // late
        assertFx("sendPlay", "finished");
    }

    @Test public void queuedLongerThanTheTimeoutIsCancelled() {
        assertEquals(TIMEOUT, jump.msUntilDeadline());
        tick();
        assertFx("sendPlay", "finished");
        assertEquals(Boolean.FALSE, ok);
        assertTrue(why, why.contains("cancelled"));
        // The call reaches the front of the queue: it must not send the PUT.
        assertFalse(callBegins(1000));
        jump.onPlayDone();
        assertFx("sendPlay", "finished");
    }

    @Test public void beginningJustBeforeTheQueueTimeoutGetsAFullTimeout() {
        long scheduled = jump.msUntilDeadline();
        assertEquals(TIMEOUT, scheduled);
        now += TIMEOUT - 100;
        assertTrue(ticket.begin());
        // The timer scheduled for the queue deadline finds the call begun.
        now += 100;
        jump.onTimer();
        assertNull(ok);
        assertEquals(TIMEOUT - 100, jump.msUntilDeadline());
        now += 3000;
        jump.onPlayDone();
        assertEquals(Boolean.TRUE, ok);
    }

    @Test public void controlPressedWhileQueuedSendsNoPlay() {
        now += 2000;
        jump.onUserInput();
        assertFx("sendPlay", "finished");
        assertEquals(Boolean.FALSE, ok);
        assertFalse(callBegins(4000));
        jump.onPlayFailed("whatever");
        jump.onTimer();
        assertFx("sendPlay", "finished");
    }

    @Test public void controlPressedWhileTheCallRunsEndsIt() {
        assertTrue(callBegins(0));
        jump.onUserInput();
        assertEquals(Boolean.FALSE, ok);
        jump.onPlayDone();
        assertFx("sendPlay", "finished");
    }

    @Test public void lateCallbacksAfterTheEndAreIgnored() throws Exception {
        assertTrue(callBegins(0));
        jump.onPlayDone();
        jump.onPlayFailed("late");
        jump.onState(state("spotify:track:other"));
        jump.onUserInput();
        now += 60000;
        jump.onTimer();
        assertFx("sendPlay", "finished");
        assertEquals(Boolean.TRUE, ok);
    }

    @Test public void controlDuringTheRecheckEndsWithoutFallback() {
        assertTrue(callBegins(0));
        jump.onPlayFailed("HTTP 502");
        jump.onUserInput();
        now += RECHECK;
        jump.onTimer();
        assertFx("sendPlay", "finished");
        assertEquals(Boolean.FALSE, ok);
    }

    @Test public void theOverallTimeoutAlsoBoundsTheRecheck() {
        assertTrue(callBegins(0));
        now += TIMEOUT - 500;
        jump.onPlayFailed("HTTP 502");
        tick(); // the deadline comes before the recheck
        assertFx("sendPlay", "finished");
        assertEquals(Boolean.FALSE, ok);
    }

    @Test public void describesItsTarget() {
        assertEquals(3, jump.getIndex());
        assertEquals("uid3", jump.getUid());
        assertEquals(TARGET, jump.getUri());
        assertEquals(1000, jump.getStartMs());
        assertNotNull(ticket);
    }
}
