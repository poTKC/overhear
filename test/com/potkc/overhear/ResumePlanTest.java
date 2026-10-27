package com.potkc.overhear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ResumePlanTest {
    private static final String T = "spotify:track:T";
    private static final String ALBUM = "spotify:album:A";

    @Test public void activeSoloistPlaysAsBefore() {
        assertEquals(ResumePlan.Kind.NONE, ResumePlan.resume(true, true, T, ALBUM, 5000, 200000).kind);
        assertEquals(ResumePlan.Kind.NONE, ResumePlan.resume(true, false, T, ALBUM, 5000, 200000).kind);
    }

    @Test public void loggedInResumesTheContextFromTheTrackAndPosition() {
        ResumePlan p = ResumePlan.resume(false, true, T, ALBUM, 65000, 200000);
        assertEquals(ResumePlan.Kind.WEB, p.kind);
        assertEquals(ALBUM, p.contextUri);
        assertEquals(T, p.trackUri);
        assertEquals(65000, p.positionMs);
        assertEquals(ALBUM, p.soloistUri); // the fallback, from the top
        for (String c : new String[] {"spotify:playlist:P", "spotify:user:u:collection"}) {
            assertEquals(c, ResumePlan.resume(false, true, T, c, 0, 0).contextUri);
        }
    }

    @Test public void aContextWithoutOffsetsPlaysTheTrackAlone() {
        for (String c : new String[] {null, "spotify:artist:R", "spotify:station:x", "spotify:track:T"}) {
            ResumePlan p = ResumePlan.resume(false, true, T, c, 1000, 200000);
            assertEquals(ResumePlan.Kind.WEB, p.kind);
            assertNull(c, p.contextUri);
            assertEquals(T, p.trackUri);
            assertEquals(1000, p.positionMs);
        }
        // Soloist plays any context from the top; with none, the track.
        assertEquals("spotify:artist:R", ResumePlan.resume(false, true, T, "spotify:artist:R", 0, 0).soloistUri);
        assertEquals(T, ResumePlan.resume(false, true, T, null, 0, 0).soloistUri);
    }

    @Test public void loggedOutAsksSoloist() {
        ResumePlan p = ResumePlan.resume(false, false, T, ALBUM, 65000, 200000);
        assertEquals(ResumePlan.Kind.SOLOIST, p.kind);
        assertEquals(ALBUM, p.soloistUri);
        assertEquals(T, ResumePlan.resume(false, false, T, null, 0, 0).soloistUri);
    }

    @Test public void noTrackPlaysTheContextFromTheTop() {
        ResumePlan p = ResumePlan.resume(false, true, null, ALBUM, 5000, 0);
        assertEquals(ResumePlan.Kind.WEB, p.kind);
        assertEquals(ALBUM, p.contextUri);
        assertNull(p.trackUri);
        assertEquals(0, p.positionMs);
        assertEquals(ResumePlan.Kind.SOLOIST, ResumePlan.resume(false, false, null, ALBUM, 0, 0).kind);
        assertEquals(ResumePlan.Kind.NONE, ResumePlan.resume(false, true, null, null, 0, 0).kind);
        // Nothing Spotify can play from either: as before.
        assertEquals(ResumePlan.Kind.NONE, ResumePlan.resume(false, true, null, "spotify:station:x", 0, 0).kind);
    }

    @Test public void thePositionStaysInsideTheTrack() {
        assertEquals(0, ResumePlan.resume(false, true, T, ALBUM, -5, 200000).positionMs);
        // At (or past) the end: from the start of the track rather than straight to the next.
        assertEquals(0, ResumePlan.resume(false, true, T, ALBUM, 199500, 200000).positionMs);
        assertEquals(0, ResumePlan.resume(false, true, T, ALBUM, 250000, 200000).positionMs);
        assertEquals(250000, ResumePlan.resume(false, true, T, ALBUM, 250000, 0).positionMs); // duration unknown
    }

    @Test public void queueEntriesOfTheContextPlayInIt() {
        ResumePlan p = ResumePlan.queueEntry(true, "spotify:track:Q", "context", ALBUM);
        assertEquals(ResumePlan.Kind.WEB, p.kind);
        assertEquals(ALBUM, p.contextUri);
        assertEquals("spotify:track:Q", p.trackUri);
        assertEquals(0, p.positionMs);
    }

    @Test public void otherQueueEntriesPlayAlone() {
        assertNull(ResumePlan.queueEntry(true, "spotify:track:Q", "queue", ALBUM).contextUri);
        assertNull(ResumePlan.queueEntry(true, "spotify:track:Q", "autoplay", ALBUM).contextUri);
        assertNull(ResumePlan.queueEntry(true, "spotify:track:Q", "context", "spotify:artist:R").contextUri);
        assertEquals("spotify:track:Q", ResumePlan.queueEntry(true, "spotify:track:Q", "queue", ALBUM).trackUri);
    }

    @Test public void queueEntriesNeedTheWebApi() {
        assertEquals(ResumePlan.Kind.NONE, ResumePlan.queueEntry(false, "spotify:track:Q", "context", ALBUM).kind);
        assertEquals(ResumePlan.Kind.NONE, ResumePlan.queueEntry(true, null, "context", ALBUM).kind);
    }
}
