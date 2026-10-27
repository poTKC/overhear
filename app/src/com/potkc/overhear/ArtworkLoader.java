package com.potkc.overhear;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Loads cover art: a disk cache of fetched files (dir/&lt;sha1(url)&gt;.jpg,
 * capped at maxDiskBytes, oldest mtime first) and a memory LRU of
 * MEMORY_ENTRIES decoded images per (url, size). Pure Java (host tests):
 * the fetch (overhear-fetch on the device) and decode (BitmapFactory) are
 * plugged in.
 *
 * Every request names a target (e.g. an ImageView); only the latest request
 * of a target is answered, so an old cover that finishes late never replaces
 * a newer one. Requests for the same image in flight share one job; a job
 * whose image nobody wants any more (cancelled or superseded before it
 * started) skips its fetch; a failed URL is not tried again for
 * failureRetryMs. Urgent requests (the main cover) run on their own
 * executor so they never queue behind thumbnails.
 *
 * load, cancel and get run on the main thread, and callbacks arrive there
 * (through the main executor); fetches and decodes run on the background
 * executors. Every job reports back, even if it throws an Error.
 */
final class ArtworkLoader<B> {
    static final int MEMORY_ENTRIES = 20;
    /** Leftover overhear-fetch temp files (".name.*.part") older than this are deleted. */
    static final long STALE_PART_MS = 10 * 60 * 1000L;
    static final String SUFFIX = ".jpg";

    /** Downloads url to out (atomically). Background thread. */
    interface Fetcher {
        void fetch(String url, File out) throws IOException;
    }

    /** Decodes file for a view of sizePx square; throws if it is not an image. Background thread. */
    interface Decoder<B> {
        B decode(File file, int sizePx) throws IOException;
    }

    /** Picks the targets cancelAll forgets (one screen's views). */
    interface TargetFilter {
        boolean matches(Object target);
    }

    /** Main thread; image is null when the cover could not be loaded. */
    interface Callback<B> {
        void onArtwork(String url, B image);
    }

    /** A target's latest request. */
    private static final class Request<B> {
        final String key;
        final Callback<B> callback;

        Request(String key, Callback<B> callback) {
            this.key = key;
            this.callback = callback;
        }
    }

