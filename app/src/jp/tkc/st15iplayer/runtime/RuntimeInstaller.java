package jp.tkc.st15iplayer.runtime;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays out what Soloist needs on internal storage:
 *
 *   filesDir/runtime/   the bundled glibc runtime (assets/runtime, see
 *                       runtime/build.sh) plus the resolv.conf written here
 *   filesDir/soloist/   Spotify's Soloist, always downloaded on the device
 *                       (it must never be bundled or redistributed)
 *
 * Every method does file or network I/O and may block for minutes: call them
 * off the main thread. ensureBundle and ensureSoloist are serialised on this
 * instance; writeResolvConf has its own lock so a Soloist start never waits
 * for a download. cancelDownload() may be called from any thread and is
 * sticky: it ends this instance's downloads for good (owners call it when
 * they are destroyed). PlayerService owns the instance, one per service
 * lifetime.
 */
public final class RuntimeInstaller {
    public static final String SOLOIST_URL =
            "https://soloist-builds.spotifycdn.com/soloist_release_arm32.tar.gz";
    private static final String TAG = "ST15iRuntime";
    private static final String ASSET_DIR = "runtime";
    private static final String VERSION = "VERSION";
    private static final String HELPER = "st15i-fetch";
    private static final String RESOLV_CONF = "resolv.conf";
    private static final String[] EXECUTABLES = {SoloistCommand.LOADER, HELPER};
    private static final int STDERR_LINES = 20;
    /** A download that takes longer than this is killed and reported as failed. */
    static final long DOWNLOAD_DEADLINE_MS = 10 * 60 * 1000L;
    private static final long STDERR_JOIN_MS = 2000;
    static final int MAX_LINE_BYTES = 4096;

    /** Outcome of ensureSoloist. */
    public static final class Result {
        public final boolean ok;
        /** "" on success, else a short reason (the helper's last stderr line). */
        public final String message;
        /**
         * Failed before downloading because /data is too full
         * (Storage.downloadError); freeing space (clearSoloistCache) may help.
         */
        public final boolean noSpace;

        Result(boolean ok, String message) {
            this(ok, message, false);
        }

        Result(boolean ok, String message, boolean noSpace) {
            this.ok = ok;
            this.message = message;
            this.noSpace = noSpace;
        }

        @Override public String toString() {
            return ok ? "ok" : "failed: " + message;
        }
    }

    private final Context context;
    private final File filesDir;
    private final Object resolvLock = new Object();
    /** The running st15i-fetch, for cancelDownload(). */
    private volatile Process download;
    /** Set for good by cancelDownload(). */
    private volatile boolean closed;

    public RuntimeInstaller(Context context) {
        this.context = context.getApplicationContext();
        this.filesDir = this.context.getFilesDir();
    }

    public File runtimeDir() { return new File(filesDir, ASSET_DIR); }
    public File soloistDir() { return new File(filesDir, "soloist"); }
    public File soloistBin() { return new File(soloistDir(), "soloist"); }
    public File dataDir() { return new File(filesDir, "soloist-data"); }
    public File cacheDir() { return new File(context.getCacheDir(), "soloist"); }
    public File homeDir() { return filesDir; }
    /** The st15i-fetch helper (installed by ensureBundle). */
    public File helperBin() { return new File(runtimeDir(), HELPER); }
    /** The CA bundle for st15i-fetch --ca. */
    public File caFile() { return new File(runtimeDir(), SoloistCommand.CACERT); }

    /**
     * Copies assets/runtime/* to filesDir/runtime/ unless the installed
     * VERSION already matches the bundled one. The copy goes to a temp dir
     * that replaces the old one with renames, so a crash never leaves a half
     * copied runtime under the real name. Do not call while Soloist runs.
     */
    public synchronized void ensureBundle() throws IOException {
        AssetManager assets = context.getAssets();
        String bundled = readAll(assets.open(ASSET_DIR + "/" + VERSION)).trim();
        File dir = runtimeDir();
        File installedVersion = new File(dir, VERSION);
        if (installedVersion.isFile() && bundled.equals(readFile(installedVersion).trim())) {
            markExecutable(dir);
            return;
        }
        File tmp = new File(filesDir, ASSET_DIR + ".tmp");
        File old = new File(filesDir, ASSET_DIR + ".old");
        deleteRecursively(tmp);
        deleteRecursively(old);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        String[] names = assets.list(ASSET_DIR);
        if (names == null || names.length == 0) throw new IOException("no runtime in the APK");
        for (String name : names) {
            if (name.equals(VERSION)) continue;
            copy(assets.open(ASSET_DIR + "/" + name), new File(tmp, name));
        }
        // VERSION last: a dir with VERSION is a complete copy.
        copy(assets.open(ASSET_DIR + "/" + VERSION), new File(tmp, VERSION));
        markExecutable(tmp);
        if (dir.exists() && !dir.renameTo(old)) throw new IOException("cannot move " + dir + " aside");
        if (!tmp.renameTo(dir)) {
            old.renameTo(dir);
            throw new IOException("cannot install " + dir);
        }
        deleteRecursively(old);
        Log.i(TAG, "runtime " + bundled + " installed");
    }

