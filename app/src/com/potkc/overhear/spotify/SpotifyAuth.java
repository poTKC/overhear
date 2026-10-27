package com.potkc.overhear.spotify;

import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Holds the login: the refresh token (in a TokenStore, Prefs on the device)
 * and the access token (memory only), refreshed a minute before it expires
 * or when the API answered 401. Spotify refusing the refresh token
 * (invalid_grant: revoked, or six months after the first login) clears the
 * store. Never logs tokens. Thread-safe (synchronized); accessToken() and
 * exchangeCode() may block for a whole helper call while holding the lock,
 * so never call them on the main thread. isLoggedIn() takes no lock and
 * reads the store directly, so the TokenStore must be thread-safe.
 * Pure Java.
 */
public final class SpotifyAuth {
    /** Where the refresh token lives. Called from several threads. */
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
    /** Shortest lifetime believed, so a tiny expires_in cannot refresh on every call. */
    private static final long MIN_EXPIRES_S = 120;

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

    /** Safe on the main thread: does not wait for a refresh in progress. */
    public boolean isLoggedIn() {
        return store.refreshToken() != null;
    }

    /**
     * Trades a login's code for tokens and keeps the refresh token. A refused
     * code leaves any existing login alone.
     */
    public synchronized void exchangeCode(String code, String verifier, String redirectUri) throws IOException {
        tokenRequest("grant_type=authorization_code&code=" + Pkce.enc(code) + "&redirect_uri="
                + Pkce.enc(redirectUri) + "&client_id=" + Pkce.enc(clientId) + "&code_verifier="
                + Pkce.enc(verifier), false);
    }

    /** A valid access token, refreshing it when needed. */
    public synchronized String accessToken() throws IOException {
        if (access != null && clock.nowMs() < expiresAtMs - EARLY_MS) return access;
        String refresh = store.refreshToken();
        if (refresh == null) throw loginRequired("not logged in");
        tokenRequest("grant_type=refresh_token&refresh_token=" + Pkce.enc(refresh) + "&client_id="
                + Pkce.enc(clientId), true);
        return access;
    }

    /**
     * Forgets the access token after a 401 so the next call refreshes, unless
     * another thread already replaced the stale one.
     */
    public synchronized void invalidate(String stale) {
        if (access != null && access.equals(stale)) access = null;
    }

    public synchronized void logOut() {
        access = null;
        store.clear();
    }

    private void tokenRequest(String form, boolean refreshGrant) throws IOException { // under this
        HelperReply r = transport.token(form);
        JSONObject j = parse(r.body);
        if (r.status == 400 && "invalid_grant".equals(string(j, "error"))) {
            if (!refreshGrant) {
                throw new SpotifyException(SpotifyException.Reason.HTTP, 400, -1, "login failed");
            }
            logOut();
            throw loginRequired("login expired");
        }
        if (r.status != 200) throw SpotifyApi.httpError("token", r);
        String a = string(j, "access_token");
        if (a.isEmpty()) throw new IOException("token reply without access_token");
        access = a;
        expiresAtMs = clock.nowMs() + Math.max(j.optLong("expires_in", 3600), MIN_EXPIRES_S) * 1000;
        String newRefresh = string(j, "refresh_token");
        if (!newRefresh.isEmpty()) store.saveRefreshToken(newRefresh);
    }

    /** The string at key, "" when missing or JSON null (Android's optString gives "null"). */
    private static String string(JSONObject j, String key) {
        return j.isNull(key) ? "" : j.optString(key, "");
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
