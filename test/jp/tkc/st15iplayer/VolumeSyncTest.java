package jp.tkc.st15iplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class VolumeSyncTest {
    private static final int MAX = 15;

    /** Records the decisions; device is the STREAM_MUSIC index as the service would read it back. */
    private static final class Fake implements VolumeSync.Target {
        int device = 8;
        /** Android's safe headphone volume: raises above it are refused. */
        int safe = MAX;
        final List<Integer> setIndex = new ArrayList<Integer>();
        final List<Integer> sent = new ArrayList<Integer>();

        @Override public int setDeviceIndex(int index) {
            setIndex.add(index);
            if (index <= safe || index <= device) device = index;
            else if (device < safe) device = safe;
            return device;
        }

        @Override public void sendPercent(int percent) {
            sent.add(percent);
        }
    }

    private final Fake t = new Fake();
    private final VolumeSync sync = new VolumeSync(MAX, t);

    /** Logs in with Soloist at percent; the device volume is adopted. */
    private void login(int percent) {
        sync.onSoloist(true, percent, t.device, 0);
    }

    // --- mapping ---

    @Test public void mapsPercentToIndexAndBack() {
        assertEquals(0, VolumeSync.percentToIndex(0, MAX));
        assertEquals(6, VolumeSync.percentToIndex(40, MAX));
        assertEquals(7, VolumeSync.percentToIndex(46, MAX));
        assertEquals(7, VolumeSync.percentToIndex(47, MAX));
        assertEquals(15, VolumeSync.percentToIndex(100, MAX));
        assertEquals(0, VolumeSync.percentToIndex(-5, MAX));
        assertEquals(15, VolumeSync.percentToIndex(130, MAX));
        assertEquals(40, VolumeSync.indexToPercent(6, MAX));
        assertEquals(47, VolumeSync.indexToPercent(7, MAX));
        assertEquals(53, VolumeSync.indexToPercent(8, MAX));
        assertEquals(100, VolumeSync.indexToPercent(15, MAX));
        assertEquals(0, VolumeSync.indexToPercent(0, MAX));
    }

    @Test public void everyIndexSurvivesTheRoundTripAndAnEchoOffByOne() {
        for (int max = 1; max <= 31; max++) {
            for (int i = 0; i <= max; i++) {
                int p = VolumeSync.indexToPercent(i, max);
                assertEquals(i, VolumeSync.percentToIndex(p, max));
                if (max <= 30) { // steps of 3.3 % or more: a 1 % quantization never crosses
                    assertEquals(i, VolumeSync.percentToIndex(Math.max(0, p - 1), max));
                    assertEquals(i, VolumeSync.percentToIndex(Math.min(100, p + 1), max));
                }
            }
        }
    }

    // --- startup ---

    @Test public void loginAdoptsTheDeviceVolume() {
        t.device = 8;
        login(100); // Soloist's default after a restart
        assertEquals(list(53), t.sent);
        assertTrue(t.setIndex.isEmpty());
        assertTrue(sync.isReady());
        // Its echo does not move the device.
        sync.onSoloist(true, 53, t.device, 100);
        assertTrue(t.setIndex.isEmpty());
        assertEquals(list(53), t.sent);
    }

    @Test public void loginWithAMatchingVolumeSendsNothing() {
        t.device = 6;
        login(41); // maps to 6
        assertTrue(t.sent.isEmpty());
        assertTrue(t.setIndex.isEmpty());
    }

    @Test public void loginWithAnUnknownVolumeStillAdopts() {
        t.device = 3;
        login(-1);
        assertEquals(list(20), t.sent);
    }

    @Test public void theFirstVolumeAfterAnUnknownOneIsNotAPhoneChange() {
        // Seen on the device: auth_state (logged in, no volume yet), then
        // playback_state with Soloist's old 40 before the echo of our 60.
        t.device = 9;
        login(-1);
        assertEquals(list(60), t.sent);
        sync.onSoloist(true, 40, t.device, 10);
        assertTrue(t.setIndex.isEmpty());
        sync.onSoloist(true, 60, t.device, 20); // the echo
        assertTrue(t.setIndex.isEmpty());
        sync.onSoloist(true, 20, t.device, 30); // the phone, later
        assertEquals(list(3), t.setIndex);
    }

    @Test public void nothingHappensBeforeLogin() {
        sync.onSoloist(false, 40, t.device, 0);
        sync.onDevice(12, 0);
        assertTrue(t.sent.isEmpty());
        assertTrue(t.setIndex.isEmpty());
        assertFalse(sync.isReady());
    }

    @Test public void reconnectAdoptsAgain() {
        login(53);
        sync.reset();
        t.device = 10;
        sync.onDevice(10, 50); // while disconnected: not sent
        assertTrue(t.sent.isEmpty());
        sync.onSoloist(true, 100, t.device, 100);
        assertEquals(list(67), t.sent);
    }

    @Test public void logoutStopsSyncingUntilTheNextLogin() {
        login(53);
        sync.onSoloist(false, 53, t.device, 10);
        assertFalse(sync.isReady());
        sync.onDevice(3, 20);
        assertTrue(t.sent.isEmpty());
    }

    // --- Soloist -> device ---

    @Test public void phoneVolumeSetsTheDeviceAndIsNotSentBack() {
        login(53); // device 8
        sync.onSoloist(true, 40, t.device, 100);
        assertEquals(list(6), t.setIndex);
        // The volume broadcast for our own change.
        sync.onDevice(t.device, 110);
        assertTrue(t.sent.isEmpty());
    }

    @Test public void phoneVolumeBetweenStepsIsLeftAlone() {
        login(53);
        sync.onSoloist(true, 45, t.device, 100); // 6.75 -> 7
        assertEquals(list(7), t.setIndex);
        sync.onDevice(t.device, 110);
        assertTrue(t.sent.isEmpty()); // the phone keeps 45
    }

    @Test public void phoneChangeWithinTheSameIndexDoesNotTouchTheDevice() {
        login(53);
        sync.onSoloist(true, 54, t.device, 100);
        sync.onSoloist(true, 52, t.device, 200);
        assertTrue(t.setIndex.isEmpty());
        assertTrue(t.sent.isEmpty());
    }

    @Test public void repeatedSnapshotsDoNothing() {
        login(53);
        for (int i = 0; i < 5; i++) sync.onSoloist(true, 53, t.device, 100 + i);
        assertTrue(t.setIndex.isEmpty());
        assertTrue(t.sent.isEmpty());
    }

    @Test public void refusedRaiseIsReportedBackAsTheIndexInEffect() {
        t.safe = 10;
        login(53); // device 8
        sync.onSoloist(true, 80, t.device, 100);
        assertEquals(list(12), t.setIndex);
        assertEquals(10, t.device);
        assertEquals(list(67), t.sent); // the phone's slider drops to what plays
        sync.onDevice(t.device, 110); // the broadcast for it
        sync.onSoloist(true, 67, t.device, 120); // the echo
        assertEquals(list(12), t.setIndex);
        assertEquals(list(67), t.sent);
        // The user accepts the warning on the device: the raise goes through.
        t.safe = MAX;
        t.device = 12;
        sync.onDevice(12, 5000);
        assertEquals(list(67, 80), t.sent);
    }

    @Test public void mutedPhoneMutesTheDevice() {
        login(53);
        sync.onSoloist(true, 0, t.device, 100);
        assertEquals(list(0), t.setIndex);
    }

    // --- device -> Soloist ---

    @Test public void deviceChangeIsSentAndItsEchoIgnored() {
        login(53); // device 8
        t.device = 9;
        sync.onDevice(9, 100);
        assertEquals(list(60), t.sent);
        sync.onSoloist(true, 60, t.device, 200);
        assertTrue(t.setIndex.isEmpty());
        assertEquals(list(60), t.sent);
    }

    @Test public void quantizedEchoIsMatchedByIndex() {
        login(53);
        t.device = 7;
        sync.onDevice(7, 100);
        assertEquals(list(47), t.sent);
        sync.onSoloist(true, 46, t.device, 200); // 47 came back as 46
        assertTrue(t.setIndex.isEmpty());
        sync.onDevice(t.device, 210);
        assertEquals(list(47), t.sent);
    }

    @Test public void sameDeviceIndexIsNotSentTwice() {
        login(53);
        sync.onDevice(8, 100);
        sync.onDevice(8, 200);
        assertTrue(t.sent.isEmpty());
    }

    @Test public void staleEchoesDuringAKeyHoldDoNotPullTheDeviceBack() {
        login(53); // device 8
        for (int i = 9; i <= 12; i++) {
            t.device = i;
            sync.onDevice(i, i * 10);
        }
        assertEquals(list(60, 67, 73, 80), t.sent);
        // The echoes arrive late, in order, while the device is already at 12.
        sync.onSoloist(true, 60, t.device, 200);
        sync.onSoloist(true, 67, t.device, 210);
        sync.onSoloist(true, 73, t.device, 220);
        sync.onSoloist(true, 80, t.device, 230);
        assertTrue(t.setIndex.isEmpty());
        assertEquals(12, t.device);
    }

    @Test public void coalescedEchoDropsTheOlderSends() {
        login(53);
        for (int i = 9; i <= 11; i++) {
            t.device = i;
            sync.onDevice(i, i * 10);
        }
        sync.onSoloist(true, 73, t.device, 200); // only the last one is reported
        assertTrue(t.setIndex.isEmpty());
        // A later phone change to an index sent earlier is a real change.
        sync.onSoloist(true, 60, t.device, 300);
        assertEquals(list(9), t.setIndex);
    }

    @Test public void forgottenSendsDoNotSwallowLaterPhoneChanges() {
        login(53);
        t.device = 9;
        sync.onDevice(9, 1000); // its echo never comes
        sync.onSoloist(true, 61, t.device, 1000 + VolumeSync.ECHO_MS + 1);
        // 61 maps to 9, which the device already has: nothing to set either way.
        assertTrue(t.setIndex.isEmpty());
        sync.onSoloist(true, 20, t.device, 1000 + VolumeSync.ECHO_MS + 2);
        assertEquals(list(3), t.setIndex);
    }

    @Test public void phoneChangeWhileASendIsInFlight() {
        login(53);
        t.device = 9;
        sync.onDevice(9, 100); // sent 60
        sync.onSoloist(true, 80, t.device, 150); // the phone, before our echo
        assertEquals(list(12), t.setIndex);
        sync.onSoloist(true, 60, t.device, 200); // our echo: dropped
        assertEquals(list(12), t.setIndex);
        sync.onDevice(t.device, 210);
        assertEquals(list(60), t.sent);
    }

    // --- convergence ---

    /**
     * Drives both sides with a fake Soloist that echoes every set_volume
     * (rounded through its 16-bit volume, so 47 may come back as 46) and a
     * fake AudioManager that broadcasts every index change, and checks that
     * nothing keeps bouncing.
     */
    @Test public void convergesWithoutOscillation() {
        final int[] soloist = {100};
        final List<Integer> echoes = new ArrayList<Integer>();
        final List<Integer> broadcasts = new ArrayList<Integer>();
        final int[] device = {8};
        final int[] commands = {0};
        VolumeSync s = new VolumeSync(MAX, new VolumeSync.Target() {
            @Override public int setDeviceIndex(int index) {
                device[0] = Math.min(index, 13); // a safe-volume limit too
                broadcasts.add(device[0]);
                return device[0];
            }

            @Override public void sendPercent(int percent) {
                commands[0]++;
                int v16 = percent * 65535 / 100; // Spotify's internal scale, truncated
                soloist[0] = v16 * 100 / 65535;
                echoes.add(soloist[0]);
            }
        });
        long now = 0;
        s.onSoloist(true, soloist[0], device[0], now);
        int[] script = {0, 1, 1, 1, -1, 50, 0, 1, -1, 23, 99, 0, -1, -1, 7};
        for (int step : script) {
            now += 50;
            if (step == 1 || step == -1) { // a volume key
                device[0] = Math.max(0, Math.min(MAX, device[0] + step));
                broadcasts.add(device[0]);
            } else if (step > 1) { // the phone's slider
                soloist[0] = step;
                echoes.add(step);
            }
            for (int round = 0; round < 20 && (!echoes.isEmpty() || !broadcasts.isEmpty()); round++) {
                now += 10;
                while (!broadcasts.isEmpty()) {
                    broadcasts.remove(0);
                    s.onDevice(device[0], now);
                }
                if (!echoes.isEmpty()) s.onSoloist(true, echoes.remove(0), device[0], now);
            }
            assertTrue("settled after step " + step, echoes.isEmpty() && broadcasts.isEmpty());
            assertEquals("agree after step " + step, device[0], VolumeSync.percentToIndex(soloist[0], MAX));
        }
        assertTrue("one command per key press or refused raise: " + commands[0], commands[0] <= 10);
    }

    private static List<Integer> list(Integer... v) {
        List<Integer> l = new ArrayList<Integer>();
        for (Integer i : v) l.add(i);
        return l;
    }
}
