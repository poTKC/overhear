package jp.tkc.st15iplayer;

/**
 * The player's top strip in full-screen mode (the status bar is hidden):
 * the time on the left, a battery dot and percent on the right. The time is
 * formatted by the activity (system 12/24 h setting); this holds the battery
 * rules. Pure Java (host tests).
 */
final class StatusStrip {
    /** 50 % or more. */
    static final int GREEN = 0xff66bb6a;
    /** 20-49 %. */
    static final int AMBER = 0xffffb300;
    /** Below 20 %. */
    static final int RED = 0xffef5350;
    /** Charging, whatever the level. */
    static final int CHARGING = 0xff4fc3f7;
    static final int UNKNOWN = 0xff666666;

    private StatusStrip() {}

    /** Shown only in full screen: otherwise the real status bar is visible. */
    static boolean visible(boolean fullscreen) {
        return fullscreen;
    }

    /** ACTION_BATTERY_CHANGED level/scale as 0..100, or -1 if unknown. */
    static int percent(int level, int scale) {
        if (level < 0 || scale <= 0) return -1;
        return Math.min(100, (int) Math.round(level * 100.0 / scale));
    }

    static String batteryText(int percent) {
        return percent < 0 ? "" : percent + "%";
    }

    static int batteryColor(int percent, boolean charging) {
        if (charging) return CHARGING;
        if (percent < 0) return UNKNOWN;
        if (percent >= 50) return GREEN;
        if (percent >= 20) return AMBER;
        return RED;
    }
}
