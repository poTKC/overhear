package com.potkc.overhear;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayPlanTest {
    private static final String CTX = "spotify:album:A";

    @Test public void shuffledAlwaysCallsEvenIfSoloistSaysOn() {
        // Soloist's flag may be stale: on while Spotify's is off.
        PlayPlan p = PlayPlan.of(CTX, null, Boolean.TRUE, Boolean.TRUE);
        assertTrue(p.callShuffle);
        assertFalse(p.fromFirst);
        assertTrue(p.checks);
        assertTrue(PlayPlan.of(CTX, null, Boolean.TRUE, null).callShuffle);
    }

    @Test public void inOrderPlaysFromTheFirstTrackAndSkipsAKnownOff() {
        PlayPlan p = PlayPlan.of(CTX, null, Boolean.FALSE, Boolean.FALSE);
        assertFalse(p.callShuffle);
        assertTrue(p.fromFirst);
        assertTrue(PlayPlan.of(CTX, null, Boolean.FALSE, Boolean.TRUE).callShuffle);
        assertTrue(PlayPlan.of(CTX, null, Boolean.FALSE, null).callShuffle);
    }

    @Test public void aTrackOrTheArtistLeavesShuffleAlone() {
        PlayPlan p = PlayPlan.of(CTX, "spotify:track:T", null, Boolean.TRUE);
        assertFalse(p.callShuffle);
        assertFalse(p.fromFirst);
        assertFalse(p.checks);
        assertFalse(p.fixNow(CTX, 1, 1, 100, 0, false, true, CTX, false));
        assertFalse(PlayPlan.of("spotify:artist:R", null, null, Boolean.FALSE).checks);
    }

    @Test public void fixesAShuffleThatDidNotTake() {
        PlayPlan p = PlayPlan.of(CTX, null, Boolean.TRUE, Boolean.FALSE);
        assertTrue(p.fixNow(CTX, 3, 3, 100, 50, false, true, CTX, false));
        assertFalse(p.fixNow(CTX, 3, 3, 100, 50, false, true, CTX, true)); // it has it
        assertFalse(p.fixNow(CTX, 3, 3, 100, 50, false, true, "spotify:album:B", false));
        assertFalse(p.fixNow(CTX, 3, 3, 100, 50, false, false, CTX, false));
    }

    @Test public void aLaterPlayWins() {
        // ▶ then, within the check delay, ⇄ on the same context: ▶'s check must not turn shuffle off.
        PlayPlan inOrder = PlayPlan.of(CTX, null, Boolean.FALSE, Boolean.FALSE);
        assertFalse(inOrder.fixNow(CTX, 1, 2, 100, 50, false, true, CTX, true));
    }

    @Test public void aControlOrAJumpSinceThePlayWins() {
        // ⇄, then the user turns shuffle off in the player: the check leaves it off.
        PlayPlan p = PlayPlan.of(CTX, null, Boolean.TRUE, Boolean.FALSE);
        assertFalse(p.fixNow(CTX, 1, 1, 100, 150, false, true, CTX, false));
        // A queue jump stepping skip_next.
        assertFalse(p.fixNow(CTX, 1, 1, 100, 50, true, true, CTX, false));
    }
}