    private static void markExecutable(File dir) throws IOException {
        for (String name : EXECUTABLES) {
            File f = new File(dir, name);
            if (!f.setExecutable(true, false)) throw new IOException("cannot chmod " + f);
        }
    }

    /** True when a Soloist binary is installed. */
    public boolean hasSoloist() {
        return soloistBin().isFile();
    }

    /**
     * Downloads and unpacks Soloist into filesDir/soloist/ through
     * st15i-fetch when it is missing, or always when refresh is set (Soloist
     * exited with SoloistCommand.EXIT_EXPIRED). The helper replaces the dir
     * atomically. Fails early when /data has less than
     * Storage.DOWNLOAD_MIN_FREE_MB free, and after DOWNLOAD_DEADLINE_MS.
     * Needs ensureBundle() first. listener may be null and is called on this
     * thread.
     */
    public synchronized Result ensureSoloist(boolean refresh, ProgressListener listener) {
        File bin = soloistBin();
        if (!refresh && bin.isFile()) {
            bin.setExecutable(true, false);
            return new Result(true, "");
        }
        if (closed) return fail("download cancelled");
        String noRoom = Storage.downloadError(freeBytes(filesDir));
        if (noRoom != null) {
            Log.w(TAG, noRoom);
            return new Result(false, noRoom, true);
        }
        File runtime = runtimeDir();
        List<String> dns = systemDns();
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(runtime, HELPER).getPath());
        cmd.add("--ca");
        cmd.add(new File(runtime, SoloistCommand.CACERT).getPath());
        cmd.add("--dns");
        cmd.add(Dns.helperArg(dns));
        cmd.add("extract");
        cmd.add(SOLOIST_URL);
        cmd.add(soloistDir().getPath());

