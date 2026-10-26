package jp.tkc.st15iplayer.runtime;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Runs one Soloist process at a time under the bundled glibc (see
 * SoloistCommand for the command line and how the API key is passed).
 *
 * A reader thread pumps Soloist's merged stdout/stderr into a ring of the last
 * LOG_LINES lines (getLog(), for a debug view), each capped at 4 KB, with
 * colour codes stripped and the key redacted. Only start, exit and error lines
 * reach logcat, rate limited, under the tag ST15iSoloist.
 *
 * Threading: start() does file I/O (stray cleanup, resolv.conf, dirs) and
 * must run off the main thread, but holds the internal lock only around the
 * fork, so stop(), release() and isRunning() never wait for slow work and
 * may be called from the main thread. A stop() that lands while start() is
 * still preparing makes that start() fail with an IOException instead of
 * leaving a process behind.
 *
 * Restart and backoff policy belongs to the caller: this class only reports
 * exits. PlayerService owns the only instance (one per service lifetime,
 * released in onDestroy); start() kills Soloist runs it does not own, i.e.
 * those left over from an earlier app process, so two live instances in one
 * process would fight.
 */
public final class SoloistProcess {
    public static final int LOG_LINES = 200;
    private static final String TAG = "ST15iSoloist";
    private static final int MAX_LINE_BYTES = 4096;
    /** At most this many log lines per LOGCAT_WINDOW_MS reach logcat. */
    private static final int LOGCAT_BURST = 10;
    private static final long LOGCAT_WINDOW_MS = 60000;
    private static final long STRAY_WAIT_MS = 3000;
    /** How long stop() lets Soloist shut down after SIGTERM before SIGKILL. */
    static final long TERM_GRACE_MS = 2000;
    private static final int SIGTERM = 15;
    /** fakepulse's trace line for an underflow it reports to Soloist. */
    static final String UNDERFLOW_LINE = "[fakepulse] underflow";
    private static final Pattern PID = Pattern.compile("[0-9]+");

    public interface Listener {
        /**
         * The process started by a start() call exited. Runs on that run's
         * reader thread and must return quickly (post to a Handler).
         *
         * @param exitCode  Soloist's exit status (a signal shows as its number
         *                  on Android 4.3, e.g. 9 for SIGKILL); -1 if unknown
         * @param expired   exitCode == SoloistCommand.EXIT_EXPIRED and no stop
         *                  was requested: re-download with
         *                  RuntimeInstaller.ensureSoloist(true, ...)
         * @param requested the exit followed stop() or release(); judge
         *                  crashes by this flag, not by the exit code
         */
        void onExit(int exitCode, boolean expired, boolean requested);
    }

    /** One started process. */
    private final class Run implements Runnable {
        final Process process;
        final String secret;
        final String loaderPath;
        final AtomicBoolean stopRequested = new AtomicBoolean();
        final CountDownLatch exited = new CountDownLatch(1);
        private long windowStart = -LOGCAT_WINDOW_MS;
        private int windowCount;
        private int suppressed;

        Run(Process process, String secret, String loaderPath) {
            this.process = process;
            this.secret = secret;
            this.loaderPath = loaderPath;
        }

        @Override public void run() {
            InputStream in = process.getInputStream();
            try {
                // Redacts before the line is cut, so no key fragment survives.
                LineReader r = new LineReader(in, MAX_LINE_BYTES, LineReader.DEFAULT_SLACK,
                        new LineReader.Filter() {
                            @Override public String apply(String raw) {
                                return SoloistCommand.sanitize(raw, secret);
                            }
                        });
                String line;
                while ((line = r.readLine()) != null) {
                    log.add(line);
                    if (line.startsWith(UNDERFLOW_LINE)) {
                        underflows.incrementAndGet();
                        continue; // counted, not logged: they can come in bursts
                    }
                    if (SoloistCommand.isErrorLine(line)) logcat(line);
                }
            } catch (IOException e) {
                // stream closed: the process is gone or being killed
            } finally {
                try {
                    in.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = -1;
            }
            current.compareAndSet(this, null);
            exited.countDown();
            boolean requested = stopRequested.get();
            boolean expired = !requested && SoloistCommand.isExpired(code);
            String msg = "exited with " + code + (expired ? " (build expired)" : "")
                    + (requested ? " after stop" : "");
            log.add("[st15i] " + msg);
            Log.i(TAG, msg);
            if (listener != null) listener.onExit(code, expired, requested);
        }

        /** SIGTERM, then SIGKILL after TERM_GRACE_MS, on a helper thread. */
        void requestStop() {
            if (!stopRequested.compareAndSet(false, true)) return;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    int pid = pidOf(process, loaderPath);
                    // Once reaped, the pid may belong to someone else.
                    if (exited.getCount() == 0) return;
                    if (pid > 0) android.os.Process.sendSignal(pid, SIGTERM);
                    boolean gone = false;
                    try {
                        gone = exited.await(pid > 0 ? TERM_GRACE_MS : 0, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (!gone) {
                        Log.w(TAG, pid > 0 ? "no exit " + TERM_GRACE_MS + " ms after SIGTERM, killing"
                                : "pid unknown, killing");
                        process.destroy();
                    }
                }
            }, "soloist-stop");
            t.setDaemon(true);
            t.start();
        }

