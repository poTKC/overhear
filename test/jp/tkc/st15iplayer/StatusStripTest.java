package jp.tkc.st15iplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class StatusStripTest {
    @Test public void percentFromLevelAndScale() {
        assertEquals(76, StatusStrip.percent(76, 100));
        assertEquals(50, StatusStrip.percent(1, 2));
        assertEquals(67, StatusStrip.percent(2, 3)); // rounded
        assertEquals(-1, StatusStrip.percent(-1, 100)); // unknown
        assertEquals(-1, StatusStrip.percent(50, 0));
        assertEquals(100, StatusStrip.percent(120, 100));
    }

    @Test public void textIsTheNumberOnly() {
        assertEquals("76", StatusStrip.batteryText(76));
        assertEquals("100", StatusStrip.batteryText(100));
        assertEquals("", StatusStrip.batteryText(-1));
    }

    @Test public void clockFollowsThe24HourSetting() {
        assertEquals("23:05", StatusStrip.clockText(23, 5, true));
        assertEquals("0:00", StatusStrip.clockText(0, 0, true));
        assertEquals("9:30", StatusStrip.clockText(9, 30, true));
    }

    @Test public void clockIn12HourModeHasNoAmPm() {
        // No AM/PM (午前/午後 on this phone): the digits font has digits and ':' only.
        assertEquals("11:05", StatusStrip.clockText(23, 5, false));
        assertEquals("12:00", StatusStrip.clockText(0, 0, false));
        assertEquals("12:59", StatusStrip.clockText(12, 59, false));
        assertEquals("9:30", StatusStrip.clockText(9, 30, false));
    }

    @Test public void everythingTheDigitsFontShowsIsInItsSubset() {
        String shown = StatusStrip.clockText(23, 59, true) + StatusStrip.clockText(10, 0, false)
                + StatusStrip.batteryText(100) + PlayerText.time(3723000) + PlayerText.time(59000);
        for (char ch : shown.toCharArray()) {
            assertEquals("'" + ch + "' in " + StatusStrip.DIGITS_FONT_CHARS, true,
                    StatusStrip.DIGITS_FONT_CHARS.indexOf(ch) >= 0);
        }
    }

    @Test public void colourFollowsTheThresholds() {
        assertEquals(StatusStrip.GREEN, StatusStrip.batteryColor(100, false));
        assertEquals(StatusStrip.GREEN, StatusStrip.batteryColor(50, false));
        assertEquals(StatusStrip.AMBER, StatusStrip.batteryColor(49, false));
        assertEquals(StatusStrip.AMBER, StatusStrip.batteryColor(20, false));
        assertEquals(StatusStrip.RED, StatusStrip.batteryColor(19, false));
        assertEquals(StatusStrip.RED, StatusStrip.batteryColor(0, false));
        assertEquals(StatusStrip.UNKNOWN, StatusStrip.batteryColor(-1, false));
    }

    @Test public void chargingHasItsOwnColour() {
        assertEquals(StatusStrip.CHARGING, StatusStrip.batteryColor(10, true));
        assertEquals(StatusStrip.CHARGING, StatusStrip.batteryColor(90, true));
        assertNotEquals(StatusStrip.GREEN, StatusStrip.CHARGING);
        assertNotEquals(StatusStrip.AMBER, StatusStrip.CHARGING);
        assertNotEquals(StatusStrip.RED, StatusStrip.CHARGING);
    }

    @Test public void theStripShowsOnlyInFullScreen() {
        assertEquals(true, StatusStrip.visible(true));
        assertEquals(false, StatusStrip.visible(false));
    }
}
