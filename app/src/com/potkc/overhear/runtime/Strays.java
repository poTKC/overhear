package com.potkc.overhear.runtime;

/**
 * Recognises Soloist processes that an earlier app process left behind. On
 * Android 4.3 a force-stop or crash kills the app but not its children, and a
 * survivor keeps the WebSocket port and the speaker name. Pure Java so it runs
 * in the host tests.
 */
final class Strays {
    private Strays() {}

    /**
     * The ppid field of /proc/<pid>/stat ("pid (comm) state ppid ..."), or -1.
     * comm may contain spaces and parentheses, so parsing starts after the
     * last ')'.
     */
    static int parsePpid(String stat) {
        int close = stat.lastIndexOf(')');
        if (close < 0) return -1;
        String[] f = stat.substring(close + 1).trim().split(" +");
        if (f.length < 2) return -1;
        try {
            return Integer.parseInt(f[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * True when the process runs our runtime's loader (argv0 == loaderPath)
     * and is not ours to keep: not a child of this app process (selfPid) and
     * not the run we are tracking (currentPid, -1 for none). An unreadable
     * ppid (-1) is never a stray.
     */
    static boolean isStray(String cmdline, String loaderPath, int pid, int ppid, int selfPid, int currentPid) {
        if (ppid < 0 || ppid == selfPid || pid == currentPid) return false;
        int end = cmdline.indexOf('\0');
        String argv0 = end < 0 ? cmdline : cmdline.substring(0, end);
        return argv0.equals(loaderPath);
    }
}
