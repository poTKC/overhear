package com.potkc.overhear;

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
    private final List<Runnable> urgent = new ArrayList<Runnable>();
    private final List<String> fetched = new ArrayList<String>();
    private final List<String> got = new ArrayList<String>();
    private final List<String> failUrls = new ArrayList<String>();
    private ArtworkLoader<String> loader;

    private final Executor bg = new Executor() {
        @Override public void execute(Runnable r) { background.add(r); }
    };
    private final Executor urgentBg = new Executor() {
        @Override public void execute(Runnable r) { urgent.add(r); }
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
        loader = new ArtworkLoader<String>(dir, 1000, 60000, fetcher, decoder, bg, urgentBg, direct);
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
        loader.load("view", "u1", 240, false, cb("view"));
        assertTrue(got.isEmpty());
        runBackground();
        assertEquals("[view=u1]", got.toString());
        assertEquals("[u1]", fetched.toString());
        assertTrue(new File(dir, ArtworkLoader.key("u1") + ".jpg").isFile());
        assertEquals("img(" + ArtworkLoader.key("u1").substring(0, 6) + "@240)", loader.get("u1", 240));
        assertNull(loader.get("u1", 48)); // another size is another entry
        // A memory hit calls back at once.
        loader.load("view", "u1", 240, false, cb("again"));
        assertEquals("[view=u1, again=u1]", got.toString());
        // Another size decodes from disk without fetching.
        loader.load("thumb", "u1", 48, false, cb("thumb"));
        runBackground();
        assertEquals("[u1]", fetched.toString());
        assertEquals("[view=u1, again=u1, thumb=u1]", got.toString());
    }

    @Test public void deduplicatesRequestsInFlight() {
        loader.load("a", "u1", 240, false, cb("a"));
        loader.load("b", "u1", 240, false, cb("b"));
        assertEquals(1, background.size());
        runBackground();
        assertEquals("[u1]", fetched.toString());
        assertEquals("[a=u1, b=u1]", got.toString());
    }

    @Test public void aNewerRequestForTheSameTargetWins() {
        loader.load("view", "old", 240, false, cb("view"));
        loader.load("view", "new", 240, false, cb("view"));
        // The new one finishes first, then the old one: the old must not replace it.
        Runnable oldJob = background.remove(0);
        background.remove(0).run();
        oldJob.run();
        assertEquals("[view=new]", got.toString());
        assertEquals("[new]", fetched.toString()); // nobody wanted "old" any more: skipped
    }

    private ArtworkLoader<String> throwingLoader(final Error error) {
        ArtworkLoader.Decoder<String> d = new ArtworkLoader.Decoder<String>() {
            @Override public String decode(File f, int size) throws IOException {
                throw error;
            }
        };
        return new ArtworkLoader<String>(dir, 1000, 0, fetcher, d, bg, urgentBg, direct);
    }

    @Test public void outOfMemoryIsAFailureNotAWedgedJob() {
        loader = throwingLoader(new OutOfMemoryError("bitmap"));
        loader.load("view", "u1", 240, false, cb("view"));
        runBackground();
        assertEquals("[view=null:u1]", got.toString());
        loader.load("view", "u1", 240, false, cb("view")); // not stuck in flight
        assertEquals(1, background.size());
    }

    @Test public void anyErrorStillFinishesTheJob() {
        loader = throwingLoader(new StackOverflowError());
        loader.load("view", "u1", 240, false, cb("view"));
        try {
            runBackground();
        } catch (StackOverflowError expected) {
            // rethrown to the executor after the job was finished
        }
        assertEquals("[view=null:u1]", got.toString());
        loader.load("view", "u1", 240, false, cb("view"));
        assertEquals(1, background.size());
    }

    @Test public void aCancelledJobDoesNotFetch() {
        loader.load("view", "u1", 240, false, cb("view"));
        loader.cancel("view");
        runBackground();
        assertTrue(fetched.isEmpty());
        assertTrue(got.isEmpty());
        // Not remembered as a failure: asking again fetches.
        loader.load("view", "u1", 240, false, cb("view"));
        runBackground();
        assertEquals("[u1]", fetched.toString());
        assertEquals("[view=u1]", got.toString());
    }

    /** Targets named "lib:..." belong to one screen. */
    private static final ArtworkLoader.TargetFilter LIBRARY = new ArtworkLoader.TargetFilter() {
        @Override public boolean matches(Object target) {
            return ((String) target).startsWith("lib:");
        }
    };

    @Test public void cancelAllDropsWhatTheFilterMatches() {
        loader.load("lib:a", "u1", 48, false, cb("a"));
        loader.load("lib:b", "u2", 48, false, cb("b"));
        loader.cancelAll(LIBRARY);
        runBackground();
        assertTrue(fetched.isEmpty());
        assertTrue(got.isEmpty());
    }

    @Test public void cancelAllLeavesAnotherScreensRequest() {
        // The library closes just after the player asked for the new cover.
        loader.load("lib:thumb", "u1", 48, false, cb("thumb"));
        loader.load("player:art", "u2", 240, true, cb("art"));
        loader.cancelAll(LIBRARY);
        runBackground();
        while (!urgent.isEmpty()) urgent.remove(0).run();
        assertEquals("[u2]", fetched.toString());
        assertEquals("[art=u2]", got.toString());
    }

    @Test public void reRequestAfterCancelWhileInFlightIsAnsweredOnce() {
        loader.load("view", "u1", 240, false, cb("first"));
        loader.cancel("view");
        loader.load("view", "u1", 240, false, cb("second"));
        assertEquals(1, background.size()); // shares the job in flight
        runBackground();
        assertEquals("[u1]", fetched.toString());
        assertEquals("[second=u1]", got.toString());
    }

    @Test public void reRequestAfterTheJobDecidedToSkipStillLoads() {
        final List<Runnable> mainQueue = new ArrayList<Runnable>();
        Executor queued = new Executor() {
            @Override public void execute(Runnable r) { mainQueue.add(r); }
        };
        loader = new ArtworkLoader<String>(dir, 1000, 60000, fetcher, decoder, bg, urgentBg, queued);
        loader.load("view", "u1", 240, false, cb("view"));
        loader.cancel("view");
        background.remove(0).run(); // nobody wants u1: skips, posts that to main
        assertTrue(fetched.isEmpty());
        loader.load("view", "u1", 240, false, cb("view")); // before the skip reaches main
        assertTrue(background.isEmpty()); // joined the job in flight
        while (!mainQueue.isEmpty()) mainQueue.remove(0).run(); // the skip: starts it again
        runBackground();
        while (!mainQueue.isEmpty()) mainQueue.remove(0).run();
        assertEquals("[u1]", fetched.toString());
        assertEquals("[view=u1]", got.toString());
    }

    @Test public void urgentRequestsUseTheirOwnExecutor() {
        loader.load("art", "u1", 240, true, cb("art"));
        loader.load("thumb", "u2", 48, false, cb("thumb"));
        assertEquals(1, urgent.size());
        assertEquals(1, background.size());
        urgent.remove(0).run();
        assertEquals("[art=u1]", got.toString());
    }

    @Test public void cancelDropsTheCallback() {
        loader.load("view", "u1", 240, false, cb("view"));
        loader.cancel("view");
        runBackground();
        assertTrue(got.isEmpty());
    }

    @Test public void failuresAreReportedAndNotRetriedAtOnce() {
        failUrls.add("bad");
        loader.load("view", "bad", 240, false, cb("view"));
        runBackground();
        assertEquals("[view=null:bad]", got.toString());
        loader.load("view", "bad", 240, false, cb("view"));
        assertTrue(background.isEmpty());
        assertEquals("[view=null:bad, view=null:bad]", got.toString());
        assertEquals("[bad]", fetched.toString());
    }

    @Test public void failuresAreRetriedAfterTheBackoff() {
        loader = new ArtworkLoader<String>(dir, 1000, 0, fetcher, decoder, bg, urgentBg, direct);
        failUrls.add("bad");
        loader.load("view", "bad", 240, false, cb("view"));
        runBackground();
        loader.load("view", "bad", 240, false, cb("view"));
        runBackground();
        assertEquals("[bad, bad]", fetched.toString());
    }

    @Test public void undecodableFilesAreDeleted() {
        ArtworkLoader.Decoder<String> broken = new ArtworkLoader.Decoder<String>() {
            @Override public String decode(File f, int size) throws IOException {
                throw new IOException("not an image");
            }
        };
        loader = new ArtworkLoader<String>(dir, 1000, 60000, fetcher, broken, bg, urgentBg, direct);
        loader.load("view", "u1", 240, false, cb("view"));
        runBackground();
        assertEquals("[view=null:u1]", got.toString());
        assertFalse(new File(dir, ArtworkLoader.key("u1") + ".jpg").exists());
    }

    @Test public void memoryKeepsTheTwentyMostRecent() {
        for (int i = 0; i < ArtworkLoader.MEMORY_ENTRIES + 1; i++) {
            loader.load("v", "u" + i, 48, false, cb("v"));
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