        private void logcat(String line) {
            long now = SystemClock.elapsedRealtime();
            if (now - windowStart >= LOGCAT_WINDOW_MS) {
                if (suppressed > 0) Log.w(TAG, suppressed + " error lines not shown (see the debug log)");
                windowStart = now;
                windowCount = 0;
                suppressed = 0;
            }
            if (windowCount++ < LOGCAT_BURST) Log.w(TAG, line);
            else suppressed++;
        }
    }

    private final RuntimeInstaller installer;
    private final Listener listener;
    private final LogRing log = new LogRing(LOG_LINES);
    private final AtomicReference<Run> current = new AtomicReference<Run>();
    /** Held only for state checks and the fork itself; never for slow work. */
    private final Object lock = new Object();
    private boolean starting; // guarded by lock
    private boolean released; // guarded by lock
    private int stopCount; // guarded by lock
    private volatile boolean lowStorageWarned;
    private final AtomicInteger underflows = new AtomicInteger();

    /** listener may be null. */
    public SoloistProcess(RuntimeInstaller installer, Listener listener) {
        this.installer = installer;
        this.listener = listener;
    }

    /**
     * Cleans up leftover runs, writes a fresh resolv.conf and starts Soloist.
     * Needs RuntimeInstaller.ensureBundle() and ensureSoloist() first. Off the
     * main thread.
     *
     * @throws IllegalStateException when running, starting, or released
     * @throws IllegalArgumentException when apiKey is empty or malformed
     * @throws IOException when setup or the fork fails, or stop() was called
     *         while starting (no process is left running)
     */
    public void start(String deviceName, String apiKey, boolean verbose) throws IOException {
        start(deviceName, apiKey, verbose, false);
    }

    /**
     * As start(deviceName, apiKey, verbose), optionally with FAKEPULSE_LOG=1
     * (debug: fakepulse traces its calls and underflows to the log ring;
     * getUnderflowCount() counts the latter).
     */
    public void start(String deviceName, String apiKey, boolean verbose, boolean traceAudio)
            throws IOException {
        File bin = installer.soloistBin();
        if (!bin.isFile()) throw new IOException("Soloist is not installed");
        File cacheDir = installer.cacheDir();
        long free = RuntimeInstaller.freeBytes(installer.homeDir());
        int cacheMb = Storage.CACHE_MB;
        SoloistCommand cmd = new SoloistCommand(installer.runtimeDir(), bin, installer.dataDir(),
                cacheDir, installer.homeDir(), deviceName, apiKey, cacheMb, verbose);
        cmd.setTraceAudio(traceAudio);
        int stopsBefore;
        synchronized (lock) {
            if (released) throw new IllegalStateException("released");
            if (starting || current.get() != null) throw new IllegalStateException("Soloist is already running");
            starting = true;
            stopsBefore = stopCount;
        }
        try {
            if (Storage.isLowForCache(free) && !lowStorageWarned) {
                lowStorageWarned = true; // once per instance, i.e. per service lifetime
                String warn = "low storage: " + free / (1024 * 1024) + " MB free for a " + cacheMb + " MB cache";
                Log.w(TAG, warn);
                log.add("[st15i] " + warn);
            }
            killStrays(cmd.loaderPath());
            installer.writeResolvConf();
            mkdirs(installer.dataDir());
            mkdirs(cacheDir);
            ProcessBuilder pb = new ProcessBuilder(cmd.command())
                    .directory(cmd.workingDir())
                    .redirectErrorStream(true);
            cmd.applyEnvironment(pb.environment());

            Run run;
            synchronized (lock) {
                if (released || stopCount != stopsBefore) throw new IOException("stopped while starting");
                Process p = pb.start();
                try {
                    run = new Run(p, apiKey, cmd.loaderPath());
                    current.set(run);
                } catch (RuntimeException e) {
                    p.destroy();
                    throw e;
                }
            }
            try {
                run.process.getOutputStream().close();
                String msg = "started as \"" + cmd.deviceName() + "\" (cache " + cacheMb + " MB)";
                log.add("[st15i] " + msg);
                Log.i(TAG, msg);
                Thread t = new Thread(run, "soloist-log");
                t.setDaemon(true);
                t.start();
            } catch (Throwable e) {
                // No reader thread: nobody would reap the process or clear current.
                current.compareAndSet(run, null);
                run.process.destroy();
                if (e instanceof IOException) throw (IOException) e;
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw (Error) e;
            }
        } finally {
            synchronized (lock) {
                starting = false;
            }
        }
    }

