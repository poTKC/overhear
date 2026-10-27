package com.potkc.overhear;

import java.util.List;

import com.potkc.overhear.soloist.PlayerState;

/**
 * Decides how to reach a tapped queue entry: one Web API play of the context
 * from that track, or QueueJump's skip_next steps. The Web API works only
 * for tracks of the playing context (not queued or autoplay ones) in a
 * context Spotify takes an offset for (albums, playlists, liked songs), and
 * picks the first occurrence of a URI, so a duplicate ahead of the target
 * (the current track included) rules it out. A duplicate already played,
 * before the visible queue, cannot be seen: offset.uri then resolves to that
 * earlier occurrence, so the right song plays but the context goes on from
 * the earlier position, replaying the tracks in between. That is accepted,
 * as Soloist gives no absolute position in the context to jump by. Shuffle rules it out too (the jump reshuffles the rest), and
 * so does any queued or autoplay row ahead of the target, which a context
 * play would leave in place (QueueJump consumes them on the way). Pure Java.
 */
final class QueueRoute {
    private QueueRoute() {}

    static boolean useWebApi(List<PlayerState.QueueItem> queue, int index, String currentUri, String contextUri,
            boolean shuffle, boolean loggedIn) {
        if (!loggedIn || shuffle || contextUri == null || index < 0 || index >= queue.size()) return false;
        if (!(contextUri.startsWith("spotify:album:") || contextUri.startsWith("spotify:playlist:")
                || (contextUri.startsWith("spotify:user:") && contextUri.endsWith(":collection")))) {
            return false;
        }
        PlayerState.QueueItem target = queue.get(index);
        if (!"context".equals(target.source) || target.uri == null) return false;
        if (target.uri.equals(currentUri)) return false;
        for (int i = 0; i < index; i++) {
            PlayerState.QueueItem before = queue.get(i);
            if (target.uri.equals(before.uri) || !"context".equals(before.source)) return false;
        }
        return true;
    }
}
