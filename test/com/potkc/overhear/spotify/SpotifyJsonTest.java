package com.potkc.overhear.spotify;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import com.potkc.overhear.soloist.PlayerStateTest;

/** Fixtures in test/fixtures/spotify are hand-written in the Web API's shapes. */
public class SpotifyJsonTest {
    private static JSONObject f(String name) {
        return PlayerStateTest.fixture("spotify/" + name);
    }

    @Test public void me() {
        assertEquals("user1", SpotifyJson.me(f("me")));
    }

    @Test public void playlistsKeepOwnerAndNextOffset() {
        Page p = SpotifyJson.playlists(f("playlists"), 10);
        assertEquals(3, p.items.size());
        Item first = p.items.get(0);
        assertEquals(Item.Kind.PLAYLIST, first.kind);
        assertEquals("spotify:playlist:P1", first.uri);
        assertEquals("P1", first.id());
        assertEquals("Mix One", first.title);
        assertEquals("Owner One", first.subtitle);
        assertEquals("owner1", first.ownerId);
        assertEquals("https://i.example/p1", first.coverUrl); // one image without a width
        assertNull(first.contextUri);
        assertEquals("13", p.next);
    }

    @Test public void playlistsWithANullOwnerNameOrNoImages() {
        Page p = SpotifyJson.playlists(f("playlists"), 10);
        assertEquals("", p.items.get(1).subtitle);
        assertEquals("user1", p.items.get(1).ownerId);
        assertEquals("https://i.example/p2-60", p.items.get(1).coverUrl);
        assertNull(p.items.get(2).coverUrl);
    }

    @Test public void savedAlbumsJoinArtists() {
        Page p = SpotifyJson.savedAlbums(f("saved_albums"), 0);
        assertEquals(2, p.items.size());
        Item a = p.items.get(0);
        assertEquals(Item.Kind.ALBUM, a.kind);
        assertEquals("spotify:album:A1", a.uri);
        assertEquals("Album One", a.title);
        assertEquals("Artist One, Artist Two", a.subtitle);
        assertEquals("https://i.example/a1-64", a.coverUrl);
        assertNull(p.next);
    }

    @Test public void savedTracksPlayInTheLikedSongs() {
        Page p = SpotifyJson.savedTracks(f("saved_tracks"), "potkc", 0);
        assertEquals(2, p.items.size());
        Item t = p.items.get(0);
        assertEquals(Item.Kind.TRACK, t.kind);
        assertEquals("spotify:track:T1", t.uri);
        assertEquals("Track One", t.title);
        assertEquals("Artist One, Artist Two", t.subtitle);
        assertEquals("https://i.example/a3-64", t.coverUrl);
        assertEquals("spotify:user:potkc:collection", t.contextUri);
        assertEquals("2", p.next);
    }

    @Test public void savedTracksWithoutAUserPlayAlone() {
        assertNull(SpotifyJson.savedTracks(f("saved_tracks"), null, 0).items.get(0).contextUri);
        assertNull(SpotifyJson.savedTracks(f("saved_tracks"), "", 0).items.get(0).contextUri);
    }

    @Test public void emptyPageHasNoNextEvenIfTheReplySaysSo() throws Exception {
        assertNull(SpotifyJson.playlists(new JSONObject("{\"items\":[],\"next\":\"x\"}"), 0).next);
    }

    @Test public void albumTracksUseTheAlbumCover() {
        Page p = SpotifyJson.albumTracks(f("album_tracks"), "spotify:album:A", "http://cover", 0);
        assertEquals(3, p.items.size());
        assertEquals("http://cover", p.items.get(0).coverUrl);
        assertEquals("spotify:album:A", p.items.get(0).contextUri);
        assertEquals("Side Two", p.items.get(1).title);
        assertEquals("Artist One, Guest Five", p.items.get(1).subtitle);
        assertEquals("3", p.next);
    }

    @Test public void playlistItemsAcceptOldAndNewShapesAndSkipLocalAndNull() {
        Page p = SpotifyJson.playlistTracks(f("playlist_items"), "spotify:playlist:P", 0);
        // fixture: 1 new-shape row, 1 old-shape row, 1 local, 1 null, 1 episode
        assertEquals(2, p.items.size());
        assertEquals("spotify:track:PT1", p.items.get(0).uri);
        assertEquals("Artist Five", p.items.get(0).subtitle);
        assertEquals("https://i.example/a5-64", p.items.get(0).coverUrl);
        assertEquals("spotify:playlist:P", p.items.get(0).contextUri);
        assertEquals("spotify:track:PT2", p.items.get(1).uri);
        assertEquals("Old Shape", p.items.get(1).title);
        // Skipped rows still count toward the next offset.
        assertEquals("5", p.next);
    }

