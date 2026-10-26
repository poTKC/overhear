package jp.tkc.st15iplayer;

import java.util.List;

import jp.tkc.st15iplayer.soloist.PlayerState;

/**
 * What the player screen says: the status line and its tap action, track
 * text and times. Pure Java (host tests).
 */
final class PlayerText {
    /** What tapping the status line does. */
    enum Action { NONE, START, RETRY, SETUP }

    static final class Status {
        final String text;
        final Action action;

        Status(String text, Action action) {
            this.text = text;
            this.action = action;
        }
    }

    static final String CONNECTED = "Spotify 接続中";
    static final String TAP_TO_START = "停止中 — タップで開始";
    static final String TAP_TO_RETRY = " — タップで再試行";
    static final String LOGIN_ERROR = "ログインエラー — タップで設定";

    private PlayerText() {}

    /** "スマホの Spotify で『name』を選んでください" */
    static String selectDevice(String name) {
        return "スマホの Spotify で『" + name
                + "』を選んでください";
    }

    /**
     * The status line.
     *
     * @param serviceUp whether PlayerService runs (when false the rest is ignored)
     * @param state the service's Supervisor state
     * @param serviceStatus the service's own status text (starting, downloading, failed ...)
     * @param authError the last login/key error from Soloist, or null
     * @param connected whether the service's client is connected to Soloist
     * @param deviceName the configured device name
     */
    static Status status(boolean serviceUp, Supervisor.State state, String serviceStatus,
            String authError, boolean connected, PlayerState ps, String deviceName) {
        if (!serviceUp) return new Status(TAP_TO_START, Action.START);
        if (state == Supervisor.State.FAILED) return new Status(serviceStatus + TAP_TO_RETRY, Action.RETRY);
        if (authError != null) return new Status(LOGIN_ERROR, Action.SETUP);
        if (connected) {
            if (ps.isLoggedIn() && ps.isActive()) return new Status(CONNECTED, Action.NONE);
            return new Status(selectDevice(deviceName), Action.NONE);
        }
        return new Status(serviceStatus == null ? "" : serviceStatus, Action.NONE);
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
