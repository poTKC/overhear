package com.potkc.overhear.spotify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class LibraryPathTest {
    private static final LibraryPath.Node TOP = LibraryPath.Node.top();

    @Test public void roundTrip() {
        List<LibraryPath.Node> stack = Arrays.asList(
                LibraryPath.Node.top(),
                LibraryPath.Node.of(LibraryPath.Node.Type.ARTISTS),
                LibraryPath.Node.artist("spotify:artist:A", "Daft Punk"),
                LibraryPath.Node.album("spotify:album:B", "Random Access Memories", "https://i.scdn.co/x"));
        assertEquals(stack, LibraryPath.decode(LibraryPath.encode(stack)));
    }

    @Test public void titlesWithSeparatorsSurvive() {
        List<LibraryPath.Node> stack = Arrays.asList(TOP,
                LibraryPath.Node.playlist("spotify:playlist:P", "a|b\nc ~%", false));
        assertEquals(stack, LibraryPath.decode(LibraryPath.encode(stack)));
    }

    @Test public void nullAndEmptyValuesStayApart() {
        List<LibraryPath.Node> stack = Arrays.asList(TOP,
                LibraryPath.Node.album("spotify:album:B", "", null),
                LibraryPath.Node.playlist("spotify:playlist:P", null, false));
        List<LibraryPath.Node> back = LibraryPath.decode(LibraryPath.encode(stack));
        assertEquals(stack, back);
        assertEquals("", back.get(1).title);
        assertNull(back.get(1).coverUrl);
        assertNull(back.get(2).title);
    }

    @Test public void playlistFactoryTellsOwnFromOthers() {
        assertEquals(LibraryPath.Node.Type.PLAYLIST, LibraryPath.Node.playlist("spotify:playlist:P", "t", false).type);
        assertEquals(LibraryPath.Node.Type.OTHERS_PLAYLIST,
                LibraryPath.Node.playlist("spotify:playlist:P", "t", true).type);
        assertFalse(LibraryPath.Node.playlist("spotify:playlist:P", "t", false)
                .equals(LibraryPath.Node.playlist("spotify:playlist:P", "t", true)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void ofRejectsTypesWithArguments() {
        LibraryPath.Node.of(LibraryPath.Node.Type.ALBUM);
    }

    @Test public void garbageGivesJustTheTop() {
        assertEquals(Collections.singletonList(TOP), LibraryPath.decode("nonsense|||"));
        assertEquals(Collections.singletonList(TOP), LibraryPath.decode(null));
        assertEquals(Collections.singletonList(TOP), LibraryPath.decode(""));
        assertEquals(Collections.singletonList(TOP), LibraryPath.decode("ALBUM|%zz|t|~"));
    }

    @Test public void anUnreadableLineDropsItAndWhatFollows() {
        String s = LibraryPath.encode(Arrays.asList(TOP, LibraryPath.Node.of(LibraryPath.Node.Type.ALBUMS)))
                + "\nBOGUS|~|~|~\n" + LibraryPath.encode(Collections.singletonList(
                        LibraryPath.Node.album("spotify:album:B", "t", null)));
        assertEquals(Arrays.asList(TOP, LibraryPath.Node.of(LibraryPath.Node.Type.ALBUMS)), LibraryPath.decode(s));
    }

    @Test public void anArgumentNodeWithoutAUriIsUnreadable() {
        assertEquals(Collections.singletonList(TOP), LibraryPath.decode("ALBUM|~|t|~"));
    }

    @Test public void theTopIsAlwaysFirst() {
        List<LibraryPath.Node> albums = Collections.singletonList(LibraryPath.Node.of(LibraryPath.Node.Type.ALBUMS));
        assertEquals(Arrays.asList(TOP, LibraryPath.Node.of(LibraryPath.Node.Type.ALBUMS)),
                LibraryPath.decode(LibraryPath.encode(albums)));
    }

    @Test public void othersPlaylistIsRestored() {
        // Opening it no longer plays it.
        List<LibraryPath.Node> stack = Arrays.asList(TOP,
                LibraryPath.Node.of(LibraryPath.Node.Type.SEARCH),
                LibraryPath.Node.playlist("spotify:playlist:P", "Theirs", true));
        assertEquals(stack, LibraryPath.decode(LibraryPath.encode(stack)));
    }

    @Test public void aTitleOfJustTheNullMarkerSurvives() {
        List<LibraryPath.Node> stack = Arrays.asList(TOP, LibraryPath.Node.artist("spotify:artist:A", "~"));
        List<LibraryPath.Node> back = LibraryPath.decode(LibraryPath.encode(stack));
        assertEquals(stack, back);
        assertEquals("~", back.get(1).title);
    }

    @Test public void aTrailingNewlineIsFine() {
        List<LibraryPath.Node> stack = Arrays.asList(TOP, LibraryPath.Node.of(LibraryPath.Node.Type.LIKED));
        assertEquals(stack, LibraryPath.decode(LibraryPath.encode(stack) + "\n"));
    }

    @Test public void aDeepStackIsCapped() {
        List<LibraryPath.Node> stack = new ArrayList<LibraryPath.Node>();
        stack.add(TOP);
        for (int i = 0; i < 40; i++) stack.add(LibraryPath.Node.artist("spotify:artist:" + i, "A" + i));
        List<LibraryPath.Node> back = LibraryPath.decode(LibraryPath.encode(stack));
        assertEquals(LibraryPath.MAX_DEPTH, back.size());
        assertEquals(stack.subList(0, LibraryPath.MAX_DEPTH), back);
    }

    @Test(expected = IllegalArgumentException.class)
    public void albumNeedsAUri() {
        LibraryPath.Node.album(null, "t", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void playlistNeedsAUri() {
        LibraryPath.Node.playlist("", "t", false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void artistNeedsAUri() {
        LibraryPath.Node.artist("", "t");
    }

    // --- atContext ---

    private static final LibraryPath.Node ALBUMS = LibraryPath.Node.of(LibraryPath.Node.Type.ALBUMS);
    private static final LibraryPath.Node PLAYLISTS = LibraryPath.Node.of(LibraryPath.Node.Type.PLAYLISTS);
    private static final LibraryPath.Node ARTISTS = LibraryPath.Node.of(LibraryPath.Node.Type.ARTISTS);
    private static final LibraryPath.Node LIKED = LibraryPath.Node.of(LibraryPath.Node.Type.LIKED);

    private static List<LibraryPath.Node> saved() {
        return new ArrayList<LibraryPath.Node>(Arrays.asList(TOP, LibraryPath.Node.of(LibraryPath.Node.Type.RECENT)));
    }

    @Test public void albumOpensUnderAlbums() {
        assertEquals(Arrays.asList(TOP, ALBUMS,
                        LibraryPath.Node.album("spotify:album:A", "Discovery", "https://i.scdn.co/s")),
                LibraryPath.atContext(saved(), "spotify:album:A", "Discovery", "https://i.scdn.co/s", null));
    }

    @Test public void playlistOpensUnderPlaylistsAsOwnUnlessKnownOthers() {
        assertEquals(Arrays.asList(TOP, PLAYLISTS, LibraryPath.Node.playlist("spotify:playlist:P", "Mix", false)),
                LibraryPath.atContext(saved(), "spotify:playlist:P", "Mix", "https://c", null));
        assertEquals(Arrays.asList(TOP, PLAYLISTS, LibraryPath.Node.playlist("spotify:playlist:P", "Mix", false)),
                LibraryPath.atContext(saved(), "spotify:playlist:P", "Mix", null, Boolean.FALSE));
        assertEquals(Arrays.asList(TOP, PLAYLISTS, LibraryPath.Node.playlist("spotify:playlist:P", "Mix", true)),
                LibraryPath.atContext(saved(), "spotify:playlist:P", "Mix", null, Boolean.TRUE));
    }

    @Test public void likedSongsOpenRightUnderTop() {
        assertEquals(Arrays.asList(TOP, LIKED),
                LibraryPath.atContext(saved(), "spotify:user:someone:collection", "Liked Songs", null, null));
    }

    @Test public void artistOpensUnderArtists() {
        assertEquals(Arrays.asList(TOP, ARTISTS, LibraryPath.Node.artist("spotify:artist:R", "Daft Punk")),
                LibraryPath.atContext(saved(), "spotify:artist:R", "Daft Punk", "https://c", null));
    }

    @Test public void otherContextsKeepTheSavedStack() {
        String[] uris = {null, "", "spotify", "spotify:album:", "spotify:album:A:x", "spotify:station:track:T",
                "spotify:track:T", "spotify:show:S", "spotify:user:u:playlist:P", "spotify:user::collection",
                "spotify:user:u:collection:x", "spotify:collection:tracks", "spotify:internal:radio",
                "spotify:playlist", "spotify:artist:", "http://open.spotify.com/album/A"};
        for (String uri : uris) {
            List<LibraryPath.Node> s = saved();
            assertSame(uri, s, LibraryPath.atContext(s, uri, "x", null, null));
        }
    }

    @Test public void savedStackEndingThereStays() {
        List<LibraryPath.Node> s = new ArrayList<LibraryPath.Node>(Arrays.asList(TOP, ARTISTS,
                LibraryPath.Node.artist("spotify:artist:R", "Daft Punk"),
                LibraryPath.Node.album("spotify:album:A", "Discovery (from the API)", "https://i.scdn.co/api")));
        // The title and cover may differ from what Soloist says: type and uri decide.
        assertSame(s, LibraryPath.atContext(s, "spotify:album:A", "Discovery", "https://i.scdn.co/soloist", null));
        List<LibraryPath.Node> liked = new ArrayList<LibraryPath.Node>(Arrays.asList(TOP, PLAYLISTS, LIKED));
        assertSame(liked, LibraryPath.atContext(liked, "spotify:user:me:collection", null, null, null));
    }

    @Test public void savedStackBelowThatPageIsReplaced() {
        List<LibraryPath.Node> s = new ArrayList<LibraryPath.Node>(Arrays.asList(TOP, ARTISTS,
                LibraryPath.Node.artist("spotify:artist:R", "Daft Punk"),
                LibraryPath.Node.album("spotify:album:A", "Discovery", null)));
        assertEquals(Arrays.asList(TOP, ARTISTS, LibraryPath.Node.artist("spotify:artist:R", "Daft Punk")),
                LibraryPath.atContext(s, "spotify:artist:R", "Daft Punk", null, null));
        assertEquals(Arrays.asList(TOP, ALBUMS, LibraryPath.Node.album("spotify:album:B", "Homework", null)),
                LibraryPath.atContext(s, "spotify:album:B", "Homework", null, null));
    }

    @Test public void eitherPlaylistPageStaysWhileOwnershipIsUnknown() {
        LibraryPath.Node others = LibraryPath.Node.playlist("spotify:playlist:P", "Mix", true);
        LibraryPath.Node own = LibraryPath.Node.playlist("spotify:playlist:P", "Mix", false);
        List<LibraryPath.Node> s = new ArrayList<LibraryPath.Node>(Arrays.asList(TOP, PLAYLISTS, others));
        assertSame(s, LibraryPath.atContext(s, "spotify:playlist:P", "Mix", null, null));
        assertSame(s, LibraryPath.atContext(s, "spotify:playlist:P", "Mix", null, Boolean.TRUE));
        assertEquals(Arrays.asList(TOP, PLAYLISTS, own),
                LibraryPath.atContext(s, "spotify:playlist:P", "Mix", null, Boolean.FALSE));
        List<LibraryPath.Node> o = new ArrayList<LibraryPath.Node>(Arrays.asList(TOP, PLAYLISTS, own));
        assertSame(o, LibraryPath.atContext(o, "spotify:playlist:P", "Mix", null, null));
        assertEquals(Arrays.asList(TOP, PLAYLISTS, others),
                LibraryPath.atContext(o, "spotify:playlist:P", "Mix", null, Boolean.TRUE));
        // Another playlist, or an album with the same ID, is another page.
        assertEquals(Arrays.asList(TOP, PLAYLISTS, LibraryPath.Node.playlist("spotify:playlist:Q", "Q", false)),
                LibraryPath.atContext(s, "spotify:playlist:Q", "Q", null, null));
    }

    @Test public void topAloneOpensThePage() {
        List<LibraryPath.Node> s = new ArrayList<LibraryPath.Node>(Collections.singletonList(TOP));
        assertEquals(Arrays.asList(TOP, LIKED),
                LibraryPath.atContext(s, "spotify:user:me:collection", null, null, null));
    }

    @Test public void contextStackRoundTrips() {
        List<LibraryPath.Node> s = LibraryPath.atContext(saved(), "spotify:album:A", null, null, null);
        assertEquals(s, LibraryPath.decode(LibraryPath.encode(s)));
    }

    @Test public void replaceSwapsEveryMatch() {
        LibraryPath.Node own = LibraryPath.Node.playlist("spotify:playlist:P", "Mix", false);
        LibraryPath.Node others = LibraryPath.Node.playlist("spotify:playlist:P", "Mix", true);
        List<LibraryPath.Node> s = new ArrayList<LibraryPath.Node>(Arrays.asList(TOP, PLAYLISTS, own));
        assertTrue(LibraryPath.replace(s, own, others));
        assertEquals(Arrays.asList(TOP, PLAYLISTS, others), s);
        assertFalse(LibraryPath.replace(s, own, others));
        assertEquals(Arrays.asList(TOP, PLAYLISTS, others), s);
    }

    @Test public void replaceGoesByTypeAndUri() {
        LibraryPath.Node untitled = LibraryPath.Node.playlist("spotify:playlist:P", null, false);
        LibraryPath.Node others = LibraryPath.Node.playlist("spotify:playlist:P", "Mix", true);
        List<LibraryPath.Node> s = new ArrayList<LibraryPath.Node>(Arrays.asList(TOP, PLAYLISTS,
                LibraryPath.Node.playlist("spotify:playlist:P", "Mix", false)));
        assertTrue(LibraryPath.replace(s, untitled, others));
        assertEquals(Arrays.asList(TOP, PLAYLISTS, others), s);
    }

    @Test public void emptyNameCountsAsNone() {
        assertEquals(Arrays.asList(TOP, ARTISTS, LibraryPath.Node.artist("spotify:artist:R", null)),
                LibraryPath.atContext(saved(), "spotify:artist:R", "", null, null));
    }

    @Test public void namedKeepsThePage() {
        LibraryPath.Node a = LibraryPath.Node.album("spotify:album:A", null, null);
        LibraryPath.Node named = a.named("Discovery", "https://c");
        assertTrue(a.samePage(named));
        assertEquals(LibraryPath.Node.album("spotify:album:A", "Discovery", "https://c"), named);
        assertNull(LibraryPath.Node.artist("spotify:artist:R", null).named("x", "https://c").coverUrl);
        assertFalse(a.samePage(LibraryPath.Node.album("spotify:album:B", null, null)));
    }

    @Test public void likedOwnerReadsTheUserId() {
        assertEquals("potkc", LibraryPath.likedOwner("spotify:user:potkc:collection"));
        assertNull(LibraryPath.likedOwner("spotify:user::collection"));
        assertNull(LibraryPath.likedOwner("spotify:user:potkc:playlist"));
        assertNull(LibraryPath.likedOwner("spotify:collection:tracks"));
        assertNull(LibraryPath.likedOwner(null));
    }

    @Test public void savedUntitledPageTakesTheName() {
        List<LibraryPath.Node> s = new ArrayList<LibraryPath.Node>(Arrays.asList(TOP, ALBUMS,
                LibraryPath.Node.album("spotify:album:A", null, null)));
        assertEquals(Arrays.asList(TOP, ALBUMS, LibraryPath.Node.album("spotify:album:A", "Discovery", "https://c")),
                LibraryPath.atContext(s, "spotify:album:A", "Discovery", "https://c", null));
        assertSame(s, LibraryPath.atContext(s, "spotify:album:A", "", "https://c", null));
        List<LibraryPath.Node> t = new ArrayList<LibraryPath.Node>(Arrays.asList(TOP, PLAYLISTS,
                LibraryPath.Node.playlist("spotify:playlist:P", "", true)));
        assertEquals(Arrays.asList(TOP, PLAYLISTS, LibraryPath.Node.playlist("spotify:playlist:P", "Mix", true)),
                LibraryPath.atContext(t, "spotify:playlist:P", "Mix", null, null));
    }

    @Test public void knownTitleStays() {
        List<LibraryPath.Node> s = new ArrayList<LibraryPath.Node>(Arrays.asList(TOP, ARTISTS,
                LibraryPath.Node.artist("spotify:artist:R", "Daft Punk")));
        assertSame(s, LibraryPath.atContext(s, "spotify:artist:R", null, null, null));
        assertSame(s, LibraryPath.atContext(s, "spotify:artist:R", "Other", null, null));
    }
}
