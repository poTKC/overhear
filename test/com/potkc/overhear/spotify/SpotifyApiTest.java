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
}
