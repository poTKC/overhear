package com.potkc.overhear;

import java.util.List;

import com.potkc.overhear.soloist.PlayerState;

/**
 * What the player screen says: the status line and its tap action, track
 * text and times. Pure Java (host tests).
 */
final class PlayerText {
    /** What tapping the status line (or the service menu item) does. */
    enum Action { NONE, START, RETRY, SETUP, STOP }

    static final class Status {
        final String text;
        final Action action;

        Status(String text, Action action) {
            this.text = text;
            this.action = action;
        }
    }

    /** What the service is doing, for the on-screen status (its notification text stays Japanese). */
    enum Phase {
        STARTING, UPDATING, DOWNLOADING, EXTRACTING, CONNECTING, WAIT_WIFI, RESTARTING, START_FAILED, STOPPED,
        LOGIN_ERROR, READY
    }

    // The on-screen status line, short and in English (bottom-left of the player).
    static final String CONNECTED = "Connected";
    static final String WAITING = "Waiting\u2026";
    static final String TAP_TO_START = "Stopped \u2014 tap to start";
    static final String TAP_TO_RETRY = "Failed \u2014 tap to retry";
    static final String CHECK_KEY = "Check API key \u2014 tap";
    static final String LOGIN_ERROR = "Login error \u2014 tap";
    static final String STARTING = "Starting\u2026";
    static final String UPDATING = "Updating\u2026";
    static final String UNPACKING = "Unpacking\u2026";
    static final String WIFI_OFF = "Wi-Fi off";
    static final String START_FAILED = "Start failed";
    static final String RECONNECTING = "Reconnecting\u2026";
    // The menu stays Japanese, like the rest of the app.
    static final String STOP = "停止";
    static final String START = "開始";

    private PlayerText() {}

    /** For the notification: "スマホの Spotify で『name』を選んでください" */
    static String selectDevice(String name) {
        return "スマホの Spotify で『" + name
                + "』を選んでください";
    }


    /**
     * The status line.
     *
     * @param serviceUp whether PlayerService runs (when false the rest is ignored)
     * @param state the service's Supervisor state
     * @param needsSetup Supervisor.needsSetup(): FAILED over the key, setup can fix it
     * @param phase what the service last reported (starting, downloading ...), or null
     * @param value the phase's number: download percent, seconds to the restart
     * @param authError the last login/key error from Soloist, or null
     * @param connected whether the service's client is connected to Soloist
     * @param deviceName the configured device name (no longer shown on the screen)
     */
    static Status status(boolean serviceUp, Supervisor.State state, boolean needsSetup, Phase phase, int value,
            String authError, boolean connected, PlayerState ps, String deviceName) {
        if (!serviceUp) return new Status(TAP_TO_START, Action.START);
        if (state == Supervisor.State.FAILED && needsSetup) return new Status(CHECK_KEY, Action.SETUP);
        if (state == Supervisor.State.FAILED) return new Status(TAP_TO_RETRY, Action.RETRY);
        if (authError != null) return new Status(LOGIN_ERROR, Action.SETUP);
        if (connected) {
            // Playing and paused both read "Connected"; the play button shows which.
            if (ps.isLoggedIn() && ps.isActive()) return new Status(CONNECTED, Action.NONE);
            return new Status(WAITING, Action.NONE);
        }
        return new Status(phaseText(phase, value), Action.NONE);
    }

    private static String phaseText(Phase phase, int value) {
        if (phase == null) return STARTING;
        switch (phase) {
            case UPDATING:
                return UPDATING;
            case DOWNLOADING:
                return "Downloading " + value + "%";
            case EXTRACTING:
                return UNPACKING;
            case WAIT_WIFI:
                return WIFI_OFF;
            case RESTARTING:
                return "Restarting in " + value + " s";
            case START_FAILED:
                return START_FAILED;
            case READY:
                return RECONNECTING;
            case LOGIN_ERROR:
                return LOGIN_ERROR;
            case STOPPED:
                return TAP_TO_RETRY;
            default:
                return STARTING;
        }
    }

    /**
     * The menu's service item (Menu key, long-press on settings): stop while
     * the service runs, whatever its state (starting, failed ...), else start.
     * After a stop the status line reads TAP_TO_START (status()).
     */
    static Action serviceMenuAction(boolean serviceUp) {
        return serviceUp ? Action.STOP : Action.START;
    }

    static String serviceMenuLabel(boolean serviceUp) {
        return serviceUp ? STOP : START;
    }

    /** Shuffle: the controls work and Soloist allows it here (available_actions). */
    static boolean shuffleEnabled(boolean connected, PlayerState ps) {
        return controlsEnabled(connected, ps) && ps.canShuffle();
    }

    /** Repeat: the controls work and Soloist allows it here (available_actions). */
    static boolean repeatEnabled(boolean connected, PlayerState ps) {
        return controlsEnabled(connected, ps) && ps.canRepeat();
    }

    /** The repeat button's level in ic_repeat_levels: off, repeat (context), repeat one (track). */
    static int repeatLevel(PlayerState.Repeat r) {
        if (r == PlayerState.Repeat.TRACK) return 2;
        if (r == PlayerState.Repeat.CONTEXT) return 1;
        return 0;
    }

    /** Queue entries take a tap (a jump) when the controls work and no jump runs. */
    static boolean queueTappable(boolean connected, PlayerState ps, boolean jumping) {
        return controlsEnabled(connected, ps) && !jumping;
    }

    /** Playback controls work only on the active device. */
    static boolean controlsEnabled(boolean connected, PlayerState ps) {
        return connected && ps.isActive();
    }

    /** "A, B · Album", leaving out what is missing. */
    static String subtitle(List<String> artists, String album) {
        StringBuilder sb = new StringBuilder();
        for (String a : artists) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(a);
        }
        if (album != null && !album.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(album);
        }
        return sb.toString();
    }

    /** m:ss, or h:mm:ss from an hour. */
    static String time(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = s / 60 % 60, sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append(':').append(m < 10 ? "0" : "");
        sb.append(m).append(':').append(sec < 10 ? "0" : "").append(sec);
        return sb.toString();
    }
}