        final Process p;
        try {
            p = new ProcessBuilder(cmd).directory(runtime).start();
        } catch (IOException e) {
            return new Result(false, "cannot run " + HELPER + ": " + e.getMessage());
        }
        download = p;
        // cancelDownload() sets closed before reading download, so one of the
        // two sides always sees the other.
        if (closed) p.destroy();
        final LogRing stderr = new LogRing(STDERR_LINES);
        final InputStream err = p.getErrorStream();
        Thread errReader = new Thread(new Runnable() {
            @Override public void run() {
                pumpLines(err, stderr);
            }
        }, "st15i-fetch-stderr");
        errReader.setDaemon(true);
        errReader.start();
        // Killing the helper closes its pipes, which ends the read loop below.
        final boolean[] timedOut = {false};
        final Object doneLock = new Object();
        final boolean[] finished = {false};
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                long deadline = SystemClock.elapsedRealtime() + DOWNLOAD_DEADLINE_MS;
                synchronized (doneLock) {
                    long left;
                    while (!finished[0] && (left = deadline - SystemClock.elapsedRealtime()) > 0) {
                        try {
                            doneLock.wait(left);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (finished[0]) return;
                    timedOut[0] = true;
                }
                p.destroy();
            }
        }, "st15i-fetch-deadline");
        watchdog.setDaemon(true);
        watchdog.start();

        HelperOutput out = new HelperOutput(listener);
        LineReader r = new LineReader(p.getInputStream(), MAX_LINE_BYTES);
        int code;
        try {
            p.getOutputStream().close();
            String line;
            while ((line = r.readLine()) != null) out.line(line);
            code = p.waitFor();
        } catch (IOException e) {
            p.destroy();
            code = -1;
        } catch (InterruptedException e) {
            p.destroy();
            Thread.currentThread().interrupt();
            return new Result(false, "interrupted");
        } finally {
            download = null;
            synchronized (doneLock) {
                finished[0] = true;
                doneLock.notifyAll();
            }
            closeQuietly(p.getInputStream());
            joinQuietly(errReader, STDERR_JOIN_MS);
        }
        boolean wasTimedOut;
        synchronized (doneLock) {
            wasTimedOut = timedOut[0];
        }
        if (closed) return fail("download cancelled");
        if (wasTimedOut) return fail("download timed out after " + DOWNLOAD_DEADLINE_MS / 60000 + " min");
        if (code != 0 || !out.isDone()) {
            List<String> lines = stderr.lines();
            return fail(lines.isEmpty() ? HELPER + " exited with " + code : lines.get(lines.size() - 1));
        }
        if (!bin.isFile()) return fail("the download has no soloist binary");
        bin.setExecutable(true, false);
        Log.i(TAG, "Soloist installed");
        return new Result(true, "");
    }

    private static Result fail(String msg) {
        Log.w(TAG, "Soloist download failed: " + msg);
        return new Result(false, msg);
    }

    /**
     * Kills a running ensureSoloist download, which then returns a failure,
     * and makes every later download on this instance fail at once (also one
     * that is just forking its helper). The helper recovers its half-done
     * work on the next run. Any thread.
     */
    public void cancelDownload() {
        closed = true;
        Process p = download;
        if (p != null) p.destroy();
    }

    /**
     * Deletes Soloist's cache dir (disposable: Soloist refills it) to free
     * space, e.g. when a re-download after expiry has no room. Only while
     * Soloist is not running. Returns the bytes now free on /data.
     */
    public long clearSoloistCache() {
        deleteRecursively(cacheDir());
        long free = freeBytes(filesDir);
        Log.i(TAG, "cleared the Soloist cache: " + free / (1024 * 1024) + " MB free");
        return free;
    }

    /** Free bytes on the filesystem holding dir, or Long.MAX_VALUE if unknown. */
    public static long freeBytes(File dir) {
        try {
            StatFs st = new StatFs(dir.getPath());
            return st.getAvailableBlocksLong() * st.getBlockSizeLong();
        } catch (IllegalArgumentException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Writes runtime/resolv.conf from the current system DNS servers and
     * returns them. Call right before every Soloist start: the servers change
     * with the Wi-Fi network.
     */
    public List<String> writeResolvConf() throws IOException {
        synchronized (resolvLock) {
            return writeResolvConfLocked();
        }
    }

    private List<String> writeResolvConfLocked() throws IOException {
        List<String> dns = systemDns();
        File dir = runtimeDir();
        File tmp = new File(dir, RESOLV_CONF + ".tmp");
        OutputStream o = new FileOutputStream(tmp);
        try {
            o.write(Dns.resolvConf(dns).getBytes("US-ASCII"));
        } finally {
            o.close();
        }
        if (!tmp.renameTo(new File(dir, RESOLV_CONF))) throw new IOException("cannot write resolv.conf");
        return dns;
    }

    /** net.dns1 / net.dns2 when they are literal IPs, else Dns.FALLBACK. */
    public static List<String> systemDns() {
        return Dns.choose(systemProperty("net.dns1"), systemProperty("net.dns2"));
    }

    /** android.os.SystemProperties.get via reflection (hidden API), then getprop. */
    static String systemProperty(String name) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method get = c.getMethod("get", String.class);
            Object v = get.invoke(null, name);
            if (v instanceof String) return (String) v;
        } catch (Exception e) {
            // fall through to getprop
        }
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String v = readAll(p.getInputStream()).trim();
            p.waitFor();
            return v;
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    static void pumpLines(InputStream in, LogRing ring) {
        LineReader r = new LineReader(in, MAX_LINE_BYTES);
        try {
            String line;
            while ((line = r.readLine()) != null) ring.add(line);
        } catch (IOException e) {
            // process gone
        } finally {
            closeQuietly(in);
        }
    }

    private static void joinQuietly(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void copy(InputStream in, File dest) throws IOException {
        try {
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                ((FileOutputStream) out).getFD().sync();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static String readFile(File f) throws IOException {
        return readAll(new FileInputStream(f));
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            StringBuilder sb = new StringBuilder();
            InputStreamReader r = new InputStreamReader(in, "UTF-8");
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            in.close();
        }
    }

    /** Deletes f and, unless f is a symlink, everything under it. */
    static void deleteRecursively(File f) {
        if (isSymlink(f)) {
            f.delete();
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }

    private static boolean isSymlink(File f) {
        try {
            File parent = f.getParentFile();
            File inCanonicalParent = parent == null ? f : new File(parent.getCanonicalFile(), f.getName());
            return !inCanonicalParent.getCanonicalFile().equals(inCanonicalParent.getAbsoluteFile());
        } catch (IOException e) {
            return true; // when in doubt, do not descend
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
