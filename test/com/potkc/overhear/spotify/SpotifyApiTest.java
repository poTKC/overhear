package com.potkc.overhear.spotify;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

public class SpotifyApiTest {
    /** Replays queued replies and records requests as "TOKEN form" / "GET /path tok body". */
    static final class FakeTransport implements ApiTransport {
        final Deque<HelperReply> replies = new ArrayDeque<HelperReply>();
        final List<String> log = new ArrayList<String>();

        FakeTransport reply(int status, String body) {
            replies.add(new HelperReply(status, -1, body));
            return this;
        }

        @Override public HelperReply token(String form) throws IOException {
            log.add("TOKEN " + form);
            return next();
        }

        @Override public HelperReply api(String method, String path, String token, String body) throws IOException {
            log.add(method + " " + path + " " + token + (body.isEmpty() ? "" : " " + body));
            return next();
        }

        private HelperReply next() throws IOException {
            if (replies.isEmpty()) throw new IOException("no reply queued");
            return replies.removeFirst();
        }
    }

    static final class MemoryStore implements SpotifyAuth.TokenStore {
        String refresh;
        @Override public String refreshToken() { return refresh; }
        @Override public void saveRefreshToken(String t) { refresh = t; }
        @Override public void clear() { refresh = null; }
    }

    private long now = 1000000;
    private final SpotifyAuth.Clock clock = new SpotifyAuth.Clock() {
        @Override public long nowMs() { return now; }
    };

    private SpotifyApi api(FakeTransport t, MemoryStore store) {
        return new SpotifyApi(t, new SpotifyAuth(t, store, clock, "cid"));
    }

    @Test public void refreshesOnceThenReusesTheAccessToken() throws IOException {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"access_token\":\"A1\",\"expires_in\":3600}")
                .reply(200, "{\"id\":\"potkc\"}")
                .reply(200, "{\"id\":\"potkc\"}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        SpotifyApi a = api(t, s);
        assertEquals("potkc", a.getJson("/me").getString("id"));
        assertEquals("potkc", a.getJson("/me").getString("id"));
        assertEquals("TOKEN grant_type=refresh_token&refresh_token=R&client_id=cid", t.log.get(0));
        assertEquals("GET /me A1", t.log.get(1));
        assertEquals("GET /me A1", t.log.get(2));
        assertEquals(3, t.log.size());
    }

    @Test public void refreshesAMinuteBeforeExpiry() throws IOException {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"access_token\":\"A1\",\"expires_in\":3600}")
                .reply(200, "{}")
                .reply(200, "{\"access_token\":\"A2\",\"expires_in\":3600}")
                .reply(200, "{}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        SpotifyApi a = api(t, s);
        a.getJson("/me");
        now += 3600 * 1000 - 59 * 1000;
        a.getJson("/me");
        assertEquals("GET /me A2", t.log.get(3));
    }

    @Test public void keepsANewRefreshTokenWhenOneIsReturned() throws IOException {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"access_token\":\"A1\",\"expires_in\":3600,\"refresh_token\":\"R2\"}")
                .reply(200, "{}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        api(t, s).getJson("/me");
        assertEquals("R2", s.refresh);
    }

    @Test public void retriesOnceAfter401WithAFreshToken() throws IOException {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"access_token\":\"A1\",\"expires_in\":3600}")
                .reply(401, "{}")
                .reply(200, "{\"access_token\":\"A2\",\"expires_in\":3600}")
                .reply(200, "{\"id\":\"x\"}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        assertEquals("x", api(t, s).getJson("/me").getString("id"));
        assertEquals("GET /me A2", t.log.get(3));
    }

    @Test public void invalidGrantClearsTheLogin() {
        FakeTransport t = new FakeTransport().reply(400, "{\"error\":\"invalid_grant\"}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        try {
            api(t, s).getJson("/me");
            fail();
        } catch (SpotifyException e) {
            assertEquals(SpotifyException.Reason.LOGIN_REQUIRED, e.reason);
        } catch (IOException e) {
            fail(e.toString());
        }
        assertNull(s.refresh);
    }

