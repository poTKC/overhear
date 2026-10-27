package com.potkc.overhear.spotify;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which tracks are in the user's Liked Songs, as far as this session knows:
 * from a check (SpotifyApi.isTrackSaved), a save or remove, or a page of the
 * liked songs. Each track is checked at most once per login unless the check
 * failed; another login (generation) forgets everything. Not thread-safe:
 * use it from one thread (the main thread). Pure Java.
 */
public final class LikedTracks {
    private final Map<String, Boolean> known = new HashMap<String, Boolean>();
    private final Set<String> asked = new HashSet<String>();
    private int generation = -1;

    /** Call before each use with the login's generation: a new one forgets what was known. */
    public void login(int generation) {
        if (generation == this.generation) return;
        this.generation = generation;
        known.clear();
        asked.clear();
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

    /** Whether to check uri now: a track, unknown and not asked yet this login. Marks it asked. */
    public boolean shouldCheck(String uri) {
        if (!likeable(uri) || known.containsKey(uri)) return false;
        return asked.add(uri);
    }

    /** The check for uri failed: it may be asked again. */
    public void checkFailed(String uri) {
        asked.remove(uri);
    }

    /** Only tracks (spotify:track:) can be saved to Liked Songs here. */
    public static boolean likeable(String uri) {
        return uri != null && uri.startsWith("spotify:track:") && uri.length() > "spotify:track:".length();
    }
}
