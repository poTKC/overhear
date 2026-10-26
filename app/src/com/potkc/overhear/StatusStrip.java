package com.potkc.overhear;

/**
 * The player's top strip in full-screen mode (the status bar is hidden):
 * the time on the left, a battery dot and number on the right, both in the
 * Rubik digits subset (assets/fonts, tools/make_fonts.py). Pure Java
 * (host tests).
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

    /**
     * The characters in assets/fonts/overhear-digits.ttf (tools/make_fonts.py
     * subsets to the same set): clock, battery and seek times only use these.
     */
    static final String DIGITS_FONT_CHARS = "0123456789: ";

    private StatusStrip() {}

    /**
     * H:mm, or h:mm in 12-hour mode (system setting), without AM/PM: the
     * locale's marker (午前/午後 here) is not in the digits font.
     */
    static String clockText(int hour, int minute, boolean is24) {
        int h = is24 ? hour : (hour % 12 == 0 ? 12 : hour % 12);
        return h + ":" + (minute < 10 ? "0" : "") + minute;
    }

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
        return percent < 0 ? "" : String.valueOf(percent);
    }

    static int batteryColor(int percent, boolean charging) {
        if (charging) return CHARGING;
        if (percent < 0) return UNKNOWN;
        if (percent >= 50) return GREEN;
        if (percent >= 20) return AMBER;
        return RED;
    }
}
