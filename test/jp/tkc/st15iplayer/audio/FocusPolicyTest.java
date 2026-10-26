package jp.tkc.st15iplayer.audio;

import static jp.tkc.st15iplayer.audio.FocusPolicy.Action.ABANDON;
import static jp.tkc.st15iplayer.audio.FocusPolicy.Action.DUCK;
import static jp.tkc.st15iplayer.audio.FocusPolicy.Action.NONE;
import static jp.tkc.st15iplayer.audio.FocusPolicy.Action.PAUSE;
import static jp.tkc.st15iplayer.audio.FocusPolicy.Action.RESUME;
import static jp.tkc.st15iplayer.audio.FocusPolicy.Action.UNDUCK;
import static jp.tkc.st15iplayer.audio.FocusPolicy.GAIN;
import static jp.tkc.st15iplayer.audio.FocusPolicy.LOSS;
import static jp.tkc.st15iplayer.audio.FocusPolicy.LOSS_TRANSIENT;
import static jp.tkc.st15iplayer.audio.FocusPolicy.LOSS_TRANSIENT_CAN_DUCK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FocusPolicyTest {
    @Test public void constantsMatchAudioManager() {
        assertEquals(1, GAIN);
        assertEquals(-1, LOSS);
        assertEquals(-2, LOSS_TRANSIENT);
        assertEquals(-3, LOSS_TRANSIENT_CAN_DUCK);
    }

    @Test public void permanentLossPausesWithoutResuming() {
        FocusPolicy p = new FocusPolicy();
        assertEquals(PAUSE, p.onChange(LOSS, true));
        assertFalse(p.holdOnPause());
        assertEquals(NONE, p.onChange(GAIN, false));
    }

    @Test public void permanentLossWhileNotPlayingGivesFocusBack() {
        assertEquals(ABANDON, new FocusPolicy().onChange(LOSS, false));
    }

    @Test public void transientLossPausesAndResumesOnGain() {
        FocusPolicy p = new FocusPolicy();
        assertEquals(PAUSE, p.onChange(LOSS_TRANSIENT, true));
        // Keep focus through the cork, or GAIN would never come.
        assertTrue(p.holdOnPause());
        assertEquals(RESUME, p.onChange(GAIN, false));
        assertFalse(p.holdOnPause());
        assertEquals(NONE, p.onChange(GAIN, true));
    }

    @Test public void transientLossWhileNotPlayingDoesNothing() {
        FocusPolicy p = new FocusPolicy();
        assertEquals(NONE, p.onChange(LOSS_TRANSIENT, false));
        assertFalse(p.holdOnPause());
        assertEquals(NONE, p.onChange(GAIN, false));
    }

    @Test public void permanentLossAfterTransientCancelsTheResume() {
        FocusPolicy p = new FocusPolicy();
        p.onChange(LOSS_TRANSIENT, true);
        assertEquals(ABANDON, p.onChange(LOSS, false));
        assertFalse(p.holdOnPause());
        assertEquals(NONE, p.onChange(GAIN, false));
    }

    @Test public void playingAgainOurselvesCancelsTheResume() {
        // The user resumed from the phone while the other app still had focus.
        FocusPolicy p = new FocusPolicy();
        p.onChange(LOSS_TRANSIENT, true);
        assertFalse(p.onGranted());
        assertFalse(p.holdOnPause());
        assertEquals(NONE, p.onChange(GAIN, true));
    }

    @Test public void duckLowersAndGainRestores() {
        FocusPolicy p = new FocusPolicy();
        assertEquals(DUCK, p.onChange(LOSS_TRANSIENT_CAN_DUCK, true));
        assertTrue(p.isDucked());
        assertFalse(p.holdOnPause()); // ducking never pauses
        assertEquals(UNDUCK, p.onChange(GAIN, true));
        assertFalse(p.isDucked());
    }

    @Test public void grantedAgainClearsTheDuck() {
        FocusPolicy p = new FocusPolicy();
        assertFalse(p.onGranted());
        p.onChange(LOSS_TRANSIENT_CAN_DUCK, true);
        assertTrue(p.onGranted());
        assertFalse(p.isDucked());
    }

    @Test public void lossWhileDuckedClearsTheDuck() {
        FocusPolicy p = new FocusPolicy();
        p.onChange(LOSS_TRANSIENT_CAN_DUCK, true);
        assertEquals(PAUSE, p.onChange(LOSS, true));
        assertFalse(p.isDucked());
    }

    @Test public void unknownChangesAreIgnored() {
        FocusPolicy p = new FocusPolicy();
        assertEquals(NONE, p.onChange(0, true));
        assertEquals(NONE, p.onChange(7, false));
    }

    @Test public void volumeFollowsDuckAndMute() {
        FocusPolicy p = new FocusPolicy();
        assertEquals(1f, p.volume(), 0f);
        p.onChange(LOSS_TRANSIENT_CAN_DUCK, true);
        assertEquals(FocusPolicy.DUCK_VOLUME, p.volume(), 0f);
        p.onChange(GAIN, true);
        assertEquals(1f, p.volume(), 0f);
    }

    @Test public void failedPauseMutesUntilFocusReturns() {
        // Soloist's API is down, so the pause could not be sent: go silent.
        FocusPolicy p = new FocusPolicy();
        assertEquals(PAUSE, p.onChange(LOSS, true));
        p.onPauseFailed();
        assertTrue(p.isMuted());
        assertEquals(0f, p.volume(), 0f);
        p.onGranted(); // played again on our own: audible again
        assertFalse(p.isMuted());
        assertEquals(1f, p.volume(), 0f);
    }

    @Test public void failedTransientPauseUnmutesAndResumesOnGain() {
        FocusPolicy p = new FocusPolicy();
        assertEquals(PAUSE, p.onChange(LOSS_TRANSIENT, true));
        p.onPauseFailed();
        assertEquals(RESUME, p.onChange(GAIN, true));
        assertFalse(p.isMuted());
    }
}
