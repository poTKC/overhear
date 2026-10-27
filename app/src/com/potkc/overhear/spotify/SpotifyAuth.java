package com.potkc.overhear.spotify;

import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Holds the login: the refresh token (in a TokenStore, Prefs on the device)
 * and the access token (memory only), refreshed a minute before it expires
 * or when the API answered 401. Spotify refusing the refresh token
 * (invalid_grant: revoked, or six months after the first login) clears the
 * store. Never logs tokens. Thread-safe (synchronized); calls block.
 * Pure Java.
 */
public final class SpotifyAuth {
    public interface TokenStore {
        /** The refresh token or null. */
        String refreshToken();

        void saveRefreshToken(String token);

        void clear();
    }

    public interface Clock {
        long nowMs();
    }

    private static final long EARLY_MS = 60 * 1000;

    private final ApiTransport transport;
    private final TokenStore store;
    private final Clock clock;
    private final String clientId;
    private String access; // guarded by this
    private long expiresAtMs; // guarded by this

    public SpotifyAuth(ApiTransport transport, TokenStore store, Clock clock, String clientId) {
        this.transport = transport;
        this.store = store;
        this.clock = clock;
        this.clientId = clientId;
    }

    public synchronized boolean isLoggedIn() {
        return store.refreshToken() != null;
    }

    /** Trades a login's code for tokens and keeps the refresh token. */
    public synchronized void exchangeCode(String code, String verifier, String redirectUri) throws IOException {
        tokenRequest("grant_type=authorization_code&code=" + Pkce.enc(code) + "&redirect_uri="
                + Pkce.enc(redirectUri) + "&client_id=" + Pkce.enc(clientId) + "&code_verifier="
                + Pkce.enc(verifier));
    }

    /** A valid access token, refreshing it when needed. */
    public synchronized String accessToken() throws IOException {
        if (access != null && clock.nowMs() < expiresAtMs - EARLY_MS) return access;
        String refresh = store.refreshToken();
        if (refresh == null) throw loginRequired("not logged in");
        tokenRequest("grant_type=refresh_token&refresh_token=" + Pkce.enc(refresh) + "&client_id="
                + Pkce.enc(clientId));
        return access;
    }

    /** Forgets the access token (after a 401) so the next call refreshes. */
    public synchronized void invalidate() {
        access = null;
    }

    public synchronized void logOut() {
        access = null;
        store.clear();
    }

    private void tokenRequest(String form) throws IOException { // under this
        HelperReply r = transport.token(form);
        JSONObject j = parse(r.body);
        if (r.status == 400 && "invalid_grant".equals(j.optString("error"))) {
            logOut();
            throw loginRequired("login expired");
        }
        if (r.status != 200) throw SpotifyApi.httpError("token", r);
        String a = j.optString("access_token", "");
        if (a.isEmpty()) throw new IOException("token reply without access_token");
        access = a;
        expiresAtMs = clock.nowMs() + j.optLong("expires_in", 3600) * 1000;
        String newRefresh = j.optString("refresh_token", "");
        if (!newRefresh.isEmpty()) store.saveRefreshToken(newRefresh);
    }

    private static JSONObject parse(String body) {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static SpotifyException loginRequired(String why) {
        return new SpotifyException(SpotifyException.Reason.LOGIN_REQUIRED, 0, -1, why);
    }
}
