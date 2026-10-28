package com.potkc.overhear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.potkc.overhear.spotify.SpotifyConfig;

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
        SetupValues v = new SetupValues(" key ", " Kitchen ", "M", null, false, true, 5);
        assertEquals("key", v.keyToStore());
        assertEquals("Kitchen", v.deviceName());
        assertFalse(v.autoStart());
        assertTrue(v.fullscreen());
        assertEquals(5, v.idleStopMinutes());
        v = new SetupValues("k", "n", "M", null, true, false, 0);
        assertTrue(v.autoStart());
        assertFalse(v.fullscreen());
        assertEquals(0, v.idleStopMinutes()); // never
    }

    @Test public void aNegativeIdleStopIsNever() {
        assertEquals(0, new SetupValues("k", "n", "M", null, true, true, -1).idleStopMinutes());
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
        assertNull(new SetupValues(null, "n", "M", null, true, true, 15).keyToStore());
        assertNull(new SetupValues("  ", "n", "M", null, true, true, 15).keyToStore());
    }

    @Test public void aMissingNameIsTheDefault() {
        assertEquals("ST15i", new SetupValues("k", null, " ST15i ", null, true, true, 15).deviceName());
        assertEquals("ST15i", new SetupValues("k", "   ", "ST15i", null, true, true, 15).deviceName());
        assertEquals("Overhear", new SetupValues("k", null, null, null, true, true, 15).deviceName());
        assertEquals("Overhear", new SetupValues("k", "  ", "  ", null, true, true, 15).deviceName());
    }

    @Test public void clientIdsAre32HexDigitsOrBlank() {
        assertTrue(SetupValues.isValidClientId("0123456789abcdef0123456789abcdef"));
        assertTrue(SetupValues.isValidClientId(" 0123456789ABCDEF0123456789abcdef\n"));
        assertTrue(SetupValues.isValidClientId(""));
        assertTrue(SetupValues.isValidClientId("   "));
        assertTrue(SetupValues.isValidClientId(null));
        assertFalse(SetupValues.isValidClientId("0123456789abcdef0123456789abcde")); // 31
        assertFalse(SetupValues.isValidClientId("0123456789abcdef0123456789abcdef0")); // 33
        assertFalse(SetupValues.isValidClientId("0123456789abcdef0123456789abcdeg"));
        assertFalse(SetupValues.isValidClientId("0123456789abcdef 123456789abcdef"));
    }

    @Test public void aClientIdIsStoredTrimmedInLowerCase() {
        SetupValues v = new SetupValues("k", "n", "M", " 0123456789ABCDEF0123456789abcdef ", true, true, 15);
        assertEquals("0123456789abcdef0123456789abcdef", v.clientIdToStore());
        assertEquals("0123456789abcdef0123456789abcdef", v.spotifyClientId());
    }

    @Test public void aBlankClientIdIsTheBuiltInOne() {
        for (String blank : new String[] {null, "", "  "}) {
            SetupValues v = new SetupValues("k", "n", "M", blank, true, true, 15);
            assertNull(v.clientIdToStore());
            assertEquals(SpotifyConfig.CLIENT_ID, v.spotifyClientId());
        }
        assertEquals(SpotifyConfig.CLIENT_ID, SetupValues.effectiveClientId(null));
        assertEquals(SpotifyConfig.CLIENT_ID, SetupValues.effectiveClientId(" "));
        assertEquals("0123456789abcdef0123456789abcdef",
                SetupValues.effectiveClientId("0123456789abcdef0123456789abcdef"));
        assertTrue(SetupValues.isValidClientId(SpotifyConfig.CLIENT_ID));
    }
}
