package com.potkc.overhear.spotify;

import java.util.List;

import com.potkc.overhear.soloist.PlayerState;

/**
 * Follows Soloist's state after another user's playlist was asked to play
 * from the top, and rebuilds its track list from the queue (QueueTracks).
 *
 * The first track is the first one seen playing in the playlist since the
 * request: Soloist reports the context first, while the state still shows
 * the previous track and then briefly none (""), so a track only counts
 * once a track_changed came or it differs from the one playing at open.
 * Restarting the track that already played sends no track_changed, so
 * ADOPT_AFTER_MS after the request returned a track near its start in the
 * playlist is taken as the first. The longest list seen is kept: the
 * regular 10-entry queue that follows must not shorten it, and once
 * another track plays QueueTracks gives none, so the list stays in order.
 * Without a list GIVE_UP_AFTER_MS after the request (unless that tick
 * just adopted the first track: then the next tick, at the latest the one
 * LAST_CHECK_AFTER_MS after, gives up), or if the context leaves the
 * playlist first, the status is FAILED (the caller offers to
 * play again, with a new tracker).
 *
 * Pure Java, one thread (the caller's); times come from the caller.
 */
public final class OthersTracker {
    public enum Status { STARTING, READY, FAILED }

    public static final long ADOPT_AFTER_MS = 2500;
    public static final long GIVE_UP_AFTER_MS = 10000;
    /** A last check, for a first track adopted in the GIVE_UP_AFTER_MS tick whose list never came. */
    public static final long LAST_CHECK_AFTER_MS = 20000;
    /** How far into the track the timed adoption still takes it for a fresh start. */
    public static final long ADOPT_MAX_POSITION_MS = 5000;

    private final String playlistUri;
    /** The state at open (or at a new connection), whose track does not count without a track_changed. */
    private int baseTrackChanges = -1;
    private String baseTrackUri;
    /** When the play request returned; -1 before. */
    private long playReturnedMs = -1;
    /** The playlist was seen as the context. */
    private boolean sawContext;
    private String firstTrackUri;
    private String currentTrackUri;
    private boolean queueAsked;
    private boolean shuffle;
    private boolean failed;
    private List<Item> items;
    /** Bumped whenever status, items or shuffle change. */
    private int version;

    public OthersTracker(String playlistUri) {
        this.playlistUri = playlistUri;
    }

    public String playlistUri() {
        return playlistUri;
    }

    /** Before asking to play: before is the current state, null if unknown. */
    public void onOpen(PlayerState before) {
        baseline(before);
        if (before != null) {
            shuffle = before.isShuffle();
            currentTrackUri = before.getTrackUri();
            sawContext = playlistUri.equals(before.getContextUri());
        }
    }

    /** The play request returned (done or failed) at nowMs; the timed checks count from the first call. */
    public void onPlayReturned(long nowMs) {
        if (playReturnedMs < 0) playReturnedMs = nowMs;
    }

    /**
     * A connection came or went, or the caller now follows another client:
     * the queue is asked for again, and a new connection's track count
     * (from 0) becomes the baseline while no first track is known.
     */
    public void onConnected(boolean connected, PlayerState s) {
        queueAsked = false;
        if (connected && firstTrackUri == null) baseline(s);
    }

    public void onState(PlayerState s) {
        if (s.isShuffle() != shuffle) {
            shuffle = s.isShuffle();
            version++;
        }
        String track = s.getTrackUri();
        currentTrackUri = track;
        String context = s.getContextUri();
        boolean inPlaylist = playlistUri.equals(context);
        if (inPlaylist) sawContext = true;
        if (firstTrackUri == null && inPlaylist && !isEmpty(track)
                && (s.getTrackChanges() != baseTrackChanges || !track.equals(baseTrackUri))) {
            firstTrackUri = track;
        }
        collect(s);
        if (items == null && !failed && playReturnedMs >= 0 && sawContext && !inPlaylist && !isEmpty(context)) {
            failed = true; // played something else before the list came
            version++;
        }
    }

    /**
     * onState plus the timed checks; call it ADOPT_AFTER_MS, GIVE_UP_AFTER_MS
     * and LAST_CHECK_AFTER_MS after the request returned.
     */
    public void onTick(PlayerState s, long nowMs) {
        onState(s);
        if (playReturnedMs < 0) return;
        long since = nowMs - playReturnedMs;
        String track = s.getTrackUri();
        boolean adopted = false;
        if (firstTrackUri == null && since >= ADOPT_AFTER_MS && playlistUri.equals(s.getContextUri())
                && !isEmpty(track) && s.positionAt(nowMs) < ADOPT_MAX_POSITION_MS) {
            firstTrackUri = track; // restarted without a track_changed
            adopted = true;
            collect(s);
        }
        // Not in the tick that adopted: the queue request has yet to go out.
        if (items == null && !failed && !adopted && since >= GIVE_UP_AFTER_MS) {
            failed = true;
            version++;
        }
    }

    /**
     * Whether to ask for the whole queue now (true once per connection):
     * once the first track is known, and unless a list came and another
     * track plays (its queue would be rotated anyway).
     */
    public boolean takeQueueRequest(boolean connected) {
        if (!connected || queueAsked || firstTrackUri == null) return false;
        if (items != null && !firstTrackUri.equals(currentTrackUri)) return false;
        queueAsked = true;
        return true;
    }

    public Status status() {
        if (items != null) return Status.READY;
        return failed ? Status.FAILED : Status.STARTING;
    }

    /** The longest list so far; null until one. */
    public List<Item> items() {
        return items;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    /** The first track in the playlist; null until known. */
    public String firstTrackUri() {
        return firstTrackUri;
    }

    /** Changes whenever status(), items() or isShuffle() may have. */
    public int version() {
        return version;
    }

    private void collect(PlayerState s) {
        List<Item> got = QueueTracks.fromState(s, playlistUri, firstTrackUri);
        if (got != null && (items == null || got.size() > items.size())) {
            items = got;
            version++;
        }
    }

    private void baseline(PlayerState s) {
        baseTrackChanges = s == null ? -1 : s.getTrackChanges();
        baseTrackUri = s == null ? null : s.getTrackUri();
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
