package com.potkc.overhear.spotify;

import static org.junit.Assert.*;

import org.junit.Test;

public class OAuthCallbackTest {
    private static final String STATE = "192.168.40.50:8889~nonce1234567890ab";
    private static final String Q_STATE = "192.168.40.50%3A8889%7Enonce1234567890ab";

    @Test public void code() {
        OAuthCallback r = OAuthCallback.parse("GET /callback?code=abc&state=" + Q_STATE + " HTTP/1.1", STATE);
        assertEquals(OAuthCallback.Kind.CODE, r.kind);
        assertEquals("abc", r.value);
    }

    @Test public void stateMayArriveUnencoded() {
        // The relay page forwards location.search verbatim; "~" and ":" may be left as is.
        OAuthCallback r = OAuthCallback.parse("GET /callback?code=abc&state=" + STATE + " HTTP/1.1", STATE);
        assertEquals(OAuthCallback.Kind.CODE, r.kind);
    }

    @Test public void denied() {
        OAuthCallback r = OAuthCallback.parse("GET /callback?error=access_denied&state=" + Q_STATE + " HTTP/1.1", STATE);
        assertEquals(OAuthCallback.Kind.ERROR, r.kind);
        assertEquals("access_denied", r.value);
    }

    @Test public void wrongStateIsRejected() {
        assertEquals(OAuthCallback.Kind.REJECT,
                OAuthCallback.parse("GET /callback?code=abc&state=x HTTP/1.1", STATE).kind);
    }

    @Test public void otherPathsAndMethodsAreRejected() {
        assertEquals(OAuthCallback.Kind.REJECT,
                OAuthCallback.parse("GET /favicon.ico HTTP/1.1", STATE).kind);
        assertEquals(OAuthCallback.Kind.REJECT,
                OAuthCallback.parse("POST /callback?code=abc&state=" + Q_STATE + " HTTP/1.1", STATE).kind);
        assertEquals(OAuthCallback.Kind.REJECT, OAuthCallback.parse("garbage", STATE).kind);
        assertEquals(OAuthCallback.Kind.REJECT, OAuthCallback.parse(null, STATE).kind);
    }

    @Test public void missingCodeIsRejected() {
        assertEquals(OAuthCallback.Kind.REJECT,
                OAuthCallback.parse("GET /callback?state=" + Q_STATE + " HTTP/1.1", STATE).kind);
    }

    @Test public void repeatedStateIsRejected() {
        assertEquals(OAuthCallback.Kind.REJECT,
                OAuthCallback.parse("GET /callback?state=x&code=a&state=" + Q_STATE + " HTTP/1.1", STATE).kind);
    }

    @Test public void repeatedCodeIsRejected() {
        assertEquals(OAuthCallback.Kind.REJECT,
                OAuthCallback.parse("GET /callback?code=a&code=b&state=" + Q_STATE + " HTTP/1.1", STATE).kind);
    }

    @Test public void errorWinsOverCode() {
        OAuthCallback r = OAuthCallback.parse(
                "GET /callback?code=abc&error=access_denied&state=" + Q_STATE + " HTTP/1.1", STATE);
        assertEquals(OAuthCallback.Kind.ERROR, r.kind);
        assertEquals("access_denied", r.value);
    }

    @Test public void badEscapeIsRejected() {
        assertEquals(OAuthCallback.Kind.REJECT,
                OAuthCallback.parse("GET /callback?code=a%zz&state=" + Q_STATE + " HTTP/1.1", STATE).kind);
    }

    @Test public void trailingCarriageReturnIsAccepted() {
        OAuthCallback r = OAuthCallback.parse("GET /callback?code=abc&state=" + Q_STATE + " HTTP/1.1\r", STATE);
        assertEquals(OAuthCallback.Kind.CODE, r.kind);
        assertEquals("abc", r.value);
    }
}
