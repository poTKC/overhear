package com.potkc.overhear.spotify;

import java.util.ArrayList;
import java.util.List;

import com.potkc.overhear.soloist.PlayerState;

/**
 * Another user's playlist, whose tracks the Web API refuses (403), rebuilt
 * from Soloist's queue once it plays from the start: the current track, then
 * the upcoming rows from the context (queued and autoplay rows are left
 * out). The queue may go on with a second round from the first track (a
 * 66-track playlist started at track 5 listed 65 upcoming), so the list stops
 * where the current track's URI comes round again. A playlist that really
 * holds the current track twice is cut at the second one too: the two cases
 * look the same. Pure Java.
 */
public final class QueueTracks {
    private QueueTracks() {}

    /** The playlist's tracks from Soloist's state, or null until the playlist is the context and a queue arrived. */
    public static List<Item> fromState(PlayerState s, String playlistUri) {
        String current = s.getTrackUri();
        if (playlistUri == null || !playlistUri.equals(s.getContextUri()) || s.isQueueStale() || current == null) {
            return null;
        }
        List<Item> items = new ArrayList<Item>();
        items.add(track(current, s.getTitle(), s.getArtists(), s.getSmallCoverUrl(), playlistUri));
        for (PlayerState.QueueItem q : s.getQueue()) {
            if (!"context".equals(q.source) || q.uri == null) continue;
            if (q.uri.equals(current)) break; // the context wrapped around
            items.add(track(q.uri, q.title, q.artists, q.smallCoverUrl, playlistUri));
        }
        return items;
    }

    private static Item track(String uri, String title, List<String> artists, String coverUrl, String playlistUri) {
        StringBuilder sb = new StringBuilder();
        for (String a : artists) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(a);
        }
        return new Item(Item.Kind.TRACK, uri, title, sb.toString(), coverUrl, null, playlistUri);
    }
}
