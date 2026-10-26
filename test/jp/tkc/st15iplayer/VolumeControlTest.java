package jp.tkc.st15iplayer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VolumeControlTest {
    private final VolumeControl v = new VolumeControl();

    @Test public void showsTheServerVolumeUntilTheUserMovesIt() {
        assertEquals(-1, v.display(0));
        v.onServer(40);
        assertEquals(40, v.display(0));
        assertEquals(0, v.change(70, 1000));
        assertEquals(70, v.display(1000));
        v.onServer(41); // an echo of an older command
        assertEquals(70, v.display(1000 + VolumeControl.HOLD_MS - 1));
        assertEquals(41, v.display(1000 + VolumeControl.HOLD_MS));
    }

    @Test public void throttlesSendsWhileDragging() {
        assertEquals(0, v.change(10, 1000));
        assertEquals(10, v.flush(1000));
        assertEquals(-1, v.flush(1000)); // nothing pending
        assertEquals(VolumeControl.INTERVAL_MS - 100, v.change(20, 1100));
        assertEquals(VolumeControl.INTERVAL_MS - 200, v.change(30, 1200));
        assertEquals(30, v.flush(1300)); // the latest value
        assertEquals(VolumeControl.INTERVAL_MS, v.change(40, 1300));
    }

    @Test public void dragKeepsTheUserValueShown() {
        v.onServer(50);
        v.change(10, 0);
        v.flush(0);
        v.setDragging(true);
        v.onServer(12);
        assertEquals(10, v.display(100000));
        v.setDragging(false);
        assertEquals(10, v.display(VolumeControl.HOLD_MS - 1));
        assertEquals(12, v.display(100000));
    }

    @Test public void stepsFromWhatIsShownAndClamps() {
        assertEquals(-1, v.step(5, 0)); // unknown volume: nothing to step from
        v.onServer(98);
        assertEquals(0, v.step(5, 0));
        assertEquals(100, v.flush(0));
        assertEquals(100, v.display(0));
        v.step(-5, 10);
        v.step(-5, 20);
        assertEquals(90, v.display(20));
        assertEquals(90, v.flush(VolumeControl.INTERVAL_MS));
        v.onServer(3);
        v.step(-5, 100000);
        assertEquals(0, v.display(100000));
    }
}
