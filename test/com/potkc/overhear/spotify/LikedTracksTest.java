package com.potkc.overhear.spotify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class LikedTracksTest {
    private static final String T = "spotify:track:T";

    private static LikedTracks liked() {
        LikedTracks l = new LikedTracks();
        l.login(1);
        return l;
    }

    /** A waiter that notes its name in log when run. */
    private static Runnable note(final List<String> log, final String name) {
        return new Runnable() {
            @Override public void run() {
                log.add(name);
            }
        };
    }

    private static void runAll(List<Runnable> rs) {
        for (Runnable r : rs) r.run();
    }

    @Test public void unknownUntilPut() {
        LikedTracks l = liked();
        assertNull(l.get(T));
        l.put(T, true);
        assertEquals(Boolean.TRUE, l.get(T));
        l.put(T, false);
        assertEquals(Boolean.FALSE, l.get(T));
    }

    @Test public void oneCheckAtATimeAndEveryAskerHearsTheAnswer() {
        LikedTracks l = liked();
        List<String> log = new ArrayList<String>();
        assertTrue(l.ask(T, 0, note(log, "player")));
        assertTrue(l.busy(T));
        assertFalse(l.ask(T, 0, note(log, "library")));
        runAll(l.answered(T, true));
        assertEquals(Arrays.asList("player", "library"), log);
        assertEquals(Boolean.TRUE, l.get(T));
        assertFalse(l.busy(T));
        assertFalse(l.ask(T, 0, note(log, "late"))); // known: nothing to wait for
        assertTrue(l.answered(T, false).isEmpty());
    }

    @Test public void aFailureTellsTheWaitersAndBacksOff() {
        LikedTracks l = liked();
        List<String> log = new ArrayList<String>();
        assertTrue(l.ask(T, 1000, note(log, "a")));
        assertFalse(l.ask(T, 1000, null));
        runAll(l.failed(T, 2000));
        assertEquals(Arrays.asList("a"), log);
        assertNull(l.get(T));
        assertFalse(l.busy(T));
        assertFalse(l.ask(T, 2000 + LikedTracks.RETRY_MS - 1, note(log, "b")));
        assertTrue(l.answered(T, true).isEmpty()); // b was not kept: no check ran for it
        l.put(T, false);
        LikedTracks m = liked();
        m.failed(T, 2000);
        assertTrue(m.ask(T, 2000 + LikedTracks.RETRY_MS, null));
    }

    @Test public void oneChangeAtATime() {
        LikedTracks l = liked();
        assertTrue(l.startChange(T));
        assertTrue(l.busy(T));
        assertFalse(l.startChange(T));
        l.changeEnded(T);
        assertFalse(l.busy(T));
        assertTrue(l.startChange(T));
    }

    @Test public void anotherLoginForgetsEverything() {
        LikedTracks l = liked();
        List<String> log = new ArrayList<String>();
        l.put(T, true);
        assertTrue(l.ask("spotify:track:U", 0, note(log, "u")));
        l.startChange("spotify:track:V");
        l.login(1);
        assertEquals(Boolean.TRUE, l.get(T));
        l.login(2);
        assertNull(l.get(T));
        assertFalse(l.busy("spotify:track:U"));
        assertFalse(l.busy("spotify:track:V"));
        assertTrue(l.answered("spotify:track:U", true).isEmpty()); // the old login's waiters are gone
        assertTrue(log.isEmpty());
    }

    @Test public void onlyTracksCanBeLiked() {
        assertTrue(LikedTracks.likeable(T));
        assertFalse(LikedTracks.likeable("spotify:episode:E"));
        assertFalse(LikedTracks.likeable("spotify:local:a:b:c:1"));
        assertFalse(LikedTracks.likeable("spotify:track:"));
        assertFalse(LikedTracks.likeable(null));
        assertFalse(liked().ask("spotify:episode:E", 0, null));
    }

    @Test public void aLikedPageMarksItsTracks() {
        LikedTracks l = liked();
        l.putAll(Arrays.asList(new Item(Item.Kind.TRACK, T, "t", "", null, null, null),
                new Item(Item.Kind.ALBUM, "spotify:album:A", "a", "", null, null, null)), true);
        assertEquals(Boolean.TRUE, l.get(T));
        assertNull(l.get("spotify:album:A"));
    }
}
