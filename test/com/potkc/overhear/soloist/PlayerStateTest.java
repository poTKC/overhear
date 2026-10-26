package com.potkc.overhear.soloist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

/** Fixtures in test/fixtures were captured from Soloist 1.3.10 on the device. */
public class PlayerStateTest {
    public static JSONObject fixture(String name) {
        InputStream in = PlayerStateTest.class.getResourceAsStream("/" + name + ".json");
        if (in == null) throw new AssertionError("missing fixture " + name);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            return new JSONObject(new String(b.toByteArray(), "UTF-8"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    final PlayerState s = new PlayerState();

    @Test public void initialState() {
        assertFalse(s.isLoggedIn());
        assertEquals(PlayerState.Status.IDLE, s.getStatus());
        assertNull(s.getTitle());
        assertEquals(Collections.<String>emptyList(), s.getArtists());
        assertEquals(-1, s.getVolume());
        assertEquals(PlayerState.Repeat.OFF, s.getRepeat());
        assertTrue(s.getQueue().isEmpty());
        assertEquals(0, s.positionAt(123456));
    }

    @Test public void authState() {
        assertTrue(s.apply(fixture("auth_state")));
        assertTrue(s.isLoggedIn());
        assertTrue(s.isActive());
        assertEquals("ST15i", s.getDeviceName());
    }

    @Test public void playbackState() {
        assertTrue(s.apply(fixture("playback_state")));
        assertEquals(PlayerState.Status.PLAYING, s.getStatus());
        assertEquals("spotify:track:4IdtNJqm7zVBwvN5jIUOYo", s.getTrackUri());
        assertEquals("Next Chapter", s.getTitle());
        assertEquals(Arrays.asList("Hearts2Hearts"), s.getArtists());
        assertEquals("Next Chapter", s.getAlbum());
        assertEquals("https://i.scdn.co/image/ab67616d0000b27300c98121de90e0638935d97b", s.getCoverUrl());
        assertEquals(169000, s.getDurationMs());
        assertEquals(123871, s.getPositionMs());
        assertEquals(1793008858254L, s.getPositionTimestampMs());
        assertEquals(1.0, s.getSpeed(), 0);
        assertEquals(70, s.getVolume());
        assertTrue(s.isActive());
        assertFalse(s.isShuffle());
        assertEquals(PlayerState.Repeat.OFF, s.getRepeat());
        assertEquals("spotify:album:5JnGvbE42HzaqM6DT7u1SN", s.getContextUri());
        assertEquals("WhyKiiiKiii", s.getContextName());
    }

    @Test public void pausedPlaybackState() {
        s.apply(fixture("playback_state_paused"));
        assertEquals(PlayerState.Status.PAUSED, s.getStatus());
        assertEquals(0.0, s.getSpeed(), 0);
        long p = s.getPositionMs();
        assertEquals(p, s.positionAt(s.getPositionTimestampMs() + 60000));
    }

    @Test public void availableActionsGateShuffleAndRepeat() {
        // Never reported: allowed (older Soloist builds, or before the first playback_state).
        assertTrue(s.canShuffle());
        assertTrue(s.canRepeat());
        // Captured on the device: an album where Soloist lists "shuffle" and "set_repeat".
        s.apply(fixture("playback_state_shuffle_allowed"));
        assertTrue(s.canShuffle());
        assertTrue(s.canRepeat());
        // Captured on the device: a context without them (Soloist ignored set_shuffle there).
        s.apply(fixture("playback_state_restricted"));
        assertFalse(s.canShuffle());
        assertFalse(s.canRepeat());
    }

    @Test public void availableActionsSurvivePartialEventsAndCopies() {
        s.apply(fixture("playback_state_restricted"));
        s.apply(fixture("playback_changed"));
        s.apply(fixture("position_sync"));
        s.apply(fixture("options_changed_context"));
        assertFalse(s.canShuffle());
        PlayerState c = s.copy();
        assertFalse(c.canShuffle());
        assertFalse(c.canRepeat());
        // The next full playback_state replaces the list.
        s.apply(fixture("playback_state_shuffle_allowed"));
        assertTrue(s.canShuffle());
        assertFalse(c.canShuffle());
    }

    @Test public void aPlaybackStateWithoutTheFieldKeepsTheLastList() {
        s.apply(fixture("playback_state_restricted"));
        JSONObject e = fixture("playback_state_shuffle_allowed");
        e.remove("available_actions");
        s.apply(e);
        assertFalse(s.canShuffle());
    }

    @Test public void trackChangedWithSeveralArtists() {
        s.apply(fixture("playback_state"));
        assertTrue(s.apply(fixture("track_changed")));
        assertEquals("BAD HABITS TONITE", s.getTitle());
        assertEquals(Arrays.asList("A2O", "A2O MAY"), s.getArtists());
        assertEquals("BAD HABITS TONITE", s.getAlbum());
        assertEquals("https://i.scdn.co/image/ab67616d0000b2732caa8b78b32389a29806ca4a", s.getCoverUrl());
        // The old track's position must not leak into the new one.
        assertEquals(0, s.positionAt(s.getPositionTimestampMs() + 5000));
        assertEquals(PlayerState.Status.PLAYING, s.getStatus()); // until playback_changed
    }

    @Test public void playbackChanged() {
        s.apply(fixture("playback_state"));
        assertTrue(s.apply(fixture("playback_changed")));
        assertEquals(PlayerState.Status.PAUSED, s.getStatus());
        assertEquals("Next Chapter", s.getTitle());
    }

    @Test public void allStatuses() throws Exception {
        String[] names = {"idle", "playing", "paused", "buffering", "something_new"};
        PlayerState.Status[] want = {PlayerState.Status.IDLE, PlayerState.Status.PLAYING,
                PlayerState.Status.PAUSED, PlayerState.Status.BUFFERING, PlayerState.Status.IDLE};
        for (int i = 0; i < names.length; i++) {
            s.apply(new JSONObject("{\"type\":\"playback_changed\",\"status\":\"" + names[i] + "\"}"));
            assertEquals(want[i], s.getStatus());
        }
    }

    @Test public void volumeChanged() {
        s.apply(fixture("playback_state"));
        assertTrue(s.apply(fixture("volume_changed")));
        assertEquals(60, s.getVolume());
    }

    @Test public void optionsChanged() {
        assertTrue(s.apply(fixture("options_changed_context")));
        assertTrue(s.isShuffle());
        assertEquals(PlayerState.Repeat.CONTEXT, s.getRepeat());
        s.apply(fixture("options_changed_track"));
        assertEquals(PlayerState.Repeat.TRACK, s.getRepeat());
        assertEquals(PlayerState.Repeat.OFF, PlayerState.Repeat.parse("off"));
        assertEquals(PlayerState.Repeat.OFF, PlayerState.Repeat.parse(null));
        assertEquals("context", PlayerState.Repeat.CONTEXT.wireName());
    }

    @Test public void contextChangedWithoutName() {
        s.apply(fixture("playback_state"));
        assertTrue(s.apply(fixture("context_changed")));
        assertEquals("spotify:album:1UP8wDVpZ3VCDjzbLsZ5AP", s.getContextUri());
        assertNull(s.getContextName());
    }

    @Test public void deviceChanged() throws Exception {
        s.apply(fixture("auth_state"));
        assertTrue(s.apply(new JSONObject(
                "{\"type\":\"device_changed\",\"is_active\":false,\"device_name\":\"Other\"}")));
        assertFalse(s.isActive());
        assertEquals("Other", s.getDeviceName());
    }

    @Test public void positionSyncAndInterpolation() {
        s.apply(fixture("playback_state"));
        assertTrue(s.apply(fixture("position_sync")));
        assertEquals(30488, s.getPositionMs());
        long ts = s.getPositionTimestampMs();
        assertEquals(1793008861746L, ts);
        assertEquals(30488, s.positionAt(ts));
        assertEquals(31488, s.positionAt(ts + 1000));
        assertEquals(0, s.positionAt(ts - 100000));
        assertEquals(169000, s.positionAt(ts + 10000000)); // clamped to the duration

        s.apply(fixture("position_sync_paused"));
        assertEquals(31787, s.positionAt(s.getPositionTimestampMs() + 5000));
    }

    @Test public void playbackChangedWithoutStatusKeepsIt() throws Exception {
        s.apply(fixture("playback_state"));
        assertTrue(s.apply(new JSONObject("{\"type\":\"playback_changed\"}")));
        assertEquals(PlayerState.Status.PLAYING, s.getStatus());
    }

    @Test public void extrapolatesOnlyWhilePlaying() throws Exception {
        s.apply(fixture("playback_state"));
        long ts = s.getPositionTimestampMs(), p = s.getPositionMs();
        assertEquals(p + 1000, s.positionAt(ts + 1000));
        // playback_changed can arrive before the position_sync with speed 0.
        s.apply(fixture("playback_changed")); // paused
        assertEquals(p, s.positionAt(ts + 1000));
        s.apply(new JSONObject("{\"type\":\"playback_changed\",\"status\":\"idle\"}"));
        assertEquals(p, s.positionAt(ts + 1000));
        s.apply(new JSONObject("{\"type\":\"playback_changed\",\"status\":\"buffering\"}"));
        assertEquals(p + 1000, s.positionAt(ts + 1000)); // speed decides while buffering
    }

    @Test public void fractionalSpeed() throws Exception {
        s.apply(new JSONObject("{\"type\":\"playback_changed\",\"status\":\"playing\"}"));
        s.apply(new JSONObject("{\"type\":\"position_sync\",\"position\":"
                + "{\"position_ms\":1000,\"timestamp_ms\":5000,\"speed\":1.5}}"));
        assertEquals(2500, s.positionAt(6000));
        // Unknown duration: only the lower bound applies.
        assertEquals(1001500, s.positionAt(672000));
    }

    @Test public void queue() {
        assertTrue(s.apply(fixture("queue_changed")));
        List<PlayerState.QueueItem> q = s.getQueue();
        assertEquals(10, q.size());
        PlayerState.QueueItem first = q.get(0);
        assertEquals("IYKYK", first.title);
        assertEquals(Arrays.asList("XG"), first.artists);
        assertEquals("spotify:track:0Gl9pXfGTU8eAnEbcka6AV", first.uri);
        assertEquals("6bbcc1f1e0d78d1c", first.uid);
        assertEquals("autoplay", first.source);
        assertEquals("https://i.scdn.co/image/ab67616d0000b273fcda1903d978d02fd3f5334b", first.coverUrl);
        assertEquals("https://i.scdn.co/image/ab67616d00004851fcda1903d978d02fd3f5334b", first.smallCoverUrl);
        assertEquals("https://i.scdn.co/image/ab67616d00001e02fcda1903d978d02fd3f5334b", first.mediumCoverUrl);
        try {
            q.clear();
            fail("queue must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test public void coverPrefersLargeThenDefaultThenAny() throws Exception {
        String item = "{\"type\":\"track_changed\",\"item\":{\"decorations\":{\"visual_identity\":{\"cover\":%s}}}}";
        s.apply(new JSONObject(String.format(item, "[{\"url\":\"s\",\"size\":\"small\"},"
                + "{\"url\":\"d\",\"size\":\"default\"},{\"url\":\"x\",\"size\":\"xlarge\"}]")));
        assertEquals("d", s.getCoverUrl());
        s.apply(new JSONObject(String.format(item, "[{\"url\":\"s\",\"size\":\"small\"}]")));
        assertEquals("s", s.getCoverUrl());
        s.apply(new JSONObject(String.format(item, "[]")));
        assertNull(s.getCoverUrl());
    }

    @Test public void mediumCoverPrefersDefaultThenLarge() throws Exception {
        s.apply(fixture("track_changed"));
        assertEquals("https://i.scdn.co/image/ab67616d00001e022caa8b78b32389a29806ca4a", s.getMediumCoverUrl());
        String item = "{\"type\":\"track_changed\",\"item\":{\"decorations\":{\"visual_identity\":{\"cover\":%s}}}}";
        s.apply(new JSONObject(String.format(item, "[{\"url\":\"s\",\"size\":\"small\"},"
                + "{\"url\":\"l\",\"size\":\"large\"}]")));
        assertEquals("l", s.getMediumCoverUrl());
        s.apply(new JSONObject(String.format(item, "[{\"url\":\"s\",\"size\":\"small\"}]")));
        assertEquals("s", s.getMediumCoverUrl());
        s.apply(new JSONObject(String.format(item, "[]")));
        assertNull(s.getMediumCoverUrl());
    }

    @Test public void smallCoverPrefersSmallThenDefault() throws Exception {
        String item = "{\"type\":\"queue_changed\",\"upcoming\":[{\"item\":{\"decorations\":"
                + "{\"visual_identity\":{\"cover\":%s}}}}]}";
        s.apply(new JSONObject(String.format(item, "[{\"url\":\"l\",\"size\":\"large\"},"
                + "{\"url\":\"d\",\"size\":\"default\"}]")));
        assertEquals("d", s.getQueue().get(0).smallCoverUrl);
        s.apply(new JSONObject(String.format(item, "[{\"url\":\"l\",\"size\":\"large\"}]")));
        assertEquals("l", s.getQueue().get(0).smallCoverUrl);
    }

    @Test public void missingMetadataIsTolerated() throws Exception {
        s.apply(fixture("playback_state"));
        assertTrue(s.apply(new JSONObject("{\"type\":\"track_changed\",\"item\":{\"uri\":\"spotify:track:x\"}}")));
        assertEquals("spotify:track:x", s.getTrackUri());
        assertNull(s.getTitle());
        assertNull(s.getAlbum());
        assertNull(s.getCoverUrl());
        assertTrue(s.getArtists().isEmpty());
        assertEquals(0, s.getDurationMs());

        s.apply(new JSONObject("{\"type\":\"playback_state\",\"status\":\"idle\",\"item\":null}"));
        assertEquals(PlayerState.Status.IDLE, s.getStatus());
        assertNull(s.getTrackUri());
        assertNull(s.getTitle());
    }

    @Test public void nonStateEventsAreIgnored() {
        s.apply(fixture("playback_state"));
        PlayerState before = s.copy();
        assertFalse(s.apply(fixture("command_result")));
        assertFalse(s.apply(fixture("error")));
        assertEquals(before.getTitle(), s.getTitle());
        assertEquals(before.getVolume(), s.getVolume());
    }

    @Test public void copyIsIndependent() {
        s.apply(fixture("playback_state"));
        s.apply(fixture("queue_changed"));
        PlayerState c = s.copy();
        s.apply(fixture("track_changed"));
        s.apply(fixture("volume_changed"));
        assertEquals("Next Chapter", c.getTitle());
        assertEquals(70, c.getVolume());
        assertEquals(10, c.getQueue().size());
        assertSame(c.getQueue(), c.copy().getQueue()); // immutable lists are shared
    }
}
