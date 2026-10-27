package com.potkc.overhear.spotify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

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

    @Test public void othersPlaylistIsNotRestored() {
        // Reopening it would start playback; restore up to its parent instead.
        List<LibraryPath.Node> stack = Arrays.asList(TOP,
                LibraryPath.Node.of(LibraryPath.Node.Type.SEARCH),
                LibraryPath.Node.playlist("spotify:playlist:P", "Theirs", true),
                LibraryPath.Node.artist("spotify:artist:A", "After"));
        assertEquals(Arrays.asList(TOP, LibraryPath.Node.of(LibraryPath.Node.Type.SEARCH)),
                LibraryPath.decode(LibraryPath.encode(stack)));
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
}
