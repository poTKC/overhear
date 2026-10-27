package com.potkc.overhear.spotify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.potkc.overhear.soloist.PlayerState;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class QueueTracksTest {
    private static final String PLAYLIST = "spotify:playlist:P";

    private PlayerState state;

    @Before public void setUp() throws Exception {
        state = new PlayerState();
        state.apply(new JSONObject().put("type", "playback_state").put("status", "playing")
                .put("item", item("a")).put("context", new JSONObject().put("uri", PLAYLIST)));
    }

    /** A track "spotify:track:<id>" titled "T<id>" by "X<id>" and "Y<id>", with three cover sizes. */
    private static JSONObject item(String id) throws Exception {
        JSONArray covers = new JSONArray()
                .put(new JSONObject().put("url", "https://i/" + id + "-large").put("size", "large"))
                .put(new JSONObject().put("url", "https://i/" + id + "-small").put("size", "small"))
                .put(new JSONObject().put("url", "https://i/" + id + "-default").put("size", "default"));
        JSONArray creators = new JSONArray().put(creator("X" + id)).put(creator("Y" + id));
        return new JSONObject().put("uri", "spotify:track:" + id).put("decorations", new JSONObject()
                .put("identity", new JSONObject().put("name", "T" + id))
                .put("creators", creators)
                .put("visual_identity", new JSONObject().put("cover", covers)));
    }

    private static JSONObject creator(String name) throws Exception {
        return new JSONObject().put("entity", new JSONObject().put("decorations",
                new JSONObject().put("identity", new JSONObject().put("name", name))));
    }

    /** Applies a queue_changed of "source=id" entries. */
    private void queue(String... sourceId) throws Exception {
        JSONArray a = new JSONArray();
        for (String e : sourceId) {
            String[] p = e.split("=");
            a.put(new JSONObject().put("source", p[0]).put("item", item(p[1])));
        }
        state.apply(new JSONObject().put("type", "queue_changed").put("upcoming", a));
    }

    private static List<String> uris(List<Item> items) {
        List<String> u = new ArrayList<String>();
        for (Item i : items) u.add(i.uri);
        return u;
    }

    @Test public void currentThenContextRows() throws Exception {
        queue("context=b", "queue=x", "context=c", "autoplay=y");
        assertEquals(Arrays.asList("spotify:track:a", "spotify:track:b", "spotify:track:c"),
                uris(QueueTracks.fromState(state, PLAYLIST)));
    }

    @Test public void stopsWhereTheContextWrapsAround() throws Exception {
        queue("context=b", "context=c", "context=a", "context=b");
        assertEquals(Arrays.asList("spotify:track:a", "spotify:track:b", "spotify:track:c"),
                uris(QueueTracks.fromState(state, PLAYLIST)));
    }

    @Test public void aQueuedCopyOfTheCurrentTrackDoesNotCutTheList() throws Exception {
        queue("queue=a", "context=b");
        assertEquals(Arrays.asList("spotify:track:a", "spotify:track:b"),
                uris(QueueTracks.fromState(state, PLAYLIST)));
    }

    @Test public void eachTrackPlaysInThePlaylist() throws Exception {
        queue("context=b");
        List<Item> items = QueueTracks.fromState(state, PLAYLIST);
        assertEquals(2, items.size());
        for (Item i : items) {
            assertEquals(Item.Kind.TRACK, i.kind);
            assertEquals(PLAYLIST, i.contextUri);
            assertNull(i.ownerId);
        }
        Item b = items.get(1);
        assertEquals("Tb", b.title);
        assertEquals("Xb, Yb", b.subtitle);
        assertEquals("https://i/b-small", b.coverUrl);
        Item a = items.get(0);
        assertEquals("Ta", a.title);
        assertEquals("Xa, Ya", a.subtitle);
        assertEquals("https://i/a-small", a.coverUrl);
    }

    @Test public void missingMetadataGivesAnEmptySubtitle() throws Exception {
        JSONArray a = new JSONArray().put(new JSONObject().put("source", "context")
                .put("item", new JSONObject().put("uri", "spotify:track:bare")));
        state.apply(new JSONObject().put("type", "queue_changed").put("upcoming", a));
        Item bare = QueueTracks.fromState(state, PLAYLIST).get(1);
        assertEquals("", bare.subtitle);
        assertNull(bare.title);
        assertNull(bare.coverUrl);
    }

    @Test public void notReadyUntilTheContextMatches() throws Exception {
        queue("context=b");
        assertNull(QueueTracks.fromState(state, "spotify:playlist:other"));
    }

    @Test public void notReadyWhileTheQueueIsStale() throws Exception {
        queue("context=b");
        state.apply(new JSONObject().put("type", "track_changed").put("item", item("b")));
        assertNull(QueueTracks.fromState(state, PLAYLIST));
        queue("context=c");
        assertEquals(Arrays.asList("spotify:track:b", "spotify:track:c"),
                uris(QueueTracks.fromState(state, PLAYLIST)));
    }
}
