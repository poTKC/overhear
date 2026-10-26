package jp.tkc.st15iplayer.runtime;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Runs one Soloist process at a time under the bundled glibc (see
 * SoloistCommand for the command line and how the API key is passed).
 *
 * A reader thread pumps Soloist's merged stdout/stderr into a ring of the last
 * LOG_LINES lines (getLog(), for a debug view) with colour codes stripped and
 * the key redacted. Only start, exit and error lines reach logcat, rate
 * limited, under the tag ST15iSoloist.
 *
 * Restart and backoff policy belongs to the caller (PlayerService): this class
 * only reports exits. start() and stop() do not block on the network and may be
 * called from any thread, but start() does file I/O (resolv.conf, dirs), so
 * keep it off the main thread.
 */
public final class SoloistProcess {
    public static final int LOG_LINES = 200;
    private static final String TAG = "ST15iSoloist";
    /** At most this many log lines per LOGCAT_WINDOW_MS reach logcat. */
    private static final int LOGCAT_BURST = 10;
    private static final long LOGCAT_WINDOW_MS = 60000;
    private static final long STRAY_WAIT_MS = 3000;
    private static final Pattern PID = Pattern.compile("[0-9]+");

    public interface Listener {
        /**
         * The process started by a start() call exited. Runs on that run's
         * reader thread and must return quickly (post to a Handler).
         *
         * @param exitCode  Soloist's exit status; a stop() (SIGKILL) shows as 9 on Android 4.3
         * @param expired   exitCode == SoloistCommand.EXIT_EXPIRED: re-download
         *                  with RuntimeInstaller.ensureSoloist(true, ...)
         * @param requested the exit followed a stop() call
         */
        void onExit(int exitCode, boolean expired, boolean requested);
    }

    /** One started process. */
    private final class Run implements Runnable {
        final Process process;
        final String secret;
        volatile boolean stopRequested;
        private long windowStart;
        private int windowCount;
        private int suppressed;

        Run(Process process, String secret) {
            this.process = process;
            this.secret = secret;
        }

        @Override public void run() {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                String raw;
                while ((raw = r.readLine()) != null) {
                    String line = SoloistCommand.sanitize(raw, secret);
                    log.add(line);
                    if (SoloistCommand.isErrorLine(line)) logcat(line);
                }
            } catch (IOException e) {
                // stream closed: the process is gone or being killed
            } finally {
                if (r != null) {
                    try {
                        r.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                code = -1;
            }
            synchronized (SoloistProcess.this) {
                if (current == this) current = null;
            }
            boolean expired = SoloistCommand.isExpired(code);
            String msg = "exited with " + code + (expired ? " (build expired)" : "")
                    + (stopRequested ? " after stop" : "");
            log.add("[st15i] " + msg);
            Log.i(TAG, msg);
            if (listener != null) listener.onExit(code, expired, stopRequested);
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
    private Run current; // guarded by this

    /** listener may be null. */
    public SoloistProcess(RuntimeInstaller installer, Listener listener) {
        this.installer = installer;
        this.listener = listener;
    }

    /**
     * Writes a fresh resolv.conf and starts Soloist. Needs
     * RuntimeInstaller.ensureBundle() and ensureSoloist() first.
     *
     * @throws IllegalStateException when a process is already running
     * @throws IllegalArgumentException when apiKey is empty or malformed
     */
    public synchronized void start(String deviceName, String apiKey, boolean verbose) throws IOException {
        if (current != null) throw new IllegalStateException("Soloist is already running");
        File bin = installer.soloistBin();
        if (!bin.isFile()) throw new IOException("Soloist is not installed");
        SoloistCommand cmd = new SoloistCommand(installer.runtimeDir(), bin, installer.dataDir(),
                installer.cacheDir(), installer.homeDir(), deviceName, apiKey, verbose);
        killStrays(cmd);
        installer.writeResolvConf();
        mkdirs(installer.dataDir());
        mkdirs(installer.cacheDir());

        ProcessBuilder pb = new ProcessBuilder(cmd.command())
                .directory(cmd.workingDir())
                .redirectErrorStream(true);
        cmd.applyEnvironment(pb.environment());
        Process p = pb.start();
        p.getOutputStream().close();
        Run run = new Run(p, apiKey);
        current = run;
        String msg = "started as \"" + cmd.deviceName() + "\"";
        log.add("[st15i] " + msg);
        Log.i(TAG, msg);
        Thread t = new Thread(run, "soloist-log");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Kills the running process (Process.destroy() sends SIGKILL on Android
     * 4.3). Returns at once; the listener still gets onExit with requested set,
     * and isRunning() stays true until then (start() throws until it does).
     */
    public synchronized void stop() {
        Run run = current;
        if (run == null) return;
        run.stopRequested = true;
        run.process.destroy();
    }

    public synchronized boolean isRunning() {
        return current != null;
    }

    /** The last LOG_LINES output lines, oldest first, key redacted. */
    public List<String> getLog() {
        return log.lines();
    }

    /**
     * Kills Soloist processes left by an earlier app process: a force-stop or
     * crash kills the app but not its children on Android 4.3, and a survivor
     * would keep the WebSocket port and the speaker name. Called with no run
     * of ours active. Waits up to STRAY_WAIT_MS for them to go.
     */
    private void killStrays(SoloistCommand cmd) {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        List<File> killed = new ArrayList<File>();
        for (File p : procs) {
            String name = p.getName();
            if (!PID.matcher(name).matches()) continue;
            String cmdline = readCmdline(new File(p, "cmdline"));
            if (cmdline == null || !cmd.isRunOf(cmdline)) continue;
            // Same uid (the binary lives in our private dir), so this works.
            android.os.Process.killProcess(Integer.parseInt(name));
            killed.add(p);
            Log.w(TAG, "killed stray Soloist " + name);
            log.add("[st15i] killed stray Soloist " + name);
        }
        long deadline = SystemClock.elapsedRealtime() + STRAY_WAIT_MS;
        for (File p : killed) {
            while (p.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        }
    }

    private static String readCmdline(File f) {
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
