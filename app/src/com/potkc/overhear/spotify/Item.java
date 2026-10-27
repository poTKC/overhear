package com.potkc.overhear.spotify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One row of a library list. For tracks, contextUri is where to play them
 * (an album, a playlist, the liked songs) with the track as the offset; null
 * plays the track alone. Tracks and albums also link to their artists, and
 * tracks to their album, for the long-press (LibraryPath.linksOf).
 * Immutable. Pure Java.
 */
public final class Item {
    public enum Kind { TRACK, ALBUM, PLAYLIST, ARTIST }

    /** An album or artist a row links to: its uri (never null), and name and cover if known. Immutable. */
    public static final class Ref {
        public final String uri;
        public final String name;
        /** Albums: about 64 px, or null. */
        public final String coverUrl;

        public Ref(String uri, String name, String coverUrl) {
            if (uri == null || uri.isEmpty()) throw new IllegalArgumentException("no uri");
            this.uri = uri;
            this.name = name;
            this.coverUrl = coverUrl;
        }
    }

    public final Kind kind;
    public final String uri;
    public final String title;
    /** Artists, owner, or "" when there is nothing to show. */
    public final String subtitle;
    /** About 64 px, or null. */
    public final String coverUrl;
    /** Playlists: the owner's user ID; null otherwise. */
    public final String ownerId;
    /** Tracks: the context to play them in, or null. */
    public final String contextUri;
    /** Tracks and albums: their artists with a uri, in order; never null. */
    public final List<Ref> artists;
    /** Tracks: their album, or null. */
    public final Ref album;

    public Item(Kind kind, String uri, String title, String subtitle, String coverUrl, String ownerId,
            String contextUri) {
        this(kind, uri, title, subtitle, coverUrl, ownerId, contextUri, null, null);
    }

    /** artists null: none. */
    public Item(Kind kind, String uri, String title, String subtitle, String coverUrl, String ownerId,
            String contextUri, List<Ref> artists, Ref album) {
        this.artists = artists == null || artists.isEmpty() ? Collections.<Ref>emptyList()
                : Collections.unmodifiableList(new ArrayList<Ref>(artists));
        this.album = album;
        this.kind = kind;
        this.uri = uri;
        this.title = title;
        this.subtitle = subtitle;
        this.coverUrl = coverUrl;
        this.ownerId = ownerId;
        this.contextUri = contextUri;
    }

    /**
     * Whether this row is what plays now, given Soloist's current track and
     * context (either may be null): a track by its own URI, an album,
     * playlist or artist when it is the context.
     */
    public boolean playsNow(String trackUri, String contextUri) {
        return uri != null && uri.equals(kind == Kind.TRACK ? trackUri : contextUri);
    }

    /** The bare ID of a spotify:<type>:<id> URI. */
    public String id() {
        return uri.substring(uri.lastIndexOf(':') + 1);
    }
}
