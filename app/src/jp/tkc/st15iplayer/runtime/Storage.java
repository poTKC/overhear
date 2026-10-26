package jp.tkc.st15iplayer.runtime;

/**
 * Disk budget decisions for /data, which is small on the ST15i (420 MB,
 * shared with every app). Pure Java so it runs in the host tests.
 */
public final class Storage {
    private static final long MB = 1024L * 1024L;
    /** Soloist's minimum -z value; also the most it may take of /data. */
    public static final int CACHE_MB = 100;
    /**
     * Free space a Soloist download needs: the tarball (~12 MB) and the
     * unpacked copy (~30 MB) exist next to the old copy until the swap, plus
     * headroom so the rest of the system is not starved.
     */
    public static final int DOWNLOAD_MIN_FREE_MB = 80;

    private Storage() {}

    /**
     * The -z value: clamp(free / 2, CACHE_MB, CACHE_MB). Always Soloist's
     * minimum, since there is no room to grow on this phone; when even that is
     * more than half the free space, isLowForCache says so.
     */
    public static int cacheSizeMb(long freeBytes) {
        return CACHE_MB;
    }

    /** True when the cache could take more than half of the free space. */
    public static boolean isLowForCache(long freeBytes) {
        return freeBytes / 2 < CACHE_MB * MB;
    }

    /** null when a Soloist download fits, else the reason to show. */
    public static String downloadError(long freeBytes) {
        if (freeBytes >= DOWNLOAD_MIN_FREE_MB * MB) return null;
        return "not enough storage to download Soloist: " + Math.max(0, freeBytes) / MB
                + " MB free, " + DOWNLOAD_MIN_FREE_MB + " MB needed";
    }
}
