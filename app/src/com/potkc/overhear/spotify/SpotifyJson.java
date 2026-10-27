package com.potkc.overhear.spotify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Turns Web API replies into Items and Pages. Every field may be missing or
 * JSON null (read with opt* and isNull, never throwing, like PlayerState);
 * items without a uri are dropped. Since February 2026 a playlist row's
 * track may sit under "item" or "track" (the new shape also has a boolean
 * "track"), so both are read.
 * Offset pages return the next offset as next, counting rows that were
 * skipped. Stateless; pure Java (org.json only, the subset Android 4.3 has).
 */
public final class SpotifyJson {
    private SpotifyJson() {
    }

    /** Search results by kind; lists are never null. */
    public static final class SearchResult {
        public final List<Item> tracks;
        public final List<Item> albums;
        public final List<Item> artists;
        public final List<Item> playlists;

        SearchResult(List<Item> tracks, List<Item> albums, List<Item> artists, List<Item> playlists) {
            this.tracks = tracks;
            this.albums = albums;
            this.artists = artists;
            this.playlists = playlists;
        }
    }

    /** The user ID from /me, "" when missing. */
    public static String me(JSONObject j) {
        return string(j, "id");
    }

    /** /me/playlists: subtitle is the owner's display name. */
    public static Page playlists(JSONObject j, int offset) {
        JSONArray a = j.optJSONArray("items");
        return new Page(playlistList(a), nextOffset(j, offset, a));
    }

