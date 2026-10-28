package com.potkc.overhear.spotify;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * One login attempt's PKCE values (RFC 7636, S256) and the authorize URL.
 * The state is "<device IPv4>:<port>~<nonce>": the relay page on GitHub
 * Pages sends the browser back to that address on the LAN. Pure Java.
 */
public final class Pkce {
    public final String verifier;
    public final String challenge;
    public final String nonce;

    private Pkce(String verifier, String nonce) {
        this.verifier = verifier;
        this.challenge = challengeOf(verifier);
        this.nonce = nonce;
    }

    public static Pkce create(SecureRandom random) {
        return new Pkce(base64Url(bytes(random, 48)), nonce(random));
    }

    /** An unguessable nonce (32 random bytes, base64url): a state's, or any other one-time token. */
    public static String nonce(SecureRandom random) {
        return base64Url(bytes(random, 32));
    }

    public static String state(String ip, int port, String nonce) {
        return ip + ":" + port + "~" + nonce;
    }

    public static String authorizeUrl(String clientId, String redirectUri, String scopes, String challenge,
            String state) {
        return "https://accounts.spotify.com/authorize?client_id=" + enc(clientId)
                + "&response_type=code&redirect_uri=" + enc(redirectUri)
                + "&code_challenge_method=S256&code_challenge=" + enc(challenge)
                + "&scope=" + enc(scopes) + "&state=" + enc(state);
    }

    static String challengeOf(String verifier) {
        try {
            return base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII")));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] bytes(SecureRandom random, int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    /** Base64url without padding (java.util.Base64 is API 26). */
    static String base64Url(byte[] b) {
        StringBuilder sb = new StringBuilder((b.length * 4 + 2) / 3);
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xff) << 16;
            if (i + 1 < b.length) n |= (b[i + 1] & 0xff) << 8;
            if (i + 2 < b.length) n |= b[i + 2] & 0xff;
            sb.append(B64[n >> 18 & 63]).append(B64[n >> 12 & 63]);
            if (i + 1 < b.length) sb.append(B64[n >> 6 & 63]);
            if (i + 2 < b.length) sb.append(B64[n & 63]);
        }
        return sb.toString();
    }
}
