package jp.tkc.st15iplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ArtworkLoaderTest {
    private File dir;
    private final List<Runnable> background = new ArrayList<Runnable>();
    private final List<String> fetched = new ArrayList<String>();
    private final List<String> got = new ArrayList<String>();
    private final List<String> failUrls = new ArrayList<String>();
    private ArtworkLoader<String> loader;

    private final Executor bg = new Executor() {
        @Override public void execute(Runnable r) { background.add(r); }
    };
    private final Executor direct = new Executor() {
        @Override public void execute(Runnable r) { r.run(); }
    };

    private final ArtworkLoader.Fetcher fetcher = new ArtworkLoader.Fetcher() {
        @Override public void fetch(String url, File out) throws IOException {
            fetched.add(url);
            if (failUrls.contains(url)) throw new IOException("404");
            write(out, url, 100);
        }
    };
    private final ArtworkLoader.Decoder<String> decoder = new ArtworkLoader.Decoder<String>() {
        @Override public String decode(File f, int size) throws IOException {
            return "img(" + f.getName().substring(0, 6) + "@" + size + ")";
        }
    };

    private ArtworkLoader.Callback<String> cb(final String name) {
        return new ArtworkLoader.Callback<String>() {
            @Override public void onArtwork(String url, String image) {
                got.add(name + "=" + (image == null ? "null:" + url : url));
            }
        };
    }

    private static void write(File f, String content, int bytes) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try {
            byte[] b = new byte[bytes];
            byte[] c = content.getBytes("UTF-8");
            System.arraycopy(c, 0, b, 0, Math.min(c.length, bytes));
            o.write(b);
        } finally {
            o.close();
        }
    }

    private void runBackground() {
        while (!background.isEmpty()) background.remove(0).run();
    }

    @Before public void setUp() throws IOException {
        dir = File.createTempFile("art", "");
        assertTrue(dir.delete());
        loader = new ArtworkLoader<String>(dir, 1000, 60000, fetcher, decoder, bg, direct);
    }

    @After public void tearDown() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.delete();
    }

    @Test public void keyIsSha1Hex() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", ArtworkLoader.key("abc"));
    }

    @Test public void sampleSizeKeepsAtLeastTheTarget() {
        assertEquals(1, ArtworkLoader.sampleSize(300, 300, 240));
        assertEquals(2, ArtworkLoader.sampleSize(640, 640, 240));
        assertEquals(4, ArtworkLoader.sampleSize(640, 640, 150));
        assertEquals(2, ArtworkLoader.sampleSize(640, 500, 240)); // the smaller side decides
        assertEquals(1, ArtworkLoader.sampleSize(100, 100, 240));
        assertEquals(1, ArtworkLoader.sampleSize(0, 0, 240));
    }

    @Test public void loadsThroughDiskAndMemory() {
        loader.load("view", "u1", 240, cb("view"));
        assertTrue(got.isEmpty());
        runBackground();
        assertEquals("[view=u1]", got.toString());
        assertEquals("[u1]", fetched.toString());
        assertTrue(new File(dir, ArtworkLoader.key("u1") + ".jpg").isFile());
        assertEquals("img(" + ArtworkLoader.key("u1").substring(0, 6) + "@240)", loader.get("u1", 240));
        assertNull(loader.get("u1", 48)); // another size is another entry
        // A memory hit calls back at once.
        loader.load("view", "u1", 240, cb("again"));
        assertEquals("[view=u1, again=u1]", got.toString());
        // Another size decodes from disk without fetching.
        loader.load("thumb", "u1", 48, cb("thumb"));
        runBackground();
        assertEquals("[u1]", fetched.toString());
        assertEquals("[view=u1, again=u1, thumb=u1]", got.toString());
    }

    @Test public void deduplicatesRequestsInFlight() {
        loader.load("a", "u1", 240, cb("a"));
        loader.load("b", "u1", 240, cb("b"));
        assertEquals(1, background.size());
        runBackground();
        assertEquals("[u1]", fetched.toString());
        assertEquals("[a=u1, b=u1]", got.toString());
    }

    @Test public void aNewerRequestForTheSameTargetWins() {
        loader.load("view", "old", 240, cb("view"));
        loader.load("view", "new", 240, cb("view"));
        // The new one finishes first, then the old one: the old must not replace it.
        Runnable oldJob = background.remove(0);
        background.remove(0).run();
        oldJob.run();
        assertEquals("[view=new]", got.toString());
        assertTrue(loader.get("old", 240) != null); // still cached for later
    }

    @Test public void cancelDropsTheCallback() {
        loader.load("view", "u1", 240, cb("view"));
        loader.cancel("view");
        runBackground();
        assertTrue(got.isEmpty());
    }

    @Test public void failuresAreReportedAndNotRetriedAtOnce() {
        failUrls.add("bad");
        loader.load("view", "bad", 240, cb("view"));
        runBackground();
        assertEquals("[view=null:bad]", got.toString());
        loader.load("view", "bad", 240, cb("view"));
        assertTrue(background.isEmpty());
        assertEquals("[view=null:bad, view=null:bad]", got.toString());
        assertEquals("[bad]", fetched.toString());
    }

    @Test public void failuresAreRetriedAfterTheBackoff() {
        loader = new ArtworkLoader<String>(dir, 1000, 0, fetcher, decoder, bg, direct);
        failUrls.add("bad");
        loader.load("view", "bad", 240, cb("view"));
        runBackground();
        loader.load("view", "bad", 240, cb("view"));
        runBackground();
        assertEquals("[bad, bad]", fetched.toString());
    }

    @Test public void undecodableFilesAreDeleted() {
        ArtworkLoader.Decoder<String> broken = new ArtworkLoader.Decoder<String>() {
            @Override public String decode(File f, int size) throws IOException {
                throw new IOException("not an image");
            }
        };
        loader = new ArtworkLoader<String>(dir, 1000, 60000, fetcher, broken, bg, direct);
        loader.load("view", "u1", 240, cb("view"));
        runBackground();
        assertEquals("[view=null:u1]", got.toString());
        assertFalse(new File(dir, ArtworkLoader.key("u1") + ".jpg").exists());
    }

    @Test public void memoryKeepsTheTwentyMostRecent() {
        for (int i = 0; i < ArtworkLoader.MEMORY_ENTRIES + 1; i++) {
            loader.load("v", "u" + i, 48, cb("v"));
            runBackground();
            if (i == 5) loader.get("u0", 48); // touch: u0 is now recent
        }
        assertTrue(loader.get("u0", 48) != null);
        assertNull(loader.get("u1", 48));
        assertTrue(loader.get("u2", 48) != null);
    }

    @Test public void trimDeletesTheOldestBeyondTheCapAndStaleParts() throws IOException {
        assertTrue(dir.mkdirs());
        long now = 100000000L;
        File a = new File(dir, "a.jpg"), b = new File(dir, "b.jpg"), c = new File(dir, "c.jpg");
        File stalePart = new File(dir, ".c.jpg.1.part"), freshPart = new File(dir, ".d.jpg.2.part");
        write(a, "a", 400);
        write(b, "b", 400);
        write(c, "c", 400);
        write(stalePart, "p", 10);
        write(freshPart, "p", 10);
        assertTrue(a.setLastModified(now - 3000));
        assertTrue(b.setLastModified(now - 1000));
        assertTrue(c.setLastModified(now - 2000));
        assertTrue(stalePart.setLastModified(now - ArtworkLoader.STALE_PART_MS - 1000));
        assertTrue(freshPart.setLastModified(now - 1000));
        ArtworkLoader.trim(dir, 1000, now);
        assertFalse(a.exists());
        assertTrue(b.exists());
        assertTrue(c.exists());
        assertFalse(stalePart.exists());
        assertTrue(freshPart.exists());
        ArtworkLoader.trim(dir, 500, now);
        assertFalse(c.exists());
        assertTrue(b.exists());
    }
}
