package com.potkc.overhear;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * The requests of 「スマホで認証」 (LoginActivity): which route a request line
 * takes, the Content-Length header, and the posted form checked as a
 * submission. Every /setup request must carry the screen's nonce (?n=...),
 * or it is refused before anything else; /callback is only served once a
 * save has started a login. Pure Java (host tests).
 */
final class PhoneSetup {
    static final String PATH = "/setup";
    /** Larger form bodies are refused unread. */
    static final int MAX_BODY = 8 * 1024;
    /** The form's field names (PhoneSetupPage). */
    static final String KEY = "key";
    static final String CLIENT_ID = "client_id";
    static final String LOGIN = "login";

    enum Route {
        /** GET /setup with the nonce: the form. */
        FORM,
        /** POST /setup with the nonce, before a save went through. */
        SAVE,
        /** /setup with the nonce after a save went through: refused, the nonce is spent. */
        USED,
        /** GET /callback?... once a save started a login (OAuthCallback checks the state). */
        CALLBACK,
        /** /setup without the right nonce. */
        FORBIDDEN,
        NOT_FOUND,
        BAD_REQUEST
    }

    private PhoneSetup() {
    }

    /** The route of requestLine (without its line ending) for this screen's nonce. */
    static Route route(String requestLine, String nonce, boolean saved, boolean loginStarted) {
        if (requestLine == null) return Route.BAD_REQUEST;
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/")) return Route.BAD_REQUEST;
        String method = parts[0], target = parts[1];
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        if ("/callback".equals(path)) {
            return loginStarted && "GET".equals(method) ? Route.CALLBACK : Route.NOT_FOUND;
        }
        if (!PATH.equals(path)) return Route.NOT_FOUND;
        if (q < 0 || !nonceMatches(target.substring(q + 1), nonce)) return Route.FORBIDDEN;
        boolean get = "GET".equals(method);
        if (!get && !"POST".equals(method)) return Route.BAD_REQUEST;
        if (saved) return Route.USED;
        return get ? Route.FORM : Route.SAVE;
    }

    /** Whether query has exactly one n, equal to nonce (compared in constant time). */
    static boolean nonceMatches(String query, String nonce) {
        if (nonce == null || nonce.isEmpty()) return false;
        String n = null;
        for (String pair : query.split("&")) {
            if (!pair.startsWith("n=")) continue;
            if (n != null) return false;
            n = dec(pair.substring(2));
            if (n == null) return false;
        }
        return n != null && MessageDigest.isEqual(utf8(n), utf8(nonce));
    }

    /**
     * The value of a Content-Length header line: -1 for another header, -2
     * when malformed, Long.MAX_VALUE when too long to be anything we take.
     */
    static long contentLength(String header) {
        int colon = header.indexOf(':');
        if (colon < 0 || !header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) return -1;
        String v = header.substring(colon + 1).trim();
        if (v.isEmpty()) return -2;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') return -2;
        }
        return v.length() > 18 ? Long.MAX_VALUE : Long.parseLong(v);
    }

    /**
     * An application/x-www-form-urlencoded body (as ISO-8859-1 chars, so
     * %-escapes carry the UTF-8), or null for a bad escape or a field given
     * twice.
     */
    static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<String, String>();
        if (body == null) return form;
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = dec(eq < 0 ? pair : pair.substring(0, eq));
            String v = eq < 0 ? "" : dec(pair.substring(eq + 1));
            if (k == null || v == null || form.containsKey(k)) return null;
            form.put(k, v);
        }
        return form;
    }

    /** A checked form: what to store and whether to log in, or the problem to show. */
    static final class Submission {
        enum Problem { NONE, BAD_FORM, NEED_KEY, BAD_CLIENT_ID, NEED_CLIENT_ID }

        final Problem problem;
        /** The new key trimmed, or null to keep the stored one. Never log or echo it. */
        final String key;
        /** The new Client ID normalized, or null to keep the stored one. */
        final String clientId;
        /** The Client ID as typed, to fill the form again after a problem. */
        final String typedClientId;
        /** The Client ID in use after the save: the new one or the stored one ("" for none). */
        final String effectiveClientId;
        /** Log in to Spotify after the save (only with a Client ID: else NEED_CLIENT_ID). */
        final boolean login;

        private Submission(Problem problem, String key, String clientId, String typedClientId,
                String effectiveClientId, boolean login) {
            this.problem = problem;
            this.key = key;
            this.clientId = clientId;
            this.typedClientId = typedClientId;
            this.effectiveClientId = effectiveClientId;
            this.login = login;
        }

        boolean ok() {
            return problem == Problem.NONE;
        }
    }

    /**
     * Checks a posted body. A blank field keeps what is stored: a key is
     * needed only when none is stored, and a Client ID, once normalized
     * (SetupValues), must be 32 hex digits. Logging in needs a Client ID,
     * new or stored. storedClientId is "" for none.
     */
    static Submission check(String body, boolean hasStoredKey, String storedClientId) {
        Map<String, String> form = parseForm(body);
        if (form == null) return new Submission(Submission.Problem.BAD_FORM, null, null, "", "", true);
        String k = form.containsKey(KEY) ? form.get(KEY).trim() : "";
        String typed = form.containsKey(CLIENT_ID) ? form.get(CLIENT_ID) : "";
        boolean login = form.containsKey(LOGIN);
        String cid = SetupValues.normalizeClientId(typed);
        String effective = cid.isEmpty() ? SetupValues.effectiveClientId(storedClientId) : cid;
        Submission.Problem p = Submission.Problem.NONE;
        if (k.isEmpty() && !hasStoredKey) p = Submission.Problem.NEED_KEY;
        else if (!SetupValues.isValidClientId(cid)) p = Submission.Problem.BAD_CLIENT_ID;
        else if (login && effective.isEmpty()) p = Submission.Problem.NEED_CLIENT_ID;
        return new Submission(p, k.isEmpty() ? null : k, cid.isEmpty() ? null : cid, typed, effective, login);
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        } catch (IllegalArgumentException e) {
            return null; // bad % escape; its message may hold the input, so it is not passed on
        }
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
