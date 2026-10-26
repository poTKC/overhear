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

    private static PlayerState playing(String status) throws Exception {
        PlayerState s = state(true, true);
        s.apply(new JSONObject("{\"type\":\"playback_changed\",\"status\":\"" + status + "\"}"));
        return s;
    }

    private static PlayerText.Status running(PlayerText.Phase phase, int value) {
        return PlayerText.status(true, Supervisor.State.STARTING, false, phase, value, null, false,
                new PlayerState(), "ST15i");
    }

    @Test public void serviceNotRunningOffersStart() {
        PlayerText.Status s = PlayerText.status(false, null, false, null, 0, null, false, new PlayerState(), "ST15i");
        assertEquals(PlayerText.Action.START, s.action);
        assertEquals("Stopped \u2014 tap to start", s.text);
    }

    @Test public void failedOffersRetry() {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.FAILED, false, PlayerText.Phase.STOPPED, 0,
                null, false, new PlayerState(), "ST15i");
        assertEquals(PlayerText.Action.RETRY, s.action);
        assertEquals("Failed \u2014 tap to retry", s.text);
    }

    @Test public void failedWinsOverAStaleConnection() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.FAILED, false, PlayerText.Phase.STOPPED, 0,
                null, true, state(true, true), "ST15i");
        assertEquals(PlayerText.Action.RETRY, s.action);
    }

    @Test public void authErrorOpensSetup() {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.RUNNING, false, PlayerText.Phase.LOGIN_ERROR,
                0, "login failed", true, new PlayerState(), "ST15i");
        assertEquals(PlayerText.Action.SETUP, s.action);
        assertEquals("Login error \u2014 tap", s.text);
    }

    @Test public void connectedButNotActiveAsksToSelectTheDevice() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.RUNNING, false, PlayerText.Phase.READY, 0,
                null, true, state(false, false), "ST15i");
        assertEquals(PlayerText.Action.NONE, s.action);
        assertEquals("Select \"ST15i\" in Spotify", s.text);
        // Logged in but another device plays: the same, with the configured name.
        s = PlayerText.status(true, Supervisor.State.RUNNING, false, PlayerText.Phase.READY, 0, null, true,
                state(true, false), "Kitchen");
        assertEquals("Select \"Kitchen\" in Spotify", s.text);
    }

    @Test public void activeIsConnectedAndSaysWhenPaused() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.RUNNING, false, PlayerText.Phase.READY, 0,
                null, true, playing("playing"), "ST15i");
        assertEquals("Connected", s.text);
        assertEquals(PlayerText.Action.NONE, s.action);
        s = PlayerText.status(true, Supervisor.State.RUNNING, false, PlayerText.Phase.READY, 0, null, true,
                playing("paused"), "ST15i");
        assertEquals("Connected \u00b7 Paused", s.text);
    }

    @Test public void otherwiseThePhaseInEnglish() {
        assertEquals("Starting\u2026", running(PlayerText.Phase.STARTING, 0).text);
        assertEquals("Starting\u2026", running(PlayerText.Phase.CONNECTING, 0).text);
        assertEquals("Updating\u2026", running(PlayerText.Phase.UPDATING, 0).text);
        assertEquals("Downloading 62%", running(PlayerText.Phase.DOWNLOADING, 62).text);
        assertEquals("Unpacking\u2026", running(PlayerText.Phase.EXTRACTING, 0).text);
        assertEquals("Restarting in 4 s", running(PlayerText.Phase.RESTARTING, 4).text);
        assertEquals("Wi-Fi off", running(PlayerText.Phase.WAIT_WIFI, 0).text);
        assertEquals("Start failed", running(PlayerText.Phase.START_FAILED, 0).text);
        assertEquals("Reconnecting\u2026", running(PlayerText.Phase.READY, 0).text); // lost the API
        assertEquals("Starting\u2026", running(null, 0).text);
        assertEquals(PlayerText.Action.NONE, running(PlayerText.Phase.DOWNLOADING, 62).action);
    }

    @Test public void theNotificationStillAsksInJapanese() {
        assertEquals("スマホの Spotify で『ST15i』を選んでください", PlayerText.selectDevice("ST15i"));
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
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.FAILED, true, PlayerText.Phase.STOPPED, 0,
                null, false, new PlayerState(), "ST15i");
        assertEquals(PlayerText.Action.SETUP, s.action);
        assertEquals("Check API key \u2014 tap", s.text);
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
        PlayerText.Status s = PlayerText.status(false, Supervisor.State.FAILED, true, PlayerText.Phase.STOPPED, 0,
                "login failed", true, state(true, true), "ST15i");
        assertEquals(PlayerText.Action.START, s.action);
        assertEquals("Stopped \u2014 tap to start", s.text);
        assertEquals(PlayerText.Action.START, PlayerText.serviceMenuAction(false));
    }
}
