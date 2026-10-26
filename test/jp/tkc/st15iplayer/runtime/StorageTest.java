package jp.tkc.st15iplayer.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StorageTest {
    private static final long MB = 1024L * 1024L;

    @Test public void cacheIsAlwaysSoloistsMinimum() {
        assertEquals(100, Storage.cacheSizeMb(10 * MB));
        assertEquals(100, Storage.cacheSizeMb(160 * MB));
        assertEquals(100, Storage.cacheSizeMb(4000 * MB));
        assertEquals(100, Storage.cacheSizeMb(-1));
    }

    @Test public void lowForCacheWhenHalfTheFreeSpaceIsUnderTheCache() {
        assertTrue(Storage.isLowForCache(150 * MB));
        assertTrue(Storage.isLowForCache(200 * MB - 1));
        assertFalse(Storage.isLowForCache(200 * MB));
        assertFalse(Storage.isLowForCache(1000 * MB));
    }

    @Test public void downloadNeedsEightyMegabytes() {
        assertNull(Storage.downloadError(80 * MB));
        assertNull(Storage.downloadError(500 * MB));
        String msg = Storage.downloadError(80 * MB - 1);
        assertTrue(msg, msg.contains("79 MB free"));
        assertTrue(msg, msg.contains("80 MB"));
        assertTrue(Storage.downloadError(0).contains("0 MB free"));
    }
}