    /** /me/albums. */
    public static Page savedAlbums(JSONObject j, int offset) {
        JSONArray a = j.optJSONArray("items");
        List<Item> out = new ArrayList<Item>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject row = a.optJSONObject(i);
            add(out, row == null ? null : album(row.optJSONObject("album")));
        }
        return new Page(out, nextOffset(j, offset, a));
    }

    /** /me/tracks: played in the liked songs, alone when userId is unknown. */
    public static Page savedTracks(JSONObject j, String userId, int offset) {
        String context = userId == null || userId.isEmpty() ? null : "spotify:user:" + userId + ":collection";
        JSONArray a = j.optJSONArray("items");
        List<Item> out = new ArrayList<Item>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject row = a.optJSONObject(i);
            add(out, row == null ? null : track(row.optJSONObject("track"), context, null));
        }
        return new Page(out, nextOffset(j, offset, a));
    }

    /**
     * /albums/{id}/tracks: the rows carry neither images nor their album, so
     * the page's album (its uri and cover) is used.
     */
    public static Page albumTracks(JSONObject j, String albumUri, String albumCover, int offset) {
        JSONArray a = j.optJSONArray("items");
        List<Item> out = new ArrayList<Item>();
        for (int i = 0; a != null && i < a.length(); i++) {
            add(out, track(a.optJSONObject(i), albumUri, albumCover,
                    albumUri == null || albumUri.isEmpty() ? null : new Item.Ref(albumUri, null, albumCover)));
        }
        return new Page(out, nextOffset(j, offset, a));
    }

    /**
     * /playlists/{id}/items: skips local files, null rows and anything that
     * is not a spotify:track: (episodes).
     */
    public static Page playlistTracks(JSONObject j, String playlistUri, int offset) {
        JSONArray a = j.optJSONArray("items");
        List<Item> out = new ArrayList<Item>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject row = a.optJSONObject(i);
            if (row == null || row.optBoolean("is_local", false)) continue;
            JSONObject t = row.optJSONObject("item");
            if (t == null) t = row.optJSONObject("track");
            if (t == null || t.optBoolean("is_local", false)) continue;
            if (!string(t, "uri").startsWith("spotify:track:")) continue;
            add(out, track(t, playlistUri, null));
        }
        return new Page(out, nextOffset(j, offset, a));
    }

    /** /me/following?type=artist: next is the cursor. */
    public static Page followedArtists(JSONObject j) {
        JSONObject artists = j.optJSONObject("artists");
        if (artists == null) return new Page(Collections.<Item>emptyList(), null);
        String next = null;
        if (!artists.isNull("next")) {
            JSONObject cursors = artists.optJSONObject("cursors");
            String after = cursors == null ? "" : string(cursors, "after");
            if (!after.isEmpty()) next = after;
        }
        return new Page(artistList(artists.optJSONArray("items")), next);
    }

    /** /artists/{id}/albums: subtitle is the release year. */
    public static Page artistAlbums(JSONObject j, int offset) {
        JSONArray a = j.optJSONArray("items");
        List<Item> out = new ArrayList<Item>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String date = string(o, "release_date");
            add(out, item(Item.Kind.ALBUM, o, date.length() >= 4 ? date.substring(0, 4) : date,
                    smallCover(o.optJSONArray("images")), null, null, artistRefs(o), null));
        }
        return new Page(out, nextOffset(j, offset, a));
    }

    /**
     * /me/player/recently-played: tracks play in their album, playlist or
     * liked songs (collection) context, alone otherwise. Only one page is used, so next is null.
     */
    public static Page recent(JSONObject j) {
        JSONArray a = j.optJSONArray("items");
        List<Item> out = new ArrayList<Item>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject row = a.optJSONObject(i);
            if (row == null) continue;
            String context = null;
            JSONObject c = row.optJSONObject("context");
            if (c != null) {
                String type = string(c, "type");
                String uri = string(c, "uri");
                if (("album".equals(type) || "playlist".equals(type) || "collection".equals(type))
                        && !uri.isEmpty()) context = uri;
            }
            add(out, track(row.optJSONObject("track"), context, null));
        }
        return new Page(out, null);
    }

    /** /search: tracks play inside their album, from that track. */
    public static SearchResult search(JSONObject j) {
        List<Item> tracks = new ArrayList<Item>();
        JSONArray a = items(j, "tracks");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null) continue;
            JSONObject album = t.optJSONObject("album");
            String albumUri = album == null ? "" : string(album, "uri");
            add(tracks, track(t, albumUri.isEmpty() ? null : albumUri, null));
        }
        List<Item> albums = new ArrayList<Item>();
        a = items(j, "albums");
        for (int i = 0; a != null && i < a.length(); i++) {
            add(albums, album(a.optJSONObject(i)));
        }
        return new SearchResult(tracks, albums, artistList(items(j, "artists")), playlistList(items(j, "playlists")));
    }

    /**
     * The smallest image at least 50 px wide; the last one when none says
     * so (playlist covers often have a null width); null when empty.
     */
    public static String smallCover(JSONArray images) {
        if (images == null || images.length() == 0) return null;
        String best = null;
        int bestWidth = Integer.MAX_VALUE;
        for (int i = 0; i < images.length(); i++) {
            JSONObject o = images.optJSONObject(i);
            if (o == null || o.isNull("width")) continue;
            int w = o.optInt("width", 0);
            String url = string(o, "url");
            if (w >= 50 && w < bestWidth && !url.isEmpty()) {
                best = url;
                bestWidth = w;
            }
        }
        if (best != null) return best;
        JSONObject last = images.optJSONObject(images.length() - 1);
        String url = last == null ? "" : string(last, "url");
        return url.isEmpty() ? null : url;
    }

    private static List<Item> playlistList(JSONArray a) {
        List<Item> out = new ArrayList<Item>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            JSONObject owner = o.optJSONObject("owner");
            String ownerName = owner == null ? "" : string(owner, "display_name");
            String ownerId = owner == null ? "" : string(owner, "id");
            add(out, item(Item.Kind.PLAYLIST, o, ownerName, smallCover(o.optJSONArray("images")),
                    ownerId.isEmpty() ? null : ownerId, null));
        }
        return out;
    }

    private static List<Item> artistList(JSONArray a) {
        List<Item> out = new ArrayList<Item>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            add(out, item(Item.Kind.ARTIST, o, "", smallCover(o.optJSONArray("images")), null, null));
        }
        return out;
    }

    private static Item album(JSONObject o) {
        if (o == null) return null;
        return item(Item.Kind.ALBUM, o, artistNames(o), smallCover(o.optJSONArray("images")), null, null,
                artistRefs(o), null);
    }

    /** A track; cover null means the track's album's. */
    private static Item track(JSONObject t, String contextUri, String cover) {
        return track(t, contextUri, cover, null);
    }

    /** As track(t, contextUri, cover), album null meaning the track's own (t.album), if any. */
    private static Item track(JSONObject t, String contextUri, String cover, Item.Ref album) {
        if (t == null) return null;
        JSONObject a = t.optJSONObject("album");
        String albumCover = a == null ? null : smallCover(a.optJSONArray("images"));
        if (cover == null) cover = albumCover;
        if (album == null && a != null) {
            String uri = string(a, "uri");
            if (!uri.isEmpty()) album = new Item.Ref(uri, nameOrNull(a), albumCover);
        }
        return item(Item.Kind.TRACK, t, artistNames(t), cover, null, contextUri, artistRefs(t), album);
    }

    /** null when the object has no uri. */
    private static Item item(Item.Kind kind, JSONObject o, String subtitle, String cover, String ownerId,
            String contextUri) {
        return item(kind, o, subtitle, cover, ownerId, contextUri, null, null);
    }

    private static Item item(Item.Kind kind, JSONObject o, String subtitle, String cover, String ownerId,
            String contextUri, List<Item.Ref> artists, Item.Ref album) {
        String uri = string(o, "uri");
        if (uri.isEmpty()) return null;
        return new Item(kind, uri, string(o, "name"), subtitle, cover, ownerId, contextUri, artists, album);
    }

    /** o's artists that have a uri (name null when missing). */
    private static List<Item.Ref> artistRefs(JSONObject o) {
        JSONArray a = o.optJSONArray("artists");
        List<Item.Ref> out = new ArrayList<Item.Ref>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject artist = a.optJSONObject(i);
            String uri = artist == null ? "" : string(artist, "uri");
            if (!uri.isEmpty()) out.add(new Item.Ref(uri, nameOrNull(artist), null));
        }
        return out;
    }

    private static String nameOrNull(JSONObject o) {
        String name = string(o, "name");
        return name.isEmpty() ? null : name;
    }

    private static void add(List<Item> out, Item item) {
        if (item != null) out.add(item);
    }

    /** The artists' names joined with ", ", skipping missing ones. */
    private static String artistNames(JSONObject o) {
        JSONArray a = o.optJSONArray("artists");
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject artist = a.optJSONObject(i);
            String name = artist == null ? "" : string(artist, "name");
            if (name.isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(name);
        }
        return b.toString();
    }

    /** The items array of a search result's kind, or null. */
    private static JSONArray items(JSONObject j, String kind) {
        JSONObject o = j.optJSONObject(kind);
        return o == null ? null : o.optJSONArray("items");
    }

    /** The following page's offset, or null on the last page. */
    private static String nextOffset(JSONObject j, int offset, JSONArray items) {
        if (j.isNull("next") || items == null || items.length() == 0) return null;
        return String.valueOf(offset + items.length());
    }

    /** The string at key, "" when missing or JSON null (Android's optString gives "null"). */
    static String string(JSONObject j, String key) {
        return j.isNull(key) ? "" : j.optString(key, "");
    }
}
