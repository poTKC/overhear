package com.potkc.overhear.spotify;

import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Spotify Web API calls Overhear makes, through an ApiTransport. Each
 * call blocks (run it off the main thread). A 401 refreshes the access token
 * and retries once. The user ID and the playback device ID are cached per
 * login (SpotifyAuth.generation()), the device also by name. Safe to call
 * from any thread; meant for one background thread (the device lookup and
 * PUT of play and shuffle are not atomic). Pure Java.
 */
public final class SpotifyApi {
    public static final int PAGE = 50;
    public static final int SEARCH_PAGE = 10;
    /** /artists/{id}/albums answers 400 "Invalid limit" above 10 (development mode, 2026-10). */
    public static final int ARTIST_ALBUMS_PAGE = 10;
    /** Lists of tracks and albums follow the user's country for what is playable. */
    private static final String MARKET = "&market=from_token";

    private final ApiTransport transport;
    private final SpotifyAuth auth;
    private volatile Cached userId;
    private static final String DEVICE_NOT_LISTED = "device not listed";

    private volatile Device device;

    /** The user ID and the login it was fetched under. */
    private static final class Cached {
        final String value;
        final int generation;

        Cached(String value, int generation) {
            this.value = value;
            this.generation = generation;
        }
    }

    /** A device's name and ID, and the login it was looked up under. */
    private static final class Device {
        final String name;
        final String id;
        final int generation;

        Device(String name, String id, int generation) {
            this.name = name;
            this.id = id;
            this.generation = generation;
        }
    }

    public SpotifyApi(ApiTransport transport, SpotifyAuth auth) {
        this.transport = transport;
        this.auth = auth;
    }

    public SpotifyAuth auth() {
        return auth;
    }

    /** The user's ID ("" when the reply has none), fetched once per login. */
    public String me() throws IOException {
        Cached c = userId;
        int generation = auth.generation();
        if (c != null && c.generation == generation) return c.value;
        String id = SpotifyJson.me(getJson("/me"));
        if (!id.isEmpty()) userId = new Cached(id, generation);
        return id;
    }

    public Page playlists(int offset) throws IOException {
        return SpotifyJson.playlists(getJson("/me/playlists" + page(offset)), offset);
    }

    public Page savedAlbums(int offset) throws IOException {
        return SpotifyJson.savedAlbums(getJson("/me/albums" + page(offset) + MARKET), offset);
    }

    /** The liked songs, played in the collection once me() knows the user. */
    public Page savedTracks(int offset) throws IOException {
        String id = me();
        return SpotifyJson.savedTracks(getJson("/me/tracks" + page(offset) + MARKET), id, offset);
    }

    public Page albumTracks(String albumUri, String albumCover, int offset) throws IOException {
        return SpotifyJson.albumTracks(getJson("/albums/" + id(albumUri) + "/tracks" + page(offset) + MARKET),
                albumUri, albumCover, offset);
    }

    public Page playlistTracks(String playlistUri, int offset) throws IOException {
        return SpotifyJson.playlistTracks(
                getJson("/playlists/" + id(playlistUri) + "/items" + page(offset) + MARKET), playlistUri, offset);
    }

    /** after is the previous page's next cursor, null for the first page. */
    public Page followedArtists(String after) throws IOException {
        String path = "/me/following?type=artist&limit=" + PAGE + (after == null ? "" : "&after=" + Pkce.enc(after));
        return SpotifyJson.followedArtists(getJson(path));
    }

    /** Albums and singles only, ARTIST_ALBUMS_PAGE at a time. */
    public Page artistAlbums(String artistUri, int offset) throws IOException {
        return SpotifyJson.artistAlbums(getJson("/artists/" + id(artistUri) + "/albums?include_groups="
                + Pkce.enc("album,single") + "&limit=" + ARTIST_ALBUMS_PAGE + "&offset=" + offset + MARKET), offset);
    }

    /** One page of recently played tracks. */
    public Page recent() throws IOException {
        return SpotifyJson.recent(getJson("/me/player/recently-played?limit=" + PAGE));
    }

    /**
     * Without MARKET: market=from_token on /search answers 403 "Insufficient
     * client scope" (it would need user-read-private, which we do not ask for).
     */
    public SpotifyJson.SearchResult search(String query) throws IOException {
        return SpotifyJson.search(getJson("/search?q=" + Pkce.enc(query) + "&type="
                + Pkce.enc("track,album,artist,playlist") + "&limit=" + SEARCH_PAGE));
    }

    /**
     * Starts playback on the device named deviceName: contextUri from offsetUri
     * (null: from the top), or offsetUri alone when contextUri is null.
     */
    public void play(String deviceName, String contextUri, String offsetUri) throws IOException {
        JSONObject offset = null;
        if (contextUri != null && offsetUri != null) offset = put(new JSONObject(), "uri", offsetUri);
        putOnDevice(deviceName, "/me/player/play?", playBody(contextUri, offsetUri, offset));
    }

