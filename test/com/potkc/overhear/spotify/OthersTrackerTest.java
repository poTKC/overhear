package com.potkc.overhear.spotify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.potkc.overhear.soloist.PlayerState;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class OthersTrackerTest {
    private static final String PLAYLIST = "spotify:playlist:P";
    private static final String OTHER = "spotify:album:O";
    private static final long T0 = 1000000;

    private PlayerState state;
    private OthersTracker tracker;

    @Before public void setUp() throws Exception {
        // Before opening: track x of another context plays.
        state = new PlayerState();
        state.apply(new JSONObject().put("type", "playback_state").put("status", "playing")
                .put("item", item("x")).put("context", context(OTHER)));
        tracker = new OthersTracker(PLAYLIST);
        tracker.onOpen(state.copy());
    }

    private static JSONObject item(String id) {
        return new JSONObject().put("uri", id.isEmpty() ? "" : "spotify:track:" + id);
    }

    private static JSONObject context(String uri) {
        return new JSONObject().put("uri", uri);
    }

    private void apply(JSONObject e) {
        state.apply(e);
        tracker.onState(state.copy());
    }

    private void contextChanged(String uri) {
        apply(new JSONObject().put("type", "context_changed").put("context", context(uri)));
    }

    private void trackChanged(String id) {
        apply(new JSONObject().put("type", "track_changed").put("item", item(id)));
    }

    /** A playback_state as Soloist sends it on a restart: no track_changed. */
    private void playbackState(String id, String contextUri) {
        apply(new JSONObject().put("type", "playback_state").put("status", "playing")
                .put("item", item(id)).put("context", context(contextUri)));
    }

    private void position(long ms, long timestamp) {
        apply(new JSONObject().put("type", "position_sync").put("position", new JSONObject()
                .put("position_ms", ms).put("timestamp_ms", timestamp).put("speed", 1)));
    }

    private void queue(String... ids) {
        JSONArray a = new JSONArray();
        for (String id : ids) a.put(new JSONObject().put("source", "context").put("item", item(id)));
        apply(new JSONObject().put("type", "queue_changed").put("upcoming", a));
    }

    private static List<String> uris(List<Item> items) {
        List<String> u = new ArrayList<String>();
        for (Item i : items) u.add(i.uri.substring("spotify:track:".length()));
        return u;
    }

    @Test public void theOldTrackUnderTheNewContextIsNotTheFirst() {
        contextChanged(PLAYLIST);
        playbackState("", PLAYLIST);
        assertNull(tracker.firstTrackUri());
        assertFalse(tracker.takeQueueRequest(true));
        trackChanged("a");
        assertEquals("spotify:track:a", tracker.firstTrackUri());
        assertEquals(OthersTracker.Status.STARTING, tracker.status());
    }

    @Test public void asksForTheQueueOncePerConnection() {
        contextChanged(PLAYLIST);
        trackChanged("a");
        assertFalse(tracker.takeQueueRequest(false));
        assertTrue(tracker.takeQueueRequest(true));
        assertFalse(tracker.takeQueueRequest(true));
        tracker.onConnected(false, state.copy());
        tracker.onConnected(true, state.copy());
        assertTrue(tracker.takeQueueRequest(true));
    }

    @Test public void keepsTheLongestList() {
        contextChanged(PLAYLIST);
        trackChanged("a");
        queue("b");
        assertEquals(Arrays.asList("a", "b"), uris(tracker.items()));
        queue("b", "c", "d");
        assertEquals(Arrays.asList("a", "b", "c", "d"), uris(tracker.items()));
        queue("b", "c");
        assertEquals(Arrays.asList("a", "b", "c", "d"), uris(tracker.items()));
        assertEquals(OthersTracker.Status.READY, tracker.status());
    }

    @Test public void anotherTrackLeavesTheListAsIs() {
        contextChanged(PLAYLIST);
        trackChanged("a");
        queue("b", "c");
        tracker.onPlayReturned(T0);
        trackChanged("c");
        queue("a", "b");
        assertEquals(Arrays.asList("a", "b", "c"), uris(tracker.items()));
        // A new connection does not ask for a rotated queue.
        tracker.onConnected(true, state.copy());
        assertFalse(tracker.takeQueueRequest(true));
    }

    private void shuffle(boolean on) {
        apply(new JSONObject().put("type", "options_changed")
                .put("options", new JSONObject().put("shuffle", on)));
    }

    @Test public void aShuffleChangeOnTheFirstTrackTakesTheNewOrder() {
        // Played shuffled although asked in order; the shuffle is then turned off.
        shuffle(true);
        contextChanged(PLAYLIST);
        trackChanged("a");
        assertTrue(tracker.takeQueueRequest(true));
        queue("c", "b", "d");
        shuffle(false);
        assertTrue(tracker.takeQueueRequest(true));
        queue("b"); // the regular short queue does not replace it
        assertEquals(Arrays.asList("a", "c", "b", "d"), uris(tracker.items()));
        int v = tracker.version();
        queue("b", "c", "d");
        assertEquals(Arrays.asList("a", "b", "c", "d"), uris(tracker.items()));
        assertTrue(tracker.version() != v);
        queue("d", "c", "b"); // once taken, only a longer list replaces it
        assertEquals(Arrays.asList("a", "b", "c", "d"), uris(tracker.items()));
    }

    @Test public void aShuffleChangeOnAnotherTrackLeavesTheList() {
        contextChanged(PLAYLIST);
        trackChanged("a");
        assertTrue(tracker.takeQueueRequest(true));
        queue("b", "c");
        trackChanged("b");
        shuffle(true);
        assertFalse(tracker.takeQueueRequest(true));
        assertEquals(Arrays.asList("a", "b", "c"), uris(tracker.items()));
    }

    @Test public void shuffleAloneLeavesTheVersion() {
        int v = tracker.version();
        apply(new JSONObject().put("type", "options_changed")
                .put("options", new JSONObject().put("shuffle", true)));
        position(0, T0);
        assertEquals(v, tracker.version());
    }

    @Test public void adoptsARestartedFirstTrackAfterTheDelay() {
        // The playlist's first track a already played: the restart sends no track_changed.
        playbackState("a", PLAYLIST);
        tracker = new OthersTracker(PLAYLIST);
        tracker.onOpen(state.copy());
        position(1000, T0);
        tracker.onPlayReturned(T0);
        tracker.onTick(state.copy(), T0 + OthersTracker.ADOPT_AFTER_MS - 1);
        assertNull(tracker.firstTrackUri());
        tracker.onTick(state.copy(), T0 + OthersTracker.ADOPT_AFTER_MS);
        assertEquals("spotify:track:a", tracker.firstTrackUri());
        queue("b");
        assertEquals(Arrays.asList("a", "b"), uris(tracker.items()));
    }

    @Test public void doesNotAdoptATrackWellUnderway() {
        playbackState("a", PLAYLIST);
        tracker = new OthersTracker(PLAYLIST);
        tracker.onOpen(state.copy());
        position(10000, T0);
        tracker.onPlayReturned(T0);
        tracker.onTick(state.copy(), T0 + OthersTracker.ADOPT_AFTER_MS);
        assertNull(tracker.firstTrackUri());
    }

    @Test public void doesNotAdoptBeforeThePlayReturned() {
        playbackState("a", PLAYLIST);
        tracker = new OthersTracker(PLAYLIST);
        tracker.onOpen(state.copy());
        position(0, T0);
        tracker.onTick(state.copy(), T0 + OthersTracker.GIVE_UP_AFTER_MS);
        assertNull(tracker.firstTrackUri());
        assertEquals(OthersTracker.Status.STARTING, tracker.status());
    }

    @Test public void failsWithoutAListAfterTheTimeout() {
        tracker.onPlayReturned(T0);
        tracker.onTick(state.copy(), T0 + OthersTracker.GIVE_UP_AFTER_MS - 1);
        assertEquals(OthersTracker.Status.STARTING, tracker.status());
        int v = tracker.version();
        tracker.onTick(state.copy(), T0 + OthersTracker.GIVE_UP_AFTER_MS);
        assertEquals(OthersTracker.Status.FAILED, tracker.status());
        assertTrue(tracker.version() != v);
    }

    @Test public void doesNotFailInTheTickThatAdopts() {
        // Only the give-up tick runs (the earlier one came before the restart),
        // and the queue is stale, so the list waits for the queue request.
        trackChanged("a");
        playbackState("a", PLAYLIST);
        tracker = new OthersTracker(PLAYLIST);
        tracker.onOpen(state.copy());
        tracker.onPlayReturned(T0);
        position(1000, T0 + OthersTracker.GIVE_UP_AFTER_MS - 1000);
        tracker.onTick(state.copy(), T0 + OthersTracker.GIVE_UP_AFTER_MS);
        assertEquals("spotify:track:a", tracker.firstTrackUri());
        assertEquals(OthersTracker.Status.STARTING, tracker.status());
        assertTrue(tracker.takeQueueRequest(true));
        queue("b");
        assertEquals(OthersTracker.Status.READY, tracker.status());
    }

    @Test public void failsLaterIfTheAdoptedTracksListNeverComes() {
        trackChanged("a");
        playbackState("a", PLAYLIST);
        tracker = new OthersTracker(PLAYLIST);
        tracker.onOpen(state.copy());
        tracker.onPlayReturned(T0);
        position(1000, T0 + OthersTracker.GIVE_UP_AFTER_MS - 1000);
        tracker.onTick(state.copy(), T0 + OthersTracker.GIVE_UP_AFTER_MS);
        assertEquals(OthersTracker.Status.STARTING, tracker.status());
        assertTrue(tracker.takeQueueRequest(true));
        int v = tracker.version();
        tracker.onTick(state.copy(), T0 + OthersTracker.LAST_CHECK_AFTER_MS);
        assertEquals(OthersTracker.Status.FAILED, tracker.status());
        assertTrue(tracker.version() != v);
    }

    @Test public void failsWhenTheContextLeavesThePlaylistFirst() {
        tracker.onPlayReturned(T0);
        contextChanged(PLAYLIST);
        assertEquals(OthersTracker.Status.STARTING, tracker.status());
        contextChanged(OTHER);
        assertEquals(OthersTracker.Status.FAILED, tracker.status());
    }

    @Test public void aListKeepsItsStatusButNotesTheContextLeft() {
        contextChanged(PLAYLIST);
        trackChanged("a");
        queue("b");
        tracker.onPlayReturned(T0);
        assertFalse(tracker.hasLeft());
        int v = tracker.version();
        contextChanged(OTHER);
        assertTrue(tracker.hasLeft());
        assertTrue(tracker.version() != v);
        assertEquals(OthersTracker.Status.READY, tracker.status());
        contextChanged(PLAYLIST); // played again (e.g. from the phone): still left
        assertTrue(tracker.hasLeft());
    }

    @Test public void theOldContextBeforeTheSwitchIsNotAFailure() {
        tracker.onPlayReturned(T0);
        trackChanged("y"); // still the old context
        assertEquals(OthersTracker.Status.STARTING, tracker.status());
    }

    @Test public void aNewConnectionResetsTheBaseline() {
        // A new connection counts track changes from 0 again.
        contextChanged(PLAYLIST);
        PlayerState fresh = new PlayerState();
        tracker.onConnected(true, fresh);
        state = fresh;
        playbackState("a", PLAYLIST);
        assertEquals("spotify:track:a", tracker.firstTrackUri());
    }
}
