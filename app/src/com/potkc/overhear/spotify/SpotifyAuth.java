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
 * reads the store directly, so the TokenStore must be thread-safe. The
 * Client ID can change (setClientId), which logs out: a refresh token
 * belongs to the client it was issued to. Without one ("": none entered in
 * setup) nothing is logged in and nothing is sent; a refresh token found
 * then (from a client used before) is dropped.
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
    private volatile String clientId; // written under this
    private String access; // guarded by this
    private long expiresAtMs; // guarded by this
    private volatile int generation;

    /** clientId null or "" for none. */
    public SpotifyAuth(ApiTransport transport, TokenStore store, Clock clock, String clientId) {
        this.transport = transport;
        this.store = store;
        this.clock = clock;
        this.clientId = clientId == null ? "" : clientId;
        if (this.clientId.isEmpty() && store.refreshToken() != null) store.clear();
    }

    /** Safe on the main thread: does not wait for a refresh in progress. */
    public boolean isLoggedIn() {
        return hasClientId() && store.refreshToken() != null;
    }

    /** Whether a Client ID is set, without which there is no login. Safe on the main thread. */
    public boolean hasClientId() {
        return !clientId.isEmpty();
    }

    /**
     * Changes whenever the login does (log out, login expired, a new login),
     * so callers can drop what they cached for the previous account.
     */
    public int generation() {
        return generation;
    }

    /** "" for none. Safe on the main thread. */
    public String clientId() {
        return clientId;
    }

    /**
     * Uses id (null or "" for none) from now on; a different one logs out
     * (logOut()). Returns whether it did.
     */
    public synchronized boolean setClientId(String id) {
        if (id == null) id = "";
        if (id.equals(clientId)) return false;
        clientId = id;
        logOut();
        return true;
    }

    /**
     * Trades a login's code for tokens and keeps the refresh token. A refused
     * code leaves any existing login alone. authorizedFor is the Client ID the
     * login was authorized for: if it is no longer the one in use, the code
     * is refused here, so no token is stored for the wrong client; with no
     * Client ID at all, too.
     */
    public synchronized void exchangeCode(String code, String verifier, String redirectUri, String authorizedFor)
            throws IOException {
        if (!hasClientId() || !authorizedFor.equals(clientId)) {
            throw new SpotifyException(SpotifyException.Reason.HTTP, 400, -1, "login for another client ID");
        }
        tokenRequest("grant_type=authorization_code&code=" + Pkce.enc(code) + "&redirect_uri="
                + Pkce.enc(redirectUri) + "&client_id=" + Pkce.enc(clientId) + "&code_verifier="
                + Pkce.enc(verifier), false);
    }

    /** A valid access token, refreshing it when needed. */
    public synchronized String accessToken() throws IOException {
        if (!hasClientId()) throw loginRequired("no client ID");
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
        generation++;
    }

    private void tokenRequest(String form, boolean refreshGrant) throws IOException { // under this
        HelperReply r = transport.token(form);
        JSONObject j = parse(r.body);
        if (r.status == 400 && "invalid_grant".equals(SpotifyJson.string(j, "error"))) {
            if (!refreshGrant) {
                throw new SpotifyException(SpotifyException.Reason.HTTP, 400, -1, "login failed");
            }
            logOut();
            throw loginRequired("login expired");
        }
        if (r.status != 200) throw SpotifyApi.httpError("token", r);
        String a = SpotifyJson.string(j, "access_token");
        if (a.isEmpty()) throw new IOException("token reply without access_token");
        access = a;
        expiresAtMs = clock.nowMs() + Math.max(j.optLong("expires_in", 3600), MIN_EXPIRES_S) * 1000;
        String newRefresh = SpotifyJson.string(j, "refresh_token");
        if (!newRefresh.isEmpty()) store.saveRefreshToken(newRefresh);
        if (!refreshGrant) generation++;
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
