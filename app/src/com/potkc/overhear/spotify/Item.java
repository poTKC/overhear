package com.potkc.overhear.spotify;

/**
 * One row of a library list. For tracks, contextUri is where to play them
 * (an album, a playlist, the liked songs) with the track as the offset; null
 * plays the track alone. Immutable. Pure Java.
 */
public final class Item {
    public enum Kind { TRACK, ALBUM, PLAYLIST, ARTIST }

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

    public Item(Kind kind, String uri, String title, String subtitle, String coverUrl, String ownerId,
            String contextUri) {
        this.kind = kind;
        this.uri = uri;
        this.title = title;
        this.subtitle = subtitle;
        this.coverUrl = coverUrl;
        this.ownerId = ownerId;
        this.contextUri = contextUri;
    }

    /** The bare ID of a spotify:<type>:<id> URI. */
    public String id() {
        return uri.substring(uri.lastIndexOf(':') + 1);
    }
}
