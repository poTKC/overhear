package com.potkc.overhear.spotify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class LikedTracksTest {
    private static final String T = "spotify:track:T";

    @Test public void unknownUntilPut() {
        LikedTracks l = new LikedTracks();
        l.login(1);
        assertNull(l.get(T));
        l.put(T, true);
        assertEquals(Boolean.TRUE, l.get(T));
        l.put(T, false);
        assertEquals(Boolean.FALSE, l.get(T));
    }

    @Test public void eachTrackIsCheckedOncePerLogin() {
        LikedTracks l = new LikedTracks();
        l.login(1);
        assertTrue(l.shouldCheck(T));
        assertFalse(l.shouldCheck(T)); // asked already, answer or not
        assertTrue(l.shouldCheck("spotify:track:U"));
        l.put("spotify:track:V", true);
        assertFalse(l.shouldCheck("spotify:track:V")); // known
    }

    @Test public void aFailedCheckMayBeAskedAgain() {
        LikedTracks l = new LikedTracks();
        l.login(1);
        assertTrue(l.shouldCheck(T));
        l.checkFailed(T);
        assertTrue(l.shouldCheck(T));
    }

    @Test public void anotherLoginForgetsEverything() {
        LikedTracks l = new LikedTracks();
        l.login(1);
        l.put(T, true);
        assertTrue(l.shouldCheck("spotify:track:U"));
        l.login(1);
        assertEquals(Boolean.TRUE, l.get(T));
        l.login(2);
        assertNull(l.get(T));
        assertTrue(l.shouldCheck("spotify:track:U"));
    }

    @Test public void onlyTracksCanBeLiked() {
        assertTrue(LikedTracks.likeable(T));
        assertFalse(LikedTracks.likeable("spotify:episode:E"));
        assertFalse(LikedTracks.likeable("spotify:local:a:b:c:1"));
        assertFalse(LikedTracks.likeable(null));
        LikedTracks l = new LikedTracks();
        l.login(1);
        assertFalse(l.shouldCheck("spotify:episode:E"));
    }

    @Test public void aLikedPageMarksItsTracks() {
        LikedTracks l = new LikedTracks();
        l.login(1);
        l.putAll(Arrays.asList(new Item(Item.Kind.TRACK, T, "t", "", null, null, null),
                new Item(Item.Kind.ALBUM, "spotify:album:A", "a", "", null, null, null)), true);
        assertEquals(Boolean.TRUE, l.get(T));
        assertNull(l.get("spotify:album:A"));
    }
}
