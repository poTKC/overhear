package com.potkc.overhear.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs overhear-fetch once and waits for it: cwd runtimeDir, the given
 * environment added, stdin written and then closed (the helper reads it to
 * EOF), stdout read whole on the calling thread and stderr on a thread of its
 * own, so neither pipe can fill and block the helper. A shared watchdog
 * thread destroys the process after timeoutMs, which closes its pipes and
 * ends the reads. Blocking; call it on a background thread. Never logs: the
 * environment and stdin may carry tokens, so callers must not put them in
 * args either.
 */
public final class HelperProcess {
    /** The longest stdout kept: overhear-fetch's reply body cap plus room for its header. */
    static final int MAX_STDOUT_BYTES = (4 << 20) + 4096;
    private static final int MAX_LINE_BYTES = 4096;
    private static final long STDERR_JOIN_MS = 2000;

    /** How one run ended. */
    public static final class Result {
        public final int exitCode;
        public final byte[] stdout;
        /** The last stderr line, or "" when it printed none. */
        public final String lastErrorLine;
        /** The watchdog destroyed the process. */
        public final boolean timedOut;

        Result(int exitCode, byte[] stdout, String lastErrorLine, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.lastErrorLine = lastErrorLine;
            this.timedOut = timedOut;
        }
    }

    private static ScheduledExecutorService watchdog; // guarded by HelperProcess.class

    private HelperProcess() {}

    private static synchronized ScheduledExecutorService watchdog() {
        if (watchdog == null) {
            watchdog = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "overhear-fetch-watchdog");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return watchdog;
    }

    /**
     * overhear-fetch args with env added to its environment (null for none)
     * and stdin (null for none). Throws when the helper is not installed,
     * cannot start, or printed more than MAX_STDOUT_BYTES; otherwise returns
     * whatever exit code it ended with.
     */
    public static Result run(RuntimeInstaller installer, List<String> args, Map<String, String> env,
            byte[] stdin, long timeoutMs) throws IOException {
        File helper = installer.helperBin();
        if (!helper.canExecute()) throw new IOException("overhear-fetch is not installed yet");
        List<String> cmd = new ArrayList<String>();
        cmd.add(helper.getPath());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(installer.runtimeDir())
                .redirectErrorStream(false);
        if (env != null) pb.environment().putAll(env);
        final Process p = pb.start();
        final AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> kill = watchdog().schedule(new Runnable() {
            @Override public void run() {
                timedOut.set(true);
                p.destroy();
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        final String[] lastErr = {""};
        final InputStream err = p.getErrorStream();
        Thread errReader = new Thread(new Runnable() {
            @Override public void run() {
                LineReader r = new LineReader(err, MAX_LINE_BYTES);
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        synchronized (lastErr) {
                            lastErr[0] = line;
                        }
                    }
                } catch (IOException e) {
                    // process gone
                } finally {
                    closeQuietly(err);
                }
            }
        }, "overhear-fetch-stderr");
        errReader.setDaemon(true);
        errReader.start();
        byte[] out;
        int code;
        boolean finished = false;
        try {
            OutputStream in = p.getOutputStream();
            try {
                if (stdin != null) in.write(stdin);
            } catch (IOException e) {
                // the helper exited early; its exit code and stderr say why
            } finally {
                closeQuietly(in);
            }
            out = readAll(p.getInputStream());
            if (out == null) {
                p.destroy();
                throw new IOException("overhear-fetch printed more than " + MAX_STDOUT_BYTES + " bytes");
            }
            code = p.waitFor();
            finished = true;
        } catch (InterruptedException e) {
            p.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } finally {
            // Not after waitFor(): destroy() is a bare kill by pid on 4.3, and
            // a reaped helper's pid may already belong to another process.
            if (!finished) p.destroy();
            kill.cancel(false);
            closeQuietly(p.getInputStream());
            try {
                errReader.join(STDERR_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String last;
        synchronized (lastErr) {
            last = lastErr[0];
        }
        return new Result(code, out, last, timedOut.get());
    }

    /** All of in, or null past MAX_STDOUT_BYTES. */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (b.size() + n > MAX_STDOUT_BYTES) return null;
            b.write(buf, 0, n);
        }
        return b.toByteArray();
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
