package com.potkc.overhear.spotify;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ItemTest {
    private static Item item(Item.Kind kind, String uri) {
        return new Item(kind, uri, "t", "", null, null, null);
    }

    @Test public void trackPlaysNowByItsUri() {
        Item t = item(Item.Kind.TRACK, "spotify:track:T");
        assertTrue(t.playsNow("spotify:track:T", "spotify:album:A"));
        assertFalse(t.playsNow("spotify:track:U", "spotify:track:T"));
        assertFalse(t.playsNow(null, null));
    }

    @Test public void contextsPlayNowByTheContext() {
        assertTrue(item(Item.Kind.ALBUM, "spotify:album:A").playsNow("spotify:track:T", "spotify:album:A"));
        assertTrue(item(Item.Kind.PLAYLIST, "spotify:playlist:P").playsNow(null, "spotify:playlist:P"));
        assertTrue(item(Item.Kind.ARTIST, "spotify:artist:R").playsNow(null, "spotify:artist:R"));
        assertFalse(item(Item.Kind.ALBUM, "spotify:album:A").playsNow("spotify:album:A", "spotify:album:B"));
        assertFalse(item(Item.Kind.PLAYLIST, "spotify:playlist:P").playsNow(null, null));
    }
}