    /**
     * Plays contextUri from its first track (offset.position 0) on the device
     * named deviceName. Unlike a play without an offset, this starts there
     * even if shuffle is on (a shuffle set just before does not always take).
     */
    public void playFirst(String deviceName, String contextUri) throws IOException {
        if (contextUri == null) throw new IllegalArgumentException("nothing to play");
        putOnDevice(deviceName, "/me/player/play?",
                playBody(contextUri, null, put(new JSONObject(), "position", 0)));
    }

    /**
     * Turns shuffle on or off on the device named deviceName. With shuffle on,
     * a following play of a context without an offset starts at a random track.
     */
    public void shuffle(String deviceName, boolean on) throws IOException {
        putOnDevice(deviceName, "/me/player/shuffle?state=" + on + "&", "");
    }

    /**
     * PUTs body to path (ending in ? or &) plus device_id. The device ID is
     * cached; a 404 (the ID changed) looks the device up again, skipping the
     * ID that failed, and retries once.
     */
    private void putOnDevice(String deviceName, String path, String body) throws IOException {
        Device d = device;
        String id = d != null && d.generation == auth.generation() && d.name.equals(deviceName)
                ? d.id : findDevice(deviceName, null);
        try {
            put(path + "device_id=" + Pkce.enc(id), body);
        } catch (SpotifyException e) {
            if (e.reason != SpotifyException.Reason.HTTP || e.status != 404) throw e;
            device = null;
            put(path + "device_id=" + Pkce.enc(findDevice(deviceName, id)), body);
        }
    }

    /** Looks deviceName up in /me/player/devices, skipping exclude, and caches its ID. */
    private String findDevice(String deviceName, String exclude) throws IOException {
        int generation = auth.generation();
        JSONArray a = getJson("/me/player/devices").optJSONArray("devices");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject d = a.optJSONObject(i);
            if (d == null || !deviceName.equals(SpotifyJson.string(d, "name"))) continue;
            String id = SpotifyJson.string(d, "id");
            if (id.isEmpty() || id.equals(exclude)) continue;
            device = new Device(deviceName, id, generation);
            return id;
        }
        throw new SpotifyException(SpotifyException.Reason.HTTP, 404, -1, DEVICE_NOT_LISTED);
    }

    /** play's or shuffle's failure when deviceName is not among the user's devices: nothing was sent. */
    public static boolean isDeviceNotListed(IOException e) {
        return e instanceof SpotifyException && ((SpotifyException) e).reason == SpotifyException.Reason.HTTP
                && ((SpotifyException) e).status == 404 && DEVICE_NOT_LISTED.equals(e.getMessage());
    }

    /** contextUri with offset (or null), else trackUri alone. */
    private static String playBody(String contextUri, String trackUri, JSONObject offset) {
        if (contextUri == null && trackUri == null) throw new IllegalArgumentException("nothing to play");
        JSONObject o = new JSONObject();
        if (contextUri == null) {
            put(o, "uris", new JSONArray().put(trackUri));
        } else {
            put(o, "context_uri", contextUri);
            if (offset != null) put(o, "offset", offset);
        }
        return o.toString();
    }

    private static JSONObject put(JSONObject o, String key, Object value) {
        try {
            return o.put(key, value);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    private static String page(int offset) {
        return "?limit=" + PAGE + "&offset=" + offset;
    }

    /** The bare ID of a spotify: URI, URL-encoded. */
    private static String id(String uri) {
        return Pkce.enc(uri.substring(uri.lastIndexOf(':') + 1));
    }

    JSONObject getJson(String path) throws IOException {
        HelperReply r = call("GET", path, "");
        if (r.status != 200) throw httpError("GET " + stripQuery(path), r);
        try {
            return new JSONObject(r.body);
        } catch (JSONException e) {
            throw new IOException("bad JSON from " + stripQuery(path));
        }
    }

    /** PUT with a JSON body; any 2xx is success. */
    void put(String path, String body) throws IOException {
        HelperReply r = call("PUT", path, body);
        if (r.status / 100 != 2) throw httpError("PUT " + stripQuery(path), r);
    }

    private HelperReply call(String method, String path, String body) throws IOException {
        String sent = auth.accessToken();
        HelperReply r = transport.api(method, path, sent, body);
        if (r.status == 401) {
            auth.invalidate(sent);
            r = transport.api(method, path, auth.accessToken(), body);
        }
        return r;
    }

    static SpotifyException httpError(String what, HelperReply r) {
        if (r.status == 429) {
            return new SpotifyException(SpotifyException.Reason.RATE_LIMITED, 429, r.retryAfterSeconds,
                    what + ": rate limited");
        }
        return new SpotifyException(SpotifyException.Reason.HTTP, r.status, -1, what + ": HTTP " + r.status);
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }
}
