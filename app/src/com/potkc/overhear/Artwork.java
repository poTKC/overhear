package com.potkc.overhear;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Process;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.potkc.overhear.runtime.Dns;
import com.potkc.overhear.runtime.HelperProcess;
import com.potkc.overhear.runtime.LineReader;
import com.potkc.overhear.runtime.RuntimeInstaller;
import com.potkc.overhear.soloist.MainThreadExecutor;

/**
 * The app's one ArtworkLoader: covers are fetched with overhear-fetch (Android
 * 4.3's own TLS cannot reach i.scdn.co reliably) into cacheDir/art and
 * decoded as RGB_565 bitmaps scaled to the view size, each with its
 * background colour (ArtColor, from a box-averaged SAMPLE_SIDE square) worked
 * out on the same background thread and kept with it in the memory cache.
 * The main cover has a thread of its own; thumbnails share two. Each fetch
 * is killed after FETCH_TIMEOUT_MS.
 */
final class Artwork {
    private static final String TAG = "OverhearArtwork";
    static final long DISK_MAX_BYTES = 5L * 1024 * 1024;
    static final long FETCH_TIMEOUT_MS = 30000;
    static final long FAILURE_RETRY_MS = 60000;
    private static final int MAX_LINE_BYTES = 4096;

    /** A decoded cover and the player background colour for it. */
    static final class Cover {
        final Bitmap bitmap;
        /** ArtColor.extract of the cover: opaque ARGB. */
        final int color;

        Cover(Bitmap bitmap, int color) {
            this.bitmap = bitmap;
            this.color = color;
        }
    }

    private static ArtworkLoader<Cover> loader;

    private Artwork() {}

    /** Main thread. */
    static ArtworkLoader<Cover> loader(Context context) {
        if (loader != null) return loader;
        final Context app = context.getApplicationContext();
        final RuntimeInstaller installer = new RuntimeInstaller(app);
        ThreadFactory low = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override public Thread newThread(final Runnable r) {
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        r.run();
                    }
                }, "artwork-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), low);
        pool.allowCoreThreadTimeOut(true);
        ThreadPoolExecutor urgent = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), low);
        urgent.allowCoreThreadTimeOut(true);

        ArtworkLoader.Fetcher fetcher = new ArtworkLoader.Fetcher() {
            @Override public void fetch(String url, File out) throws IOException {
                runHelper(installer, url, out);
            }
        };
        ArtworkLoader.Decoder<Cover> decoder = new ArtworkLoader.Decoder<Cover>() {
            @Override public Cover decode(File file, int sizePx) throws IOException {
                Bitmap b = decodeScaled(file, sizePx);
                return new Cover(b, color(b));
            }
        };
        loader = new ArtworkLoader<Cover>(new File(app.getCacheDir(), "art"), DISK_MAX_BYTES,
                FAILURE_RETRY_MS, fetcher, decoder, pool, urgent, new MainThreadExecutor());
        return loader;
    }

    /** overhear-fetch get url out; throws unless it printed "done" and exited 0. */
    static void runHelper(RuntimeInstaller installer, String url, File out) throws IOException {
        List<String> args = new ArrayList<String>();
        args.add("--ca");
        args.add(installer.caFile().getPath());
        args.add("--dns");
        args.add(Dns.helperArg(RuntimeInstaller.systemDns()));
        args.add("get");
        args.add(url);
        args.add(out.getPath());
        HelperProcess.Result res = HelperProcess.run(installer, args, null, null, FETCH_TIMEOUT_MS);
        boolean done = false;
        String last = "";
        LineReader r = new LineReader(new ByteArrayInputStream(res.stdout), MAX_LINE_BYTES);
        String line;
        while ((line = r.readLine()) != null) {
            if (line.equals("done")) done = true;
            else if (!line.startsWith("progress ")) last = line;
        }
        // Errors go to stderr; stdout carries only progress and "done".
        if (!res.lastErrorLine.isEmpty()) last = res.lastErrorLine;
        int code = res.exitCode;
        if (code != 0 || !done || !out.isFile()) {
            Log.w(TAG, "artwork fetch failed (" + code + "): " + last);
            throw new IOException("overhear-fetch: " + last);
        }
    }

    /**
     * The background colour for b, from a box-averaged SAMPLE_SIDE square
     * (ArtColor.downscale; not createScaledBitmap, whose bilinear filter
     * aliases at this ratio). Background thread.
     */
    static int color(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        return ArtColor.extract(ArtColor.downscale(px, w, h, ArtColor.SAMPLE_SIDE));
    }

    /**
     * Decodes file as RGB_565, sampled, then scaled so its smaller side is
     * sizePx (the sampled intermediate is recycled at once). The bitmap has
     * no density, so views draw it at its pixel size.
     */
    static Bitmap decodeScaled(File file, int sizePx) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("not an image");
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = ArtworkLoader.sampleSize(o.outWidth, o.outHeight, sizePx);
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        d.inDither = true;
        d.inScaled = false;
        Bitmap b = BitmapFactory.decodeFile(file.getPath(), d);
        if (b == null) throw new IOException("cannot decode");
        int side = Math.min(b.getWidth(), b.getHeight());
        if (side > sizePx) {
            int w = Math.max(1, Math.round(b.getWidth() * (float) sizePx / side));
            int h = Math.max(1, Math.round(b.getHeight() * (float) sizePx / side));
            Bitmap scaled = Bitmap.createScaledBitmap(b, w, h, true);
            if (scaled != b) b.recycle();
            b = scaled;
        }
        b.setDensity(Bitmap.DENSITY_NONE);
        return b;
    }
}
