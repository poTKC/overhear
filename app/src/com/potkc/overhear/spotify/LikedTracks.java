package com.potkc.overhear.spotify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which tracks are in the user's Liked Songs, as far as this session knows:
 * from a check (SpotifyApi.isTrackSaved), a save or remove, or a page of the
 * liked songs. A track is checked by one call at a time; everyone who asks
 * meanwhile waits for it (ask) and is told when it answers or fails. After a
 * failure the track is not asked again for RETRY_MS. A save or remove in
 * flight (startChange) keeps a second one from starting. Another login
 * (generation) forgets everything, waiters included. Not thread-safe: use it
 * from one thread (the main thread). Pure Java.
 */
public final class LikedTracks {
    /** After a failed check, how long before the track is checked again. */
    public static final long RETRY_MS = 30000;

    private final Map<String, Boolean> known = new HashMap<String, Boolean>();
    /** The tracks being checked, with who waits for the answer. */
    private final Map<String, List<Runnable>> checking = new HashMap<String, List<Runnable>>();
    /** When a track's last check failed. */
    private final Map<String, Long> failedAt = new HashMap<String, Long>();
    private final Set<String> changing = new HashSet<String>();
    private int generation = -1;

    /** Call before each use with the login's generation: a new one forgets what was known. */
    public void login(int generation) {
        if (generation == this.generation) return;
        this.generation = generation;
        known.clear();
        checking.clear();
        failedAt.clear();
        changing.clear();
    }

    /** Whether uri is liked, or null while unknown. */
    public Boolean get(String uri) {
        return known.get(uri);
    }

    public void put(String uri, boolean liked) {
        known.put(uri, liked);
    }

    /** Every track among items, as liked or not (a page of the liked songs: all liked). */
    public void putAll(List<Item> items, boolean liked) {
        for (Item i : items) {
            if (i.kind == Item.Kind.TRACK && likeable(i.uri)) known.put(i.uri, liked);
        }
    }

    /**
     * Asks for uri's state: when it is unknown and a track, waiter (if not
     * null) is told once a check answers or fails. Returns whether the
     * caller should start that check now: none is running and none failed
     * within RETRY_MS (then nobody is told until the next ask).
     */
    public boolean ask(String uri, long nowMs, Runnable waiter) {
        if (!likeable(uri) || known.containsKey(uri)) return false;
        List<Runnable> waiters = checking.get(uri);
        if (waiters != null) {
            if (waiter != null) waiters.add(waiter);
            return false;
        }
        Long failed = failedAt.get(uri);
        if (failed != null && nowMs - failed < RETRY_MS) return false;
        waiters = new ArrayList<Runnable>();
        if (waiter != null) waiters.add(waiter);
        checking.put(uri, waiters);
        return true;
    }

    /** The check of uri answered: records it and returns who waited (to run them). */
    public List<Runnable> answered(String uri, boolean liked) {
        known.put(uri, liked);
        failedAt.remove(uri);
        return waiters(uri);
    }

    /** The check of uri failed at nowMs: returns who waited (to run them; the state stays unknown). */
    public List<Runnable> failed(String uri, long nowMs) {
        failedAt.put(uri, nowMs);
        return waiters(uri);
    }

    private List<Runnable> waiters(String uri) {
        List<Runnable> w = checking.remove(uri);
        return w == null ? Collections.<Runnable>emptyList() : w;
    }

    /** Whether uri's state is being checked or changed: its menu item waits (disabled). */
    public boolean busy(String uri) {
        return checking.containsKey(uri) || changing.contains(uri);
    }

    /** A save or remove of uri starts; false (start none) if one is already in flight. */
    public boolean startChange(String uri) {
        return changing.add(uri);
    }

    /** The save or remove of uri ended (done or failed). */
    public void changeEnded(String uri) {
        changing.remove(uri);
    }

    /** Only tracks (spotify:track:) can be saved to Liked Songs here. */
    public static boolean likeable(String uri) {
        return uri != null && uri.startsWith("spotify:track:") && uri.length() > "spotify:track:".length();
    }
}