    @Test public void noRefreshTokenMeansLoginRequired() {
        try {
            api(new FakeTransport(), new MemoryStore()).getJson("/me");
            fail();
        } catch (SpotifyException e) {
            assertEquals(SpotifyException.Reason.LOGIN_REQUIRED, e.reason);
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    @Test public void rateLimitedCarriesRetryAfter() {
        FakeTransport t = new FakeTransport().reply(200, "{\"access_token\":\"A1\",\"expires_in\":3600}");
        t.replies.add(new HelperReply(429, 7, ""));
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        try {
            api(t, s).getJson("/me");
            fail();
        } catch (SpotifyException e) {
            assertEquals(SpotifyException.Reason.RATE_LIMITED, e.reason);
            assertEquals(7, e.retryAfterSeconds);
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    @Test public void exchangeCodeStoresTheRefreshToken() throws IOException {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"access_token\":\"A1\",\"expires_in\":3600,\"refresh_token\":\"R1\"}");
        MemoryStore s = new MemoryStore();
        new SpotifyAuth(t, s, clock, "cid").exchangeCode("C", "V", "https://x/cb/");
        assertEquals("R1", s.refresh);
        assertEquals("TOKEN grant_type=authorization_code&code=C&redirect_uri=https%3A%2F%2Fx%2Fcb%2F"
                + "&client_id=cid&code_verifier=V", t.log.get(0));
    }

    @Test public void invalidateLeavesANewerAccessTokenAlone() throws IOException {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"access_token\":\"A1\",\"expires_in\":3600}")
                .reply(200, "{\"access_token\":\"A2\",\"expires_in\":3600}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        SpotifyAuth auth = new SpotifyAuth(t, s, clock, "cid");
        assertEquals("A1", auth.accessToken());
        auth.invalidate("A0");
        assertEquals("A1", auth.accessToken());
        assertEquals(1, t.log.size());
        auth.invalidate("A1");
        assertEquals("A2", auth.accessToken());
    }

    @Test public void refusedCodeKeepsTheExistingLogin() {
        FakeTransport t = new FakeTransport().reply(400, "{\"error\":\"invalid_grant\"}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        try {
            new SpotifyAuth(t, s, clock, "cid").exchangeCode("C", "V", "https://x/cb/");
            fail();
        } catch (SpotifyException e) {
            assertEquals(SpotifyException.Reason.HTTP, e.reason);
            assertEquals(400, e.status);
        } catch (IOException e) {
            fail(e.toString());
        }
        assertEquals("R", s.refresh);
    }

    @Test public void nullRefreshTokenKeepsTheStoredOne() throws IOException {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"access_token\":\"A\",\"expires_in\":3600,\"refresh_token\":null}")
                .reply(200, "{}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        api(t, s).getJson("/me");
        assertEquals("R", s.refresh);
        assertEquals("GET /me A", t.log.get(1));
    }

    @Test public void nullAccessTokenIsAnError() {
        FakeTransport t = new FakeTransport().reply(200, "{\"access_token\":null,\"expires_in\":3600}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        try {
            api(t, s).getJson("/me");
            fail();
        } catch (IOException e) {
            assertEquals(1, t.log.size());
        }
    }

    @Test public void shortExpiresInDoesNotRefreshEveryCall() throws IOException {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"access_token\":\"A1\",\"expires_in\":1}")
                .reply(200, "{}")
                .reply(200, "{}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        SpotifyApi a = api(t, s);
        a.getJson("/me");
        now += 30 * 1000;
        a.getJson("/me");
        assertEquals(3, t.log.size());
        assertEquals("GET /me A1", t.log.get(2));
    }

    @Test public void secondUnauthorizedIsAnHttpError() {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"access_token\":\"A1\",\"expires_in\":3600}")
                .reply(401, "{}")
                .reply(200, "{\"access_token\":\"A2\",\"expires_in\":3600}")
                .reply(401, "{}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        try {
            api(t, s).getJson("/me");
            fail();
        } catch (SpotifyException e) {
            assertEquals(SpotifyException.Reason.HTTP, e.reason);
            assertEquals(401, e.status);
        } catch (IOException e) {
            fail(e.toString());
        }
        int apiCalls = 0;
        for (String line : t.log) if (!line.startsWith("TOKEN ")) apiCalls++;
        assertEquals(2, apiCalls);
        assertEquals(4, t.log.size());
    }

    @Test public void tokenServerErrorIsAnHttpError() {
        FakeTransport t = new FakeTransport().reply(500, "oops");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        try {
            api(t, s).getJson("/me");
            fail();
        } catch (SpotifyException e) {
            assertEquals(SpotifyException.Reason.HTTP, e.reason);
            assertEquals(500, e.status);
        } catch (IOException e) {
            fail(e.toString());
        }
        assertEquals("R", s.refresh);
    }

    @Test public void putAccepts204AndReports404() throws IOException {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"access_token\":\"A1\",\"expires_in\":3600}")
                .reply(204, "")
                .reply(404, "{}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        SpotifyApi a = api(t, s);
        a.put("/me/player/play", "{\"x\":1}");
        assertEquals("PUT /me/player/play A1 {\"x\":1}", t.log.get(1));
        try {
            a.put("/me/player/play", "{}");
            fail();
        } catch (SpotifyException e) {
            assertEquals(SpotifyException.Reason.HTTP, e.reason);
            assertEquals(404, e.status);
        }
    }

    @Test public void logOutClearsTheLogin() throws IOException {
        FakeTransport t = new FakeTransport()
                .reply(200, "{\"access_token\":\"A1\",\"expires_in\":3600}")
                .reply(200, "{}");
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        SpotifyApi a = api(t, s);
        a.getJson("/me");
        a.auth().logOut();
        assertNull(s.refresh);
        assertFalse(a.auth().isLoggedIn());
        try {
            a.getJson("/me");
            fail();
        } catch (SpotifyException e) {
            assertEquals(SpotifyException.Reason.LOGIN_REQUIRED, e.reason);
        }
        assertEquals(2, t.log.size());
    }

    @Test public void failureMessagesNeverCarryTokens() {
        String token = "{\"access_token\":\"SECRET_ACCESS\",\"expires_in\":3600}";
        List<FakeTransport> cases = new ArrayList<FakeTransport>();
        cases.add(new FakeTransport().reply(400, "{\"error\":\"invalid_grant\"}"));
        cases.add(new FakeTransport().reply(500, "SECRET_REFRESH"));
        cases.add(new FakeTransport().reply(200, "{\"expires_in\":3600}"));
        cases.add(new FakeTransport().reply(200, token).reply(401, "{}").reply(200, token).reply(401, "{}"));
        cases.add(new FakeTransport().reply(200, token).reply(404, "SECRET_ACCESS"));
        cases.add(new FakeTransport().reply(200, token).reply(200, "not json SECRET_ACCESS"));
        FakeTransport limited = new FakeTransport().reply(200, token);
        limited.replies.add(new HelperReply(429, 3, ""));
        cases.add(limited);
        for (FakeTransport t : cases) {
            MemoryStore s = new MemoryStore();
            s.refresh = "SECRET_REFRESH";
            try {
                api(t, s).getJson("/me?q=x");
                fail();
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                assertFalse(m, m.contains("SECRET_REFRESH"));
                assertFalse(m, m.contains("SECRET_ACCESS"));
            }
        }
        FakeTransport t = new FakeTransport().reply(400, "{\"error\":\"invalid_grant\"}");
        MemoryStore s = new MemoryStore();
        try {
            new SpotifyAuth(t, s, clock, "cid").exchangeCode("SECRET_CODE", "SECRET_VERIFIER", "https://x/cb/");
            fail();
        } catch (IOException e) {
            assertFalse(e.getMessage().contains("SECRET"));
        }
    }

    private static SpotifyApi loggedIn(FakeTransport t) {
        MemoryStore s = new MemoryStore();
        s.refresh = "R";
        return new SpotifyApi(t, new SpotifyAuth(t, s, new SpotifyAuth.Clock() {
            @Override public long nowMs() { return 1000000; }
        }, "cid"));
    }

    private static FakeTransport withToken() {
        return new FakeTransport().reply(200, "{\"access_token\":\"A\",\"expires_in\":3600}");
    }

    /** Checks a logged "PUT path token body" line, comparing the body as JSON. */
    private static void assertPut(String pathAndToken, String body, String line) {
        assertTrue(line, line.startsWith("PUT " + pathAndToken + " "));
        JSONObject want = new JSONObject(body);
        JSONObject got = new JSONObject(line.substring(("PUT " + pathAndToken + " ").length()));
        assertTrue(got.toString(), want.similar(got));
    }

    private static final String DEVICES =
            "{\"devices\":[{\"id\":\"d1\",\"name\":\"Echo\"},{\"id\":\"d2\",\"name\":\"ST15i\"}]}";

    @Test public void playLooksUpTheDeviceOnceAndSendsTheOffset() throws IOException {
        FakeTransport t = withToken().reply(200, DEVICES).reply(204, "").reply(204, "");
        SpotifyApi a = loggedIn(t);
        a.play("ST15i", "spotify:album:X", "spotify:track:T");
        a.play("ST15i", "spotify:album:X", null);
        assertEquals("GET /me/player/devices A", t.log.get(1));
        assertPut("/me/player/play?device_id=d2 A",
                "{\"context_uri\":\"spotify:album:X\",\"offset\":{\"uri\":\"spotify:track:T\"}}", t.log.get(2));
        assertPut("/me/player/play?device_id=d2 A", "{\"context_uri\":\"spotify:album:X\"}", t.log.get(3));
        assertEquals(4, t.log.size());
    }

    @Test public void playALoneTrackSendsUris() throws IOException {
        FakeTransport t = withToken().reply(200, DEVICES).reply(204, "");
        loggedIn(t).play("ST15i", null, "spotify:track:T");
        assertPut("/me/player/play?device_id=d2 A", "{\"uris\":[\"spotify:track:T\"]}", t.log.get(2));
    }

    @Test public void playLooksUpAgainWhenTheDeviceIsGone() throws IOException {
        FakeTransport t = withToken().reply(200, DEVICES).reply(404, "{}")
                .reply(200, "{\"devices\":[{\"id\":\"d3\",\"name\":\"ST15i\"}]}").reply(204, "").reply(204, "");
        SpotifyApi a = loggedIn(t);
        a.play("ST15i", "spotify:album:X", null);
        assertEquals("GET /me/player/devices A", t.log.get(3));
        assertPut("/me/player/play?device_id=d3 A", "{\"context_uri\":\"spotify:album:X\"}", t.log.get(4));
        a.play("ST15i", "spotify:album:X", null);
        assertPut("/me/player/play?device_id=d3 A", "{\"context_uri\":\"spotify:album:X\"}", t.log.get(5));
        assertEquals(6, t.log.size());
    }

    @Test public void playGivesUpAfterASecond404() {
        FakeTransport t = withToken().reply(200, DEVICES).reply(404, "{}").reply(200, DEVICES).reply(404, "{}");
        try {
            loggedIn(t).play("ST15i", "spotify:album:X", null);
            fail();
        } catch (SpotifyException e) {
            assertEquals(SpotifyException.Reason.HTTP, e.reason);
            assertEquals(404, e.status);
        } catch (IOException e) {
            fail(e.toString());
        }
        assertEquals(5, t.log.size());
    }

    @Test public void playFailsWhenTheDeviceIsNotListed() {
        FakeTransport t = withToken().reply(200, "{\"devices\":[{\"id\":\"d1\",\"name\":\"ST15i phone\"},"
                + "{\"id\":null,\"name\":\"ST15i\"}]}");
        try {
            loggedIn(t).play("ST15i", "spotify:album:X", null);
            fail();
        } catch (SpotifyException e) {
            assertEquals(SpotifyException.Reason.HTTP, e.reason);
            assertEquals(404, e.status);
            assertEquals("device not listed", e.getMessage());
        } catch (IOException e) {
            fail(e.toString());
        }
        assertEquals(2, t.log.size());
    }

    @Test public void listPathsAndPaging() throws IOException {
        FakeTransport t = withToken()
                .reply(200, "{\"items\":[{\"uri\":\"spotify:playlist:P\",\"name\":\"p\"}],\"next\":\"x\"}")
                .reply(200, "{\"items\":[]}")
                .reply(200, "{\"items\":[]}")
                .reply(200, "{\"items\":[]}")
                .reply(200, "{\"artists\":{\"items\":[],\"next\":\"x\",\"cursors\":{\"after\":\"c2\"}}}")
                .reply(200, "{\"artists\":{\"items\":[]}}")
                .reply(200, "{\"items\":[]}")
                .reply(200, "{\"items\":[]}")
                .reply(200, "{\"tracks\":{\"items\":[{\"uri\":\"spotify:track:T\",\"name\":\"t\","
                        + "\"album\":{\"uri\":\"spotify:album:B\"}}]}}");
        SpotifyApi a = loggedIn(t);
        Page p = a.playlists(50);
        assertEquals("spotify:playlist:P", p.items.get(0).uri);
        assertEquals("51", p.next);
        a.savedAlbums(0);
        a.albumTracks("spotify:album:B", "cover", 50);
        a.playlistTracks("spotify:playlist:P", 100);
        assertEquals("c2", a.followedArtists("c1").next);
        a.followedArtists(null);
        a.artistAlbums("spotify:artist:R", 50);
        a.recent();
        SpotifyJson.SearchResult r = a.search("daft punk");
        assertEquals("spotify:album:B", r.tracks.get(0).contextUri);
        assertEquals("GET /me/playlists?limit=50&offset=50 A", t.log.get(1));
        assertEquals("GET /me/albums?limit=50&offset=0&market=from_token A", t.log.get(2));
        assertEquals("GET /albums/B/tracks?limit=50&offset=50&market=from_token A", t.log.get(3));
        assertEquals("GET /playlists/P/items?limit=50&offset=100&market=from_token A", t.log.get(4));
        assertEquals("GET /me/following?type=artist&limit=50&after=c1 A", t.log.get(5));
        assertEquals("GET /me/following?type=artist&limit=50 A", t.log.get(6));
        assertEquals("GET /artists/R/albums?include_groups=album%2Csingle&limit=50&offset=50&market=from_token A",
                t.log.get(7));
        assertEquals("GET /me/player/recently-played?limit=50 A", t.log.get(8));
        assertEquals("GET /search?q=daft+punk&type=track%2Calbum%2Cartist%2Cplaylist&limit=10&market=from_token A",
                t.log.get(9));
        assertEquals(10, t.log.size());
    }

    @Test public void meIsCachedAndGivesSavedTracksTheirContext() throws IOException {
        FakeTransport t = withToken()
                .reply(200, "{\"id\":\"potkc\"}")
                .reply(200, "{\"items\":[{\"track\":{\"uri\":\"spotify:track:T\",\"name\":\"t\"}}]}")
                .reply(200, "{\"items\":[]}");
        SpotifyApi a = loggedIn(t);
        Page p = a.savedTracks(0);
        assertEquals("spotify:user:potkc:collection", p.items.get(0).contextUri);
        a.savedTracks(50);
        assertEquals("potkc", a.me());
        assertEquals("GET /me A", t.log.get(1));
        assertEquals("GET /me/tracks?limit=50&offset=0&market=from_token A", t.log.get(2));
        assertEquals("GET /me/tracks?limit=50&offset=50&market=from_token A", t.log.get(3));
        assertEquals(4, t.log.size());
    }

    @Test public void savedTracksWithoutAUserIdPlayAlone() throws IOException {
        FakeTransport t = withToken()
                .reply(200, "{}")
                .reply(200, "{\"items\":[{\"track\":{\"uri\":\"spotify:track:T\",\"name\":\"t\"}}]}");
        Page p = loggedIn(t).savedTracks(0);
        assertNull(p.items.get(0).contextUri);
    }
}
