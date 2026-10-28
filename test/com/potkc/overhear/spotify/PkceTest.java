package com.potkc.overhear.spotify;

import static org.junit.Assert.*;

import java.security.SecureRandom;

import org.junit.Test;

public class PkceTest {
    @Test public void challengeIsS256OfVerifier() {
        // RFC 7636 appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test public void createdValuesAreBase64UrlWithoutPadding() {
        Pkce p = Pkce.create(new SecureRandom());
        assertTrue(p.verifier.matches("[A-Za-z0-9_-]{43,128}"));
        assertTrue(p.nonce.matches("[A-Za-z0-9_-]{16,128}"));
        assertEquals(Pkce.challengeOf(p.verifier), p.challenge);
    }

    @Test public void noncesAreBase64UrlAndDiffer() {
        SecureRandom r = new SecureRandom();
        String a = Pkce.nonce(r);
        assertTrue(a.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(a, Pkce.nonce(r));
    }

    @Test public void stateCarriesTheDeviceAddress() {
        assertEquals("192.168.40.50:8889~abc", Pkce.state("192.168.40.50", 8889, "abc"));
    }

    @Test public void authorizeUrl() {
        String u = Pkce.authorizeUrl("cid", "https://x.example/cb/", "a b", "chal", "1.2.3.4:5~n");
        assertTrue(u.startsWith("https://accounts.spotify.com/authorize?"));
        assertTrue(u.contains("client_id=cid"));
        assertTrue(u.contains("response_type=code"));
        assertTrue(u.contains("redirect_uri=https%3A%2F%2Fx.example%2Fcb%2F"));
        assertTrue(u.contains("code_challenge_method=S256"));
        assertTrue(u.contains("code_challenge=chal"));
        assertTrue(u.contains("scope=a+b") || u.contains("scope=a%20b"));
        assertTrue(u.contains("state=1.2.3.4%3A5%7En"));
    }

    @Test public void base64UrlMatchesRfc4648VectorsWithoutPadding() throws Exception {
        // RFC 4648 section 10
        String[][] v = {
            {"", ""}, {"f", "Zg"}, {"fo", "Zm8"}, {"foo", "Zm9v"},
            {"foob", "Zm9vYg"}, {"fooba", "Zm9vYmE"}, {"foobar", "Zm9vYmFy"},
        };
        for (String[] p : v) assertEquals(p[1], Pkce.base64Url(p[0].getBytes("US-ASCII")));
    }
}
