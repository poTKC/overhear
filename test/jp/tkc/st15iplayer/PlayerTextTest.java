package jp.tkc.st15iplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import jp.tkc.st15iplayer.soloist.PlayerState;

import org.json.JSONObject;
import org.junit.Test;

public class PlayerTextTest {
    private static PlayerState state(boolean loggedIn, boolean active) throws Exception {
        PlayerState s = new PlayerState();
        s.apply(new JSONObject("{\"type\":\"auth_state\",\"logged_in\":" + loggedIn
                + ",\"is_active\":" + active + ",\"device_name\":\"Kitchen\"}"));
        return s;
    }

    @Test public void serviceNotRunningOffersStart() {
        PlayerText.Status s = PlayerText.status(false, null, false, null, null, false, new PlayerState(), "ST15i");
        assertEquals(PlayerText.Action.START, s.action);
        assertTrue(s.text, s.text.contains("タップで開始"));
    }

    @Test public void failedOffersRetryWithTheReason() {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.FAILED, false, "停止: x",
                null, false, new PlayerState(), "ST15i");
        assertEquals(PlayerText.Action.RETRY, s.action);
        assertEquals("停止: x — タップで再試行", s.text);
    }

    @Test public void failedWinsOverAStaleConnection() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.FAILED, false, "x", null, true,
                state(true, true), "ST15i");
        assertEquals(PlayerText.Action.RETRY, s.action);
    }

    @Test public void authErrorOpensSetup() {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.RUNNING, false, "y", "login failed",
                true, new PlayerState(), "ST15i");
        assertEquals(PlayerText.Action.SETUP, s.action);
        assertTrue(s.text, s.text.startsWith("ログインエラー"));
    }

    @Test public void connectedButNotActiveAsksToSelectTheDevice() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.RUNNING, false, "y", null, true,
                state(false, false), "ST15i");
        assertEquals(PlayerText.Action.NONE, s.action);
        assertEquals("スマホの Spotify で『ST15i』を選んでください", s.text);
        // Logged in but another device plays: the same.
        s = PlayerText.status(true, Supervisor.State.RUNNING, false, "y", null, true, state(true, false), "ST15i");
        assertTrue(s.text, s.text.contains("『ST15i』"));
    }

    @Test public void activeIsConnected() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.RUNNING, false, "y", null, true,
                state(true, true), "ST15i");
        assertEquals("Spotify 接続中", s.text);
        assertEquals(PlayerText.Action.NONE, s.action);
    }

    @Test public void otherwiseTheServiceStatus() {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.STARTING, false,
                "Soloist をダウンロード中… 62%", null, false,
                new PlayerState(), "ST15i");
        assertEquals("Soloist をダウンロード中… 62%", s.text);
        assertEquals(PlayerText.Action.NONE, s.action);
    }

    @Test public void controlsNeedAConnectedActiveDevice() throws Exception {
        assertTrue(PlayerText.controlsEnabled(true, state(true, true)));
        assertFalse(PlayerText.controlsEnabled(false, state(true, true)));
        assertFalse(PlayerText.controlsEnabled(true, state(true, false)));
    }

    @Test public void subtitleJoinsArtistsAndAlbum() {
        assertEquals("A, B · Album", PlayerText.subtitle(Arrays.asList("A", "B"), "Album"));
        assertEquals("A", PlayerText.subtitle(Arrays.asList("A"), null));
        assertEquals("Album", PlayerText.subtitle(Collections.<String>emptyList(), "Album"));
        assertEquals("", PlayerText.subtitle(Collections.<String>emptyList(), ""));
    }

    @Test public void formatsTimes() {
        assertEquals("0:00", PlayerText.time(0));
        assertEquals("0:00", PlayerText.time(-5));
        assertEquals("0:59", PlayerText.time(59999));
        assertEquals("3:05", PlayerText.time(185000));
        assertEquals("1:00:00", PlayerText.time(3600000));
    }

    @Test public void aFailureOfTheSettingsOpensSetupNotRetry() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.FAILED, true,
                "\u505c\u6b62: API \u30ad\u30fc\u304c\u3042\u308a\u307e\u305b\u3093", null, false,
                new PlayerState(), "ST15i");
        assertEquals(PlayerText.Action.SETUP, s.action);
        assertEquals("\u30ad\u30fc\u3092\u78ba\u8a8d\u3057\u3066\u304f\u3060\u3055\u3044"
                + " \u2014 \u30bf\u30c3\u30d7\u3067\u8a2d\u5b9a", s.text);
    }

    @Test public void serviceMenuStopsARunningService() {
        assertEquals(PlayerText.Action.STOP, PlayerText.serviceMenuAction(true));
        assertEquals("停止", PlayerText.serviceMenuLabel(true));
    }

    @Test public void serviceMenuStartsAStoppedService() {
        assertEquals(PlayerText.Action.START, PlayerText.serviceMenuAction(false));
        assertEquals("開始", PlayerText.serviceMenuLabel(false));
    }

    @Test public void afterAStopTheStatusLineOffersStartEvenWithStaleState() throws Exception {
        // The service went away: whatever it reported last no longer counts.
        PlayerText.Status s = PlayerText.status(false, Supervisor.State.FAILED, true, "停止: x", "login failed",
                true, state(true, true), "ST15i");
        assertEquals(PlayerText.Action.START, s.action);
        assertEquals("停止中 — タップで開始", s.text);
        assertEquals(PlayerText.Action.START, PlayerText.serviceMenuAction(false));
    }
}
