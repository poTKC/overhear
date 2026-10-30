package com.potkc.overhear;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TopPullTest {
    private final TopPull pull = new TopPull(24, 40);

    @Test public void aPullFromTheEdgeFiresOnce() {
        assertTrue(pull.down(100, 5));
        assertFalse(pull.move(102, 30)); // not far enough yet
        assertTrue(pull.move(105, 50));
        assertFalse(pull.move(105, 90)); // the same touch
    }

    @Test public void aTouchBelowTheEdgeDoesNotFire() {
        assertFalse(pull.down(100, 30));
        assertFalse(pull.move(100, 200));
    }

    @Test public void aSidewaysMoveDoesNotFire() {
        pull.down(100, 0);
        assertFalse(pull.move(160, 50));
    }

    @Test public void anEndedTouchDoesNotFire() {
        pull.down(100, 0);
        pull.end();
        assertFalse(pull.move(100, 100));
    }

    @Test public void aNewTouchFiresAgain() {
        pull.down(100, 0);
        assertTrue(pull.move(100, 60));
        pull.down(100, 0);
        assertTrue(pull.move(100, 60));
    }
}
