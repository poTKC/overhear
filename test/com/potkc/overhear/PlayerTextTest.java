package com.potkc.overhear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import com.potkc.overhear.soloist.PlayerState;
import com.potkc.overhear.spotify.LibraryPath;

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

    @Test public void connectedButNotActiveIsStandby() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.RUNNING, false, PlayerText.Phase.READY, 0,
                null, true, state(false, false), "ST15i");
        assertEquals(PlayerText.Action.NONE, s.action);
        assertEquals("Standby", s.text);
        // Logged in but another device plays: the same.
        s = PlayerText.status(true, Supervisor.State.RUNNING, false, PlayerText.Phase.READY, 0, null, true,
                state(true, false), "Kitchen");
        assertEquals("Standby", s.text);
    }

    @Test public void noTrackTextFollowsTheStatusLine() throws Exception {
        PlayerText.Status s = PlayerText.status(false, null, false, null, 0, null, false, new PlayerState(), "ST15i");
        assertEquals("Stopped", PlayerText.noTrackText(s));
        s = PlayerText.status(true, Supervisor.State.RUNNING, false, PlayerText.Phase.READY, 0, null, true,
                state(true, false), "ST15i");
        assertEquals("Standby", PlayerText.noTrackText(s));
        s = PlayerText.status(true, Supervisor.State.RUNNING, false, PlayerText.Phase.READY, 0, null, true,
                state(false, false), "ST15i");
        assertEquals("Standby", PlayerText.noTrackText(s));
    }

    @Test public void noTrackTextIsTheResourceOtherwise() throws Exception {
        // Active with no track, starting, failed, a login error: "再生中の曲はありません".
        assertNull(PlayerText.noTrackText(PlayerText.status(true, Supervisor.State.RUNNING, false,
                PlayerText.Phase.READY, 0, null, true, state(true, true), "ST15i")));
        assertNull(PlayerText.noTrackText(running(null, 0)));
        assertNull(PlayerText.noTrackText(running(PlayerText.Phase.DOWNLOADING, 40)));
        assertNull(PlayerText.noTrackText(PlayerText.status(true, Supervisor.State.FAILED, false,
                PlayerText.Phase.STOPPED, 0, null, true, state(true, false), "ST15i")));
        assertNull(PlayerText.noTrackText(PlayerText.status(true, Supervisor.State.RUNNING, false,
                PlayerText.Phase.LOGIN_ERROR, 0, "login failed", true, state(false, false), "ST15i")));
    }

    @Test public void activeIsConnectedWhetherPlayingOrPaused() throws Exception {
        PlayerText.Status s = PlayerText.status(true, Supervisor.State.RUNNING, false, PlayerText.Phase.READY, 0,
                null, true, playing("playing"), "ST15i");
        assertEquals("Connected", s.text);
        assertEquals(PlayerText.Action.NONE, s.action);
        s = PlayerText.status(true, Supervisor.State.RUNNING, false, PlayerText.Phase.READY, 0, null, true,
                playing("paused"), "ST15i");
        assertEquals("Connected", s.text);
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

    private static PlayerState withActions(boolean active, String... actions) throws Exception {
        PlayerState s = state(true, active);
        JSONObject e = new JSONObject("{\"type\":\"playback_state\",\"status\":\"playing\",\"is_active\":"
                + active + ",\"available_actions\":{}}");
        for (String a : actions) e.getJSONObject("available_actions").put(a, new JSONObject());
        s.apply(e);
        return s;
    }

    @Test public void shuffleAndRepeatNeedTheirActions() throws Exception {
        PlayerState all = withActions(true, "pause", "shuffle", "set_repeat");
        assertTrue(PlayerText.shuffleEnabled(true, all));
        assertTrue(PlayerText.repeatEnabled(true, all));
        PlayerState radio = withActions(true, "pause", "seek", "skip_next", "skip_prev", "add_to_queue");
        assertFalse(PlayerText.shuffleEnabled(true, radio));
        assertFalse(PlayerText.repeatEnabled(true, radio));
        PlayerState onlyShuffle = withActions(true, "shuffle");
        assertTrue(PlayerText.shuffleEnabled(true, onlyShuffle));
        assertFalse(PlayerText.repeatEnabled(true, onlyShuffle));
    }

    @Test public void shuffleAndRepeatAlsoNeedTheControls() throws Exception {
        PlayerState all = withActions(true, "shuffle", "set_repeat");
        assertFalse(PlayerText.shuffleEnabled(false, all)); // not connected
        assertFalse(PlayerText.repeatEnabled(false, all));
        PlayerState inactive = withActions(false, "shuffle", "set_repeat");
        assertFalse(PlayerText.shuffleEnabled(true, inactive)); // another device plays
    }

    @Test public void withoutAnActionListShuffleAndRepeatFollowTheControls() throws Exception {
        assertTrue(PlayerText.shuffleEnabled(true, state(true, true)));
        assertTrue(PlayerText.repeatEnabled(true, state(true, true)));
    }

    @Test public void repeatPicksTheDrawableLevel() {
        assertEquals(0, PlayerText.repeatLevel(PlayerState.Repeat.OFF)); // grey, no dot
        assertEquals(1, PlayerText.repeatLevel(PlayerState.Repeat.CONTEXT)); // green, dot
        assertEquals(2, PlayerText.repeatLevel(PlayerState.Repeat.TRACK)); // repeat_one, green, dot
    }

    @Test public void controlsNeedAConnectedActiveDevice() throws Exception {
        assertTrue(PlayerText.controlsEnabled(true, state(true, true)));
        assertFalse(PlayerText.controlsEnabled(false, state(true, true)));
        assertFalse(PlayerText.controlsEnabled(true, state(true, false)));
        assertFalse(PlayerText.controlsEnabled(true, state(false, true))); // as the service's soloistActive
    }

    @Test public void queueRowsTakeTapsLikeTheControlsButNotDuringAJump() throws Exception {
        assertTrue(PlayerText.queueTappable(true, state(true, true), false, false, false));
        assertFalse(PlayerText.queueTappable(true, state(true, true), true, false, false));
        assertFalse(PlayerText.queueTappable(false, state(true, true), false, false, false));
        assertFalse(PlayerText.queueTappable(true, state(true, false), false, false, false));
        PlayerState stale = state(true, true);
        stale.apply(new JSONObject("{\"type\":\"track_changed\",\"item\":{\"uri\":\"spotify:track:a\"}}"));
        assertFalse(PlayerText.queueTappable(true, stale, false, false, false));
        stale.apply(new JSONObject("{\"type\":\"queue_changed\",\"upcoming\":[]}"));
        assertTrue(PlayerText.queueTappable(true, stale, false, false, false));
    }

    @Test public void whileSoloistWaitsQueueRowsPlayHereThroughTheWebApi() throws Exception {
        assertTrue(PlayerText.queueTappable(true, state(true, false), false, true, false));
        assertFalse(PlayerText.queueTappable(true, state(true, false), false, true, true)); // one in flight
        assertFalse(PlayerText.queueTappable(true, state(true, false), true, true, false));
        assertFalse(PlayerText.queueTappable(false, state(true, false), false, true, false));
        assertFalse(PlayerText.queueTappable(true, state(true, false), false, false, false)); // no login: no skipping
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

    @Test public void serviceButtonStopsARunningService() {
        assertEquals(PlayerText.Action.STOP, PlayerText.serviceAction(true));
        assertEquals("停止", PlayerText.serviceLabel(true));
    }

    @Test public void serviceButtonStartsAStoppedService() {
        assertEquals(PlayerText.Action.START, PlayerText.serviceAction(false));
        assertEquals("開始", PlayerText.serviceLabel(false));
    }

    @Test public void afterAStopTheStatusLineOffersStartEvenWithStaleState() throws Exception {
        // The service went away: whatever it reported last no longer counts.
        PlayerText.Status s = PlayerText.status(false, Supervisor.State.FAILED, true, PlayerText.Phase.STOPPED, 0,
                "login failed", true, state(true, true), "ST15i");
        assertEquals(PlayerText.Action.START, s.action);
        assertEquals("Stopped \u2014 tap to start", s.text);
        assertEquals(PlayerText.Action.START, PlayerText.serviceAction(false));
    }

    // --- the ⋮ menu ---

    /** A playback_state of spotify:track:T on album A (if albumUri) by the creators given as "uri=name" ("=name": no uri). */
    private static PlayerState track(String albumUri, String... creators) throws Exception {
        org.json.JSONArray c = new org.json.JSONArray();
        for (String e : creators) {
            String[] p = e.split("=", -1);
            JSONObject entity = new JSONObject().put("decorations",
                    new JSONObject().put("identity", new JSONObject().put("name", p[1])));
            if (!p[0].isEmpty()) entity.put("uri", p[0]);
            c.put(new JSONObject().put("entity", entity));
        }
        JSONObject d = new JSONObject().put("identity", new JSONObject().put("name", "Title")).put("creators", c);
        if (albumUri != null) {
            d.put("parent", new JSONObject().put("entity", new JSONObject().put("uri", albumUri)
                    .put("decorations", new JSONObject().put("identity", new JSONObject().put("name", "Album")))));
        }
        PlayerState s = state(true, false); // Soloist logged in, waiting
        s.apply(new JSONObject().put("type", "playback_state").put("status", "playing")
                .put("item", new JSONObject().put("uri", "spotify:track:T").put("decorations", d)));
        return s;
    }

    @Test public void trackLinksAreTheAlbumThenTheArtists() throws Exception {
        PlayerState s = track("spotify:album:A", "spotify:artist:R=Daft Punk", "=No Uri", "spotify:artist:S=Romanthony");
        assertEquals(Arrays.asList(LibraryPath.Node.album("spotify:album:A", "Album", null),
                        LibraryPath.Node.artist("spotify:artist:R", "Daft Punk"),
                        LibraryPath.Node.artist("spotify:artist:S", "Romanthony")),
                PlayerText.trackLinks(true, s));
    }

    @Test public void noTrackLinksWithoutAPlayerOrATrack() throws Exception {
        assertTrue(PlayerText.trackLinks(false, track("spotify:album:A", "spotify:artist:R=R")).isEmpty());
        assertTrue(PlayerText.trackLinks(true, new PlayerState()).isEmpty());
        assertTrue(PlayerText.trackLinks(true, track(null, "=No Uri")).isEmpty());
    }

    @Test public void trackLinksWithOnlySomeUris() throws Exception {
        assertEquals(Collections.singletonList(LibraryPath.Node.artist("spotify:artist:R", "R")),
                PlayerText.trackLinks(true, track(null, "spotify:artist:R=R")));
    }

    // --- ▶ while Soloist waits ---

    @Test public void playWorksWhileActiveOrWhenThereIsSomethingToResume() throws Exception {
        PlayerState waiting = track("spotify:album:A", "spotify:artist:R=R"); // not active
        assertTrue(PlayerText.playEnabled(true, waiting, false, true, false));
        assertTrue(PlayerText.playEnabled(true, waiting, false, false, false)); // Soloist's play from the top
        assertFalse(PlayerText.playEnabled(true, waiting, false, true, true)); // one in flight
        assertFalse(PlayerText.playEnabled(false, waiting, false, true, false)); // no player
        assertFalse(PlayerText.playEnabled(true, new PlayerState(), false, true, false)); // nothing to resume
        PlayerState active = playing("playing");
        assertTrue(PlayerText.playEnabled(true, active, false, false, false));
        assertFalse(PlayerText.playEnabled(true, active, true, false, false)); // a queue jump skips
    }

    @Test public void playStaysOffWhileSoloistIsLoggedOut() throws Exception {
        PlayerState s = track("spotify:album:A", "spotify:artist:R=R");
        s.apply(new JSONObject("{\"type\":\"auth_state\",\"logged_in\":false,\"is_active\":false}"));
        assertFalse(PlayerText.playEnabled(true, s, false, true, false));
    }
}
