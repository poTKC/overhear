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
}