    @Test public void followedArtistsUseTheCursor() {
        Page p = SpotifyJson.followedArtists(f("followed_artists"));
        assertEquals(2, p.items.size());
        Item a = p.items.get(0);
        assertEquals(Item.Kind.ARTIST, a.kind);
        assertEquals("spotify:artist:R1", a.uri);
        assertEquals("Artist One", a.title);
        assertEquals("", a.subtitle);
        assertEquals("https://i.example/r1-160", a.coverUrl);
        assertNull(p.items.get(1).coverUrl);
        assertEquals("cursor1", p.next);
    }

    @Test public void followedArtistsLastPage() throws Exception {
        JSONObject j = new JSONObject("{\"artists\":{\"next\":null,\"cursors\":{\"after\":null},\"items\":[]}}");
        assertNull(SpotifyJson.followedArtists(j).next);
    }

    @Test public void followedArtistsWithoutACursorStop() throws Exception {
        JSONObject j = new JSONObject("{\"artists\":{\"next\":\"x\",\"items\":[]}}");
        assertNull(SpotifyJson.followedArtists(j).next);
        j = new JSONObject("{\"artists\":{\"next\":\"x\",\"cursors\":{\"after\":null},\"items\":[]}}");
        assertNull(SpotifyJson.followedArtists(j).next);
    }

    @Test public void artistAlbumsShowTheYear() {
        Page p = SpotifyJson.artistAlbums(f("artist_albums"), 0);
        assertEquals(2, p.items.size());
        assertEquals(Item.Kind.ALBUM, p.items.get(0).kind);
        assertEquals("2013", p.items.get(0).subtitle);
        assertEquals("https://i.example/a7-64", p.items.get(0).coverUrl);
        assertEquals("1997", p.items.get(1).subtitle);
        assertEquals("2", p.next);
    }

    @Test public void recentKeepsTheContextOrNone() {
        Page p = SpotifyJson.recent(f("recent"));
        assertEquals(4, p.items.size());
        assertEquals("spotify:album:A7", p.items.get(0).contextUri);
        assertNull(p.items.get(1).contextUri); // the fixture's context: null row
        assertEquals("spotify:playlist:P1", p.items.get(2).contextUri);
        assertEquals("spotify:user:user1:collection", p.items.get(3).contextUri); // liked songs
        assertEquals("Recent Two", p.items.get(1).title);
        assertEquals("https://i.example/a8-64", p.items.get(1).coverUrl);
        assertNull(p.next);
    }

    @Test public void recentIgnoresOtherContexts() throws Exception {
        JSONObject j = new JSONObject("{\"items\":[{\"track\":{\"uri\":\"spotify:track:X\",\"name\":\"X\"},"
                + "\"context\":{\"type\":\"artist\",\"uri\":\"spotify:artist:R\"}}]}");
        assertNull(SpotifyJson.recent(j).items.get(0).contextUri);
    }

    @Test public void searchSplitsByKindAndSkipsNulls() {
        SpotifyJson.SearchResult r = SpotifyJson.search(f("search"));
        assertEquals(2, r.tracks.size());
        assertEquals(1, r.albums.size());
        assertEquals(1, r.artists.size());
        assertEquals(1, r.playlists.size());
        for (Item i : r.playlists) assertNotNull(i);
        assertEquals("owner9", r.playlists.get(0).ownerId);
        assertEquals(Item.Kind.ALBUM, r.albums.get(0).kind);
        assertEquals("Artist Nine", r.albums.get(0).subtitle);
        assertEquals(Item.Kind.ARTIST, r.artists.get(0).kind);
        Item t = r.tracks.get(0);
        assertEquals(Item.Kind.TRACK, t.kind);
        assertEquals("https://i.example/s1-64", t.coverUrl);
        // Search tracks play inside their album, from that track.
        assertEquals("spotify:album:S1", t.contextUri);
        assertNull(r.tracks.get(1).contextUri); // no album
        assertNull(r.tracks.get(1).coverUrl);
    }

    @Test public void searchWithMissingKinds() throws Exception {
        SpotifyJson.SearchResult r = SpotifyJson.search(new JSONObject("{\"tracks\":{\"items\":[]}}"));
        assertTrue(r.tracks.isEmpty());
        assertTrue(r.albums.isEmpty());
        assertTrue(r.artists.isEmpty());
        assertTrue(r.playlists.isEmpty());
    }

    @Test public void itemsWithoutUriAreSkipped() throws Exception {
        JSONObject j = new JSONObject("{\"items\":[{\"name\":\"no uri\"},{\"uri\":null,\"name\":\"null uri\"},"
                + "{\"uri\":\"spotify:album:A\",\"name\":\"ok\"}],\"next\":null}");
        Page p = SpotifyJson.artistAlbums(j, 0);
        assertEquals(1, p.items.size());
        assertEquals("ok", p.items.get(0).title);
        assertEquals("", p.items.get(0).subtitle);
    }

