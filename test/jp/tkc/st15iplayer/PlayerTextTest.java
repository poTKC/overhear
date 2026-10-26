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
        PlayerText.Status s = PlayerText.status(false, null, null, null, false, new PlayerState(), "ST15i");
        assertEquals(PlayerText.Action.START, s.action);
        assertTrue(s.text, s.text.contains("タップで開始"));
    }

    @Test public void failedOffersRetryWithTheReason() {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.FAILED, "停止: x",
                null, false, new PlayerState(), "ST15i");
        assertEquals(PlayerText.Action.RETRY, s.action);
        assertEquals("停止: x — タップで再試行", s.text);
    }

    @Test public void failedWinsOverAStaleConnection() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.FAILED, "x", null, true,
                state(true, true), "ST15i");
        assertEquals(PlayerText.Action.RETRY, s.action);
    }

    @Test public void authErrorOpensSetup() {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.RUNNING, "y", "login failed",
                true, new PlayerState(), "ST15i");
        assertEquals(PlayerText.Action.SETUP, s.action);
        assertTrue(s.text, s.text.startsWith("ログインエラー"));
    }

    @Test public void connectedButNotActiveAsksToSelectTheDevice() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.RUNNING, "y", null, true,
                state(false, false), "ST15i");
        assertEquals(PlayerText.Action.NONE, s.action);
        assertEquals("スマホの Spotify で『ST15i』を選んでください", s.text);
        // Logged in but another device plays: the same.
        s = PlayerText.status(true, Supervisor.State.RUNNING, "y", null, true, state(true, false), "ST15i");
        assertTrue(s.text, s.text.contains("『ST15i』"));
    }

    @Test public void activeIsConnected() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.RUNNING, "y", null, true,
                state(true, true), "ST15i");
        assertEquals("Spotify 接続中", s.text);
        assertEquals(PlayerText.Action.NONE, s.action);
    }

    @Test public void otherwiseTheServiceStatus() {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.STARTING,
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
}
