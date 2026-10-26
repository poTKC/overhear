package com.potkc.overhear;

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
    }

    @Test public void theDefaultNameIsTheTrimmedModelOrOverhear() {
        assertEquals("ST15i", SetupValues.defaultDeviceName("ST15i"));
        assertEquals("Pixel 3a", SetupValues.defaultDeviceName("  Pixel 3a \n"));
        assertEquals("Overhear", SetupValues.defaultDeviceName(""));
        assertEquals("Overhear", SetupValues.defaultDeviceName("   "));
        assertEquals("Overhear", SetupValues.defaultDeviceName(null));
    }

    @Test public void keepsEverySettingForTheSingleCommit() {
        SetupValues v = new SetupValues(" key ", " Kitchen ", "M", false, true);
        assertEquals("key", v.keyToStore());
        assertEquals("Kitchen", v.deviceName());
        assertFalse(v.autoStart());
        assertTrue(v.fullscreen());
        v = new SetupValues("k", "n", "M", true, false);
        assertTrue(v.autoStart());
        assertFalse(v.fullscreen());
    }

    @Test public void aBlankKeyKeepsTheStoredOne() {
        assertNull(new SetupValues(null, "n", "M", true, true).keyToStore());
        assertNull(new SetupValues("  ", "n", "M", true, true).keyToStore());
    }

    @Test public void aMissingNameIsTheDefault() {
        assertEquals("ST15i", new SetupValues("k", null, " ST15i ", true, true).deviceName());
        assertEquals("ST15i", new SetupValues("k", "   ", "ST15i", true, true).deviceName());
        assertEquals("Overhear", new SetupValues("k", null, null, true, true).deviceName());
        assertEquals("Overhear", new SetupValues("k", "  ", "  ", true, true).deviceName());
    }
}