    @Test public void smallestCoverAboveFiftyPixels() throws Exception {
        // images sorted large→small: 640, 300, 64 → 64
        assertEquals("http://64", SpotifyJson.smallCover(new JSONArray(
                "[{\"url\":\"http://640\",\"width\":640},{\"url\":\"http://300\",\"width\":300},{\"url\":\"http://64\",\"width\":64}]")));
    }

    @Test public void smallCoverSkipsTinyAndFallsBackToTheLast() throws Exception {
        assertEquals("http://300", SpotifyJson.smallCover(new JSONArray(
                "[{\"url\":\"http://300\",\"width\":300},{\"url\":\"http://40\",\"width\":40}]")));
        assertEquals("http://b", SpotifyJson.smallCover(new JSONArray(
                "[{\"url\":\"http://a\",\"width\":null},{\"url\":\"http://b\",\"width\":null}]")));
        assertNull(SpotifyJson.smallCover(new JSONArray("[]")));
        assertNull(SpotifyJson.smallCover(null));
    }

    // --- links (artists and album) for the long-press ---

    private static void assertRef(String uri, String name, String cover, Item.Ref r) {
        assertEquals(uri, r.uri);
        assertEquals(name, r.name);
        assertEquals(cover, r.coverUrl);
    }

    @Test public void savedTracksKeepArtistsAndAlbum() {
        Item t = SpotifyJson.savedTracks(f("saved_tracks"), "potkc", 0).items.get(0);
        assertEquals(2, t.artists.size());
        assertRef("spotify:artist:R1", "Artist One", null, t.artists.get(0));
        assertRef("spotify:artist:R2", "Artist Two", null, t.artists.get(1));
        assertRef("spotify:album:A3", "Album Three", "https://i.example/a3-64", t.album);
    }

    @Test public void albumTracksUseThePagesAlbum() {
        Item t = SpotifyJson.albumTracks(f("album_tracks"), "spotify:album:A", "http://cover", 0).items.get(1);
        assertRef("spotify:album:A", null, "http://cover", t.album);
        assertEquals(2, t.artists.size());
        assertRef("spotify:artist:R1", "Artist One", null, t.artists.get(0));
    }

    @Test public void playlistTracksKeepArtistsAndAlbum() {
        Item t = SpotifyJson.playlistTracks(f("playlist_items"), "spotify:playlist:P", 0).items.get(0);
        assertRef("spotify:artist:R5", "Artist Five", null, t.artists.get(0));
        assertRef("spotify:album:A5", "Album Five", "https://i.example/a5-64", t.album);
    }

    @Test public void recentAndSearchTracksKeepTheirAlbum() {
        Item r = SpotifyJson.recent(f("recent")).items.get(0);
        assertNotNull(r.album);
        assertTrue(r.album.uri.startsWith("spotify:album:"));
        assertFalse(r.artists.isEmpty());
        SpotifyJson.SearchResult s = SpotifyJson.search(f("search"));
        assertEquals("spotify:album:S1", s.tracks.get(0).album.uri);
        assertNull(s.tracks.get(1).album); // no album
    }

    @Test public void albumsKeepTheirArtists() {
        Item a = SpotifyJson.savedAlbums(f("saved_albums"), 0).items.get(0);
        assertEquals(2, a.artists.size());
        assertEquals("Artist One", a.artists.get(0).name);
        assertNull(a.album);
        Item s = SpotifyJson.search(f("search")).albums.get(0);
        assertRef("spotify:artist:R9", "Artist Nine", null, s.artists.get(0));
        assertFalse(SpotifyJson.artistAlbums(f("artist_albums"), 0).items.get(0).artists.isEmpty());
    }

    @Test public void artistsWithoutAUriAreSkipped() throws Exception {
        JSONObject j = new JSONObject("{\"items\":[{\"track\":{\"uri\":\"spotify:track:X\",\"name\":\"X\","
                + "\"artists\":[{\"name\":\"No Uri\"},null,{\"uri\":\"spotify:artist:Y\",\"name\":null}],"
                + "\"album\":{\"name\":\"No Uri\"}}}]}");
        Item t = SpotifyJson.savedTracks(j, "u", 0).items.get(0);
        assertEquals(1, t.artists.size());
        assertRef("spotify:artist:Y", null, null, t.artists.get(0));
        assertEquals("No Uri", t.subtitle);
        assertNull(t.album);
    }

    @Test public void playlistsAndArtistsHaveNoLinks() {
        assertTrue(SpotifyJson.playlists(f("playlists"), 0).items.get(0).artists.isEmpty());
        assertNull(SpotifyJson.playlists(f("playlists"), 0).items.get(0).album);
    }
}