    /**
     * Asks Soloist to exit: SIGTERM, then SIGKILL after TERM_GRACE_MS. Returns
     * at once; the listener gets onExit with requested set, and isRunning()
     * stays true until the process is reaped. Also aborts a start() that is
     * still preparing. Any thread.
     */
    public void stop() {
        Run run;
        synchronized (lock) {
            stopCount++;
            run = current.get();
        }
        if (run != null) run.requestStop();
    }

    /** stop(), and make every later start() fail. For owners being destroyed. */
    public void release() {
        synchronized (lock) {
            released = true;
        }
        stop();
    }

    /** fakepulse underflow reports seen so far (only traced with traceAudio). */
    public int getUnderflowCount() {
        return underflows.get();
    }

    /** Lock-free. */
    public boolean isRunning() {
        return current.get() != null;
    }

    /** The last LOG_LINES output lines, oldest first, key redacted. */
    public List<String> getLog() {
        return log.lines();
    }

    /** The pid of p: the platform Process's private field, else a /proc scan. */
    static int pidOf(Process p, String loaderPath) {
        try {
            Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            int pid = f.getInt(p);
            if (pid > 0) return pid;
        } catch (Exception e) {
            // fall back to /proc
        }
        int self = android.os.Process.myPid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return -1;
        for (File d : procs) {
            if (!PID.matcher(d.getName()).matches()) continue;
            String cmdline = readSmall(new File(d, "cmdline"));
            if (cmdline == null || !cmdline.startsWith(loaderPath + "\0")) continue;
            String stat = readSmall(new File(d, "stat"));
            if (stat != null && Strays.parsePpid(stat) == self) return Integer.parseInt(d.getName());
        }
        return -1;
    }

    /**
     * Kills Soloist runs that an earlier app process left behind (see
     * Strays), never children of this process, and waits up to STRAY_WAIT_MS
     * for them to go. Runs before current is set, so nothing of ours can match.
     */
    private void killStrays(String loaderPath) {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        int self = android.os.Process.myPid();
        List<File> killed = new ArrayList<File>();
        for (File d : procs) {
            String name = d.getName();
            if (!PID.matcher(name).matches()) continue;
            String cmdline = readSmall(new File(d, "cmdline"));
            if (cmdline == null || !cmdline.startsWith(loaderPath)) continue;
            String stat = readSmall(new File(d, "stat"));
            int ppid = stat == null ? -1 : Strays.parsePpid(stat);
            int pid = Integer.parseInt(name);
            if (!Strays.isStray(cmdline, loaderPath, pid, ppid, self, -1)) continue;
            // Same uid (the binary lives in our private dir), so this works.
            android.os.Process.killProcess(pid);
            killed.add(d);
        }
        long deadline = SystemClock.elapsedRealtime() + STRAY_WAIT_MS;
        for (File d : killed) {
            while (d.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            String msg = d.exists() ? "stray Soloist " + d.getName() + " survived SIGKILL"
                    : "killed stray Soloist " + d.getName();
            Log.w(TAG, msg);
            log.add("[st15i] " + msg);
        }
    }

    private static String readSmall(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            return n <= 0 ? null : new String(buf, 0, n, "UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
    }
}
