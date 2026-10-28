package com.potkc.overhear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SetupValuesTest {
    @Test public void defaults() {
        assertTrue(SetupValues.DEFAULT_AUTO_START);
        assertTrue(SetupValues.DEFAULT_FULLSCREEN); // the user asked for full screen
        assertEquals("Overhear", SetupValues.FALLBACK_DEVICE_NAME);
        assertEquals(15, SetupValues.DEFAULT_IDLE_STOP_MINUTES);
    }

    @Test public void theDefaultNameIsTheTrimmedModelOrOverhear() {
        assertEquals("ST15i", SetupValues.defaultDeviceName("ST15i"));
        assertEquals("Pixel 3a", SetupValues.defaultDeviceName("  Pixel 3a \n"));
        assertEquals("Overhear", SetupValues.defaultDeviceName(""));
        assertEquals("Overhear", SetupValues.defaultDeviceName("   "));
        assertEquals("Overhear", SetupValues.defaultDeviceName(null));
    }

    @Test public void keepsEverySettingForTheSingleCommit() {
        SetupValues v = new SetupValues(" key ", " Kitchen ", "M", false, true, 5);
        assertEquals("key", v.keyToStore());
        assertEquals("Kitchen", v.deviceName());
        assertFalse(v.autoStart());
        assertTrue(v.fullscreen());
        assertEquals(5, v.idleStopMinutes());
        v = new SetupValues("k", "n", "M", true, false, 0);
        assertTrue(v.autoStart());
        assertFalse(v.fullscreen());
        assertEquals(0, v.idleStopMinutes()); // never
    }

    @Test public void aNegativeIdleStopIsNever() {
        assertEquals(0, new SetupValues("k", "n", "M", true, true, -1).idleStopMinutes());
    }

    @Test public void idleStopChoicesMatchTheSpinner() {
        // 5分, 15分, 30分, しない (R.array.setup_idle_stop_entries)
        assertArrayEquals(new int[] {5, 15, 30, 0}, SetupValues.IDLE_STOP_CHOICES);
        for (int i = 0; i < SetupValues.IDLE_STOP_CHOICES.length; i++) {
            assertEquals(i, SetupValues.idleStopIndex(SetupValues.idleStopMinutes(i)));
        }
        assertEquals(15, SetupValues.idleStopMinutes(SetupValues.idleStopIndex(15)));
        assertEquals(0, SetupValues.idleStopMinutes(3));
    }

    @Test public void anIdleStopNotOfferedShowsTheDefault() {
        assertEquals(1, SetupValues.idleStopIndex(7));
        assertEquals(1, SetupValues.idleStopIndex(-1));
        assertEquals(15, SetupValues.idleStopMinutes(-1));
        assertEquals(15, SetupValues.idleStopMinutes(4));
    }

    @Test public void aBlankKeyKeepsTheStoredOne() {
        assertNull(new SetupValues(null, "n", "M", true, true, 15).keyToStore());
        assertNull(new SetupValues("  ", "n", "M", true, true, 15).keyToStore());
    }

    @Test public void aMissingNameIsTheDefault() {
        assertEquals("ST15i", new SetupValues("k", null, " ST15i ", true, true, 15).deviceName());
        assertEquals("ST15i", new SetupValues("k", "   ", "ST15i", true, true, 15).deviceName());
        assertEquals("Overhear", new SetupValues("k", null, null, true, true, 15).deviceName());
        assertEquals("Overhear", new SetupValues("k", "  ", "  ", true, true, 15).deviceName());
    }
}
