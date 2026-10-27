package com.potkc.overhear.spotify;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * Classifies the request line the login listener received: the code, an
 * error Spotify sent back (e.g. access_denied), or anything else, which is
 * rejected. Only "GET /callback?..." with exactly the expected state counts.
 * Pure Java.
 */
public final class OAuthCallback {
    public enum Kind { CODE, ERROR, REJECT }

    public final Kind kind;
    /** The code (CODE) or the error (ERROR); null for REJECT. */
    public final String value;

    private OAuthCallback(Kind kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    private static final OAuthCallback REJECTED = new OAuthCallback(Kind.REJECT, null);

    public static OAuthCallback parse(String requestLine, String expectedState) {
        if (requestLine == null) return REJECTED;
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !"GET".equals(parts[0]) || !parts[2].startsWith("HTTP/")) return REJECTED;
        String target = parts[1];
        if (!target.startsWith("/callback?")) return REJECTED;
        String code = null, error = null, state = null;
        for (String pair : target.substring("/callback?".length()).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq), v = dec(pair.substring(eq + 1));
            if (v == null) return REJECTED;
            if ("code".equals(k)) code = v;
            else if ("error".equals(k)) error = v;
            else if ("state".equals(k)) state = v;
        }
        if (state == null || !state.equals(expectedState)) return REJECTED;
        if (error != null) return new OAuthCallback(Kind.ERROR, error);
        if (code == null || code.isEmpty()) return REJECTED;
        return new OAuthCallback(Kind.CODE, code);
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        } catch (IllegalArgumentException e) {
            return null; // bad % escape
        }
    }
}
