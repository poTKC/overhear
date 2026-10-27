package com.potkc.overhear.spotify;

import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Spotify Web API calls Overhear makes, through an ApiTransport. Each
 * call blocks (run it off the main thread). A 401 refreshes the access token
 * and retries once. Pure Java.
 */
public final class SpotifyApi {
    private final ApiTransport transport;
    private final SpotifyAuth auth;

    public SpotifyApi(ApiTransport transport, SpotifyAuth auth) {
        this.transport = transport;
        this.auth = auth;
    }

    public SpotifyAuth auth() {
        return auth;
    }

    JSONObject getJson(String path) throws IOException {
        HelperReply r = call("GET", path, "");
        if (r.status != 200) throw httpError("GET " + stripQuery(path), r);
        try {
            return new JSONObject(r.body);
        } catch (JSONException e) {
            throw new IOException("bad JSON from " + stripQuery(path));
        }
    }

    /** PUT with a JSON body; any 2xx is success. */
    void put(String path, String body) throws IOException {
        HelperReply r = call("PUT", path, body);
        if (r.status / 100 != 2) throw httpError("PUT " + stripQuery(path), r);
    }

    private HelperReply call(String method, String path, String body) throws IOException {
        HelperReply r = transport.api(method, path, auth.accessToken(), body);
        if (r.status == 401) {
            auth.invalidate();
            r = transport.api(method, path, auth.accessToken(), body);
        }
        return r;
    }

    static SpotifyException httpError(String what, HelperReply r) {
        if (r.status == 429) {
            return new SpotifyException(SpotifyException.Reason.RATE_LIMITED, 429, r.retryAfterSeconds,
                    what + ": rate limited");
        }
        return new SpotifyException(SpotifyException.Reason.HTTP, r.status, -1, what + ": HTTP " + r.status);
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }
}
