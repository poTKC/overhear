package jp.tkc.st15iplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SetupValuesTest {
    @Test public void defaults() {
        assertTrue(SetupValues.DEFAULT_AUTO_START);
        assertTrue(SetupValues.DEFAULT_FULLSCREEN); // the user asked for full screen
        assertEquals("ST15i", SetupValues.DEFAULT_DEVICE_NAME);
    }

    @Test public void keepsEverySettingForTheSingleCommit() {
        SetupValues v = new SetupValues(" key ", " Kitchen ", false, true);
        assertEquals("key", v.keyToStore());
        assertEquals("Kitchen", v.deviceName());
        assertFalse(v.autoStart());
        assertTrue(v.fullscreen());
        v = new SetupValues("k", "n", true, false);
        assertTrue(v.autoStart());
        assertFalse(v.fullscreen());
    }

    @Test public void aBlankKeyKeepsTheStoredOne() {
        assertNull(new SetupValues(null, "n", true, true).keyToStore());
        assertNull(new SetupValues("  ", "n", true, true).keyToStore());
    }

    @Test public void aMissingNameIsTheDefault() {
        assertEquals("ST15i", new SetupValues("k", null, true, true).deviceName());
        assertEquals("ST15i", new SetupValues("k", "   ", true, true).deviceName());
    }
}
