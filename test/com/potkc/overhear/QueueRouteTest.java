package com.potkc.overhear;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.potkc.overhear.soloist.PlayerState;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class QueueRouteTest {
    private static final String ALBUM = "spotify:album:X";
    private static final String CUR = "spotify:track:cur";

    /** Builds Soloist's upcoming queue from "source=uri" pairs. */
    private static List<PlayerState.QueueItem> queue(String... sourceUri) throws Exception {
        JSONArray a = new JSONArray();
        for (String e : sourceUri) {
            int eq = e.indexOf('=');
            a.put(new JSONObject().put("source", e.substring(0, eq))
                    .put("item", new JSONObject().put("uri", e.substring(eq + 1))));
        }
        PlayerState s = new PlayerState();
        s.apply(new JSONObject().put("type", "queue_changed").put("upcoming", a));
        return s.getQueue();
    }

    @Test public void contextRowInAnAlbumUsesTheWebApi() throws Exception {
        assertTrue(QueueRoute.useWebApi(queue("context=spotify:track:a", "context=spotify:track:b"), 1, CUR,
                ALBUM, false, true));
    }

    @Test public void playlistContextUsesTheWebApi() throws Exception {
        assertTrue(QueueRoute.useWebApi(queue("context=spotify:track:a"), 0, CUR, "spotify:playlist:P", false,
                true));
    }

    @Test public void queuedOrAutoplayRowsUseSkips() throws Exception {
        assertFalse(QueueRoute.useWebApi(queue("queue=spotify:track:a"), 0, CUR, ALBUM, false, true));
        assertFalse(QueueRoute.useWebApi(queue("autoplay=spotify:track:a"), 0, CUR, ALBUM, false, true));
    }

    @Test public void artistContextUsesSkips() throws Exception {
        assertFalse(QueueRoute.useWebApi(queue("context=spotify:track:a"), 0, CUR, "spotify:artist:X", false,
                true));
    }

    @Test public void noContextUsesSkips() throws Exception {
        assertFalse(QueueRoute.useWebApi(queue("context=spotify:track:a"), 0, CUR, null, false, true));
    }

    @Test public void likedSongsUseTheWebApi() throws Exception {
        assertTrue(QueueRoute.useWebApi(queue("context=spotify:track:a"), 0, CUR, "spotify:user:potkc:collection",
                false, true));
    }

    @Test public void shuffleUsesSkips() throws Exception {
        // Task 0: an offset.uri jump while shuffled reshuffles the rest of the queue.
        assertFalse(QueueRoute.useWebApi(queue("context=spotify:track:a"), 0, CUR, ALBUM, true, true));
    }

    @Test public void notLoggedInUsesSkips() throws Exception {
        assertFalse(QueueRoute.useWebApi(queue("context=spotify:track:a"), 0, CUR, ALBUM, false, false));
    }

    @Test public void anIndexOutsideTheQueueUsesSkips() throws Exception {
        assertFalse(QueueRoute.useWebApi(queue("context=spotify:track:a"), 1, CUR, ALBUM, false, true));
        assertFalse(QueueRoute.useWebApi(queue("context=spotify:track:a"), -1, CUR, ALBUM, false, true));
    }

    @Test public void aDuplicateEarlierInTheQueueUsesSkips() throws Exception {
        assertFalse(QueueRoute.useWebApi(queue("context=spotify:track:a", "context=spotify:track:a"), 1, CUR,
                ALBUM, false, true));
    }

    @Test public void theCurrentTrackBeingTheSameAlsoCounts() throws Exception {
        // offset.uri picks the first occurrence in the context, which may be before the queue:
        // treat any duplicate of the target URI that can be seen as unsafe.
        assertFalse(QueueRoute.useWebApi(queue("context=spotify:track:a"), 0, "spotify:track:a", ALBUM, false,
                true));
    }

    @Test public void queuedRowsBeforeTheTargetUseSkips() throws Exception {
        // Task 0: manually queued tracks survive a context play, so they would still play next;
        // QueueJump consumes them on the way, as before.
        assertFalse(QueueRoute.useWebApi(queue("queue=spotify:track:q", "context=spotify:track:a"), 1, CUR,
                ALBUM, false, true));
        // Queued rows after the target do not matter.
        assertTrue(QueueRoute.useWebApi(queue("context=spotify:track:a", "queue=spotify:track:q"), 0, CUR,
                ALBUM, false, true));
    }

    @Test public void aTargetWithoutAUriUsesSkips() throws Exception {
        JSONArray a = new JSONArray().put(new JSONObject().put("source", "context").put("item", new JSONObject()));
        PlayerState s = new PlayerState();
        s.apply(new JSONObject().put("type", "queue_changed").put("upcoming", a));
        assertFalse(QueueRoute.useWebApi(s.getQueue(), 0, CUR, ALBUM, false, true));
    }

    @Test public void theBareCollectionContextUsesSkips() throws Exception {
        assertFalse(QueueRoute.useWebApi(queue("context=spotify:track:a"), 0, CUR, "spotify:collection", false,
                true));
    }
}
