package com.potkc.overhear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IdleStopTest {
    private static final long MIN = 60000;
    private static final long T = 15 * MIN;
    private final IdleStop idle = new IdleStop();

    @Test public void stopsAfterTheTimeoutOfIdleness() {
        assertEquals(1000 + T, idle.update(1000, true, T));
        assertFalse(idle.shouldStop(1000 + T - 1));
        assertTrue(idle.shouldStop(1000 + T));
        assertTrue(idle.shouldStop(1000 + T + 5 * MIN)); // a late timer still stops
    }

    @Test public void moreIdleUpdatesKeepTheStartOfTheStretch() {
        idle.update(1000, true, T);
        assertEquals(1000 + T, idle.update(1000 + 5 * MIN, true, T));
        assertEquals(1000 + T, idle.update(1000 + 10 * MIN, true, T));
        assertTrue(idle.shouldStop(1000 + T));
    }

    @Test public void playingOrAScreenEndsTheStretch() {
        idle.update(1000, true, T);
        assertEquals(-1, idle.update(1000 + 10 * MIN, false, T));
        assertFalse(idle.shouldStop(1000 + T));
        // Idle again (paused, or the screen closed): a fresh countdown.
        assertEquals(1000 + 12 * MIN + T, idle.update(1000 + 12 * MIN, true, T));
        assertFalse(idle.shouldStop(1000 + 12 * MIN + T - 1));
    }

    @Test public void resetStartsTheCountdownOver() {
        idle.update(1000, true, T);
        idle.reset();
        assertFalse(idle.shouldStop(1000 + T));
        assertEquals(1000 + 14 * MIN + T, idle.update(1000 + 14 * MIN, true, T));
        assertFalse(idle.shouldStop(1000 + T));
    }

    @Test public void zeroNeverStops() {
        assertEquals(-1, idle.update(1000, true, 0));
        assertFalse(idle.shouldStop(Long.MAX_VALUE));
        assertEquals(-1, idle.update(1000 + 60 * MIN, true, 0));
    }

    @Test public void aNewTimeoutAppliesToTheStretchUnderWay() {
        idle.update(1000, true, 30 * MIN);
        assertEquals(1000 + 5 * MIN, idle.update(1000 + 10 * MIN, true, 5 * MIN));
        assertTrue(idle.shouldStop(1000 + 10 * MIN));
        // Turned off meanwhile: no stop, and back on counts from the same start.
        assertEquals(-1, idle.update(1000 + 11 * MIN, true, 0));
        assertFalse(idle.shouldStop(1000 + 11 * MIN));
        assertEquals(1000 + T, idle.update(1000 + 12 * MIN, true, T));
    }

    @Test public void beforeAnyUpdateNothingStops() {
        assertFalse(idle.shouldStop(0));
        assertFalse(idle.shouldStop(Long.MAX_VALUE));
    }

    @Test public void minutesToTimeout() {
        assertEquals(15 * MIN, IdleStop.timeoutMs(15));
        assertEquals(0, IdleStop.timeoutMs(0));
        assertEquals(0, IdleStop.timeoutMs(-5));
    }
}