    private final File dir;
    private final long maxDiskBytes;
    private final long failureRetryMs;
    private final Fetcher fetcher;
    private final Decoder<B> decoder;
    private final Executor background;
    private final Executor urgentBackground;
    private final Executor main;
    private final Map<String, B> memory = new LinkedHashMap<String, B>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, B> eldest) {
            return size() > MEMORY_ENTRIES;
        }
    };
    /** Each target's latest request (main thread). */
    private final Map<Object, Request<B>> targets = new HashMap<Object, Request<B>>();
    /** Memory keys with a job queued or running (main thread). */
    private final Set<String> inFlight = new HashSet<String>();
    /** Memory keys some target waits for; read by jobs as they start. */
    private final Set<String> wanted = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** url -> when it last failed (nanoTime ms). */
    private final Map<String, Long> failures = new HashMap<String, Long>();

    ArtworkLoader(File dir, long maxDiskBytes, long failureRetryMs, Fetcher fetcher, Decoder<B> decoder,
            Executor background, Executor urgentBackground, Executor main) {
        this.dir = dir;
        this.maxDiskBytes = maxDiskBytes;
        this.failureRetryMs = failureRetryMs;
        this.fetcher = fetcher;
        this.decoder = decoder;
        this.background = background;
        this.urgentBackground = urgentBackground;
        this.main = main;
    }

    /** The decoded image if it is in memory (and marks it recently used), else null. */
    B get(String url, int sizePx) {
        return memory.get(memoryKey(url, sizePx));
    }

    /**
     * Loads url for target at sizePx; callback gets it unless target is
     * cancelled or asks for something else first. A memory hit (or a recent
     * failure) calls back before returning. urgent: the job skips the queue
     * of non-urgent ones.
     */
    void load(Object target, String url, int sizePx, boolean urgent, Callback<B> callback) {
        String key = memoryKey(url, sizePx);
        B hit = memory.get(key);
        if (hit != null) {
            forget(target);
            callback.onArtwork(url, hit);
            return;
        }
        Long failedAt = failures.get(url);
        if (failedAt != null && nowMs() - failedAt < failureRetryMs) {
            forget(target);
            callback.onArtwork(url, null);
            return;
        }
        targets.put(target, new Request<B>(key, callback));
        updateWanted();
        if (inFlight.add(key)) submit(key, url, sizePx, urgent);
    }

    /** Forgets target's pending request: its callback will not run. */
    void cancel(Object target) {
        forget(target);
    }

    /**
     * Forgets the pending requests of the targets filter matches (a screen
     * going away); jobs nobody else waits for skip their fetch if they have
     * not started. Only that screen's: the loader is shared, and the next
     * screen may already have asked for its cover (the closing one's
     * onDestroy comes after the next one's onResume).
     */
    void cancelAll(TargetFilter filter) {
        boolean changed = false;
        Iterator<Object> it = targets.keySet().iterator();
        while (it.hasNext()) {
            if (filter.matches(it.next())) {
                it.remove();
                changed = true;
            }
        }
        if (changed) updateWanted();
    }

    private void forget(Object target) {
        if (targets.remove(target) != null) updateWanted();
    }

    private void updateWanted() {
        Set<String> now = new HashSet<String>();
        for (Request<B> r : targets.values()) now.add(r.key);
        wanted.retainAll(now);
        wanted.addAll(now);
    }

    private void submit(final String key, final String url, final int sizePx, final boolean urgent) {
        (urgent ? urgentBackground : background).execute(new Runnable() {
            @Override public void run() {
                boolean skipped = !wanted.contains(key);
                B image = null;
                try {
                    if (!skipped) image = fetchAndDecode(url, sizePx);
                } finally {
                    // Also after an Error: a job left in flight would wedge this key.
                    final B result = image;
                    final boolean skip = skipped;
                    main.execute(new Runnable() {
                        @Override public void run() {
                            finished(key, url, sizePx, urgent, result, skip);
                        }
                    });
                }
            }
        });
    }

    private void finished(String key, String url, int sizePx, boolean urgent, B image, boolean skipped) {
        if (skipped) {
            // Somebody may have asked again after the job decided to skip.
            if (wanted.contains(key)) submit(key, url, sizePx, urgent);
            else inFlight.remove(key);
            return;
        }
        inFlight.remove(key);
        if (image != null) {
            memory.put(key, image);
            failures.remove(url);
        } else {
            failures.put(url, nowMs());
        }
        List<Callback<B>> callbacks = new ArrayList<Callback<B>>();
        Iterator<Map.Entry<Object, Request<B>>> it = targets.entrySet().iterator();
        while (it.hasNext()) {
            Request<B> r = it.next().getValue();
            if (!r.key.equals(key)) continue; // cancelled or superseded targets are not here
            callbacks.add(r.callback);
            it.remove();
        }
        updateWanted();
        for (Callback<B> c : callbacks) c.onArtwork(url, image);
    }

    /** Background: from disk, else fetched first; null on failure. */
    private B fetchAndDecode(String url, int sizePx) {
        File f = new File(dir, key(url) + SUFFIX);
        try {
            if (f.isFile()) {
                f.setLastModified(System.currentTimeMillis()); // LRU on disk
            } else {
                if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                    throw new IOException("cannot create " + dir);
                }
                fetcher.fetch(url, f);
                trim(dir, maxDiskBytes, System.currentTimeMillis());
            }
            return decoder.decode(f, sizePx);
        } catch (IOException e) {
            f.delete(); // a broken file must not stick
            return null;
        } catch (RuntimeException e) {
            return null;
        } catch (OutOfMemoryError e) {
            f.delete(); // too big to decode here: do not keep it
            return null;
        }
    }

    private static String memoryKey(String url, int sizePx) {
        return key(url) + "@" + sizePx;
    }

    private static long nowMs() {
        return System.nanoTime() / 1000000L;
    }

    /** Lower-case hex SHA-1 of url (UTF-8): the cache file name. */
    static String key(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The largest power-of-two BitmapFactory inSampleSize that keeps both
     * sides of a width x height image at least target pixels.
     */
    static int sampleSize(int width, int height, int target) {
        int s = 1;
        if (target <= 0) return s;
        while (width / (s * 2) >= target && height / (s * 2) >= target) s *= 2;
        return s;
    }

    /**
     * Deletes the least recently used covers (by mtime) until the rest fit
     * in maxBytes, and overhear-fetch temp files older than STALE_PART_MS (a
     * killed helper leaves them; a running one may still write fresh ones).
     */
    static void trim(File dir, long maxBytes, long nowMs) {
        File[] files = dir.listFiles();
        if (files == null) return;
        List<File> covers = new ArrayList<File>();
        long total = 0;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".") && n.endsWith(".part")) {
                if (nowMs - f.lastModified() > STALE_PART_MS) f.delete();
            } else if (n.endsWith(SUFFIX) && f.isFile()) {
                covers.add(f);
                total += f.length();
            }
        }
        if (total <= maxBytes) return;
        final Map<File, Long> mtimes = new HashMap<File, Long>();
        for (File f : covers) mtimes.put(f, f.lastModified());
        File[] sorted = covers.toArray(new File[0]);
        Arrays.sort(sorted, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                long x = mtimes.get(a), y = mtimes.get(b);
                return x < y ? -1 : x == y ? 0 : 1;
            }
        });
        for (File f : sorted) {
            if (total <= maxBytes) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }
}
