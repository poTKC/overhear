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

    @Test public void textIsThePercent() {
        assertEquals("76%", StatusStrip.batteryText(76));
        assertEquals("", StatusStrip.batteryText(-1));
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
