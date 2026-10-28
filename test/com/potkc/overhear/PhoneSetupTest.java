package com.potkc.overhear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.potkc.overhear.PhoneSetup.Route;
import com.potkc.overhear.PhoneSetup.Submission;
import com.potkc.overhear.PhoneSetup.Submission.Problem;

public class PhoneSetupTest {
    private static final String N = "AbC-_0123456789abcdefghijklmnopqrstuvwxyzAB";
    private static final String CID = "0123456789abcdef0123456789abcdef";

    private static Route route(String line) {
        return PhoneSetup.route(line, N, false, false);
    }

    @Test public void theFormAndTheSaveNeedTheNonce() {
        assertEquals(Route.FORM, route("GET /setup?n=" + N + " HTTP/1.1"));
        assertEquals(Route.SAVE, route("POST /setup?n=" + N + " HTTP/1.1"));
        assertEquals(Route.FORM, route("GET /setup?x=1&n=" + N + " HTTP/1.1"));
    }

    @Test public void aWrongOrMissingNonceIsForbidden() {
        assertEquals(Route.FORBIDDEN, route("GET /setup HTTP/1.1"));
        assertEquals(Route.FORBIDDEN, route("GET /setup? HTTP/1.1"));
        assertEquals(Route.FORBIDDEN, route("GET /setup?n= HTTP/1.1"));
        assertEquals(Route.FORBIDDEN, route("POST /setup?n=" + N + "x HTTP/1.1"));
        assertEquals(Route.FORBIDDEN, route("POST /setup?n=" + N.substring(1) + " HTTP/1.1"));
        assertEquals(Route.FORBIDDEN, route("GET /setup?nn=" + N + " HTTP/1.1"));
        assertEquals(Route.FORBIDDEN, route("GET /setup?n=" + N + "&n=" + N + " HTTP/1.1"));
        assertEquals(Route.FORBIDDEN, route("GET /setup?n=%zz HTTP/1.1"));
        // Checked before the method and before whether it was used.
        assertEquals(Route.FORBIDDEN, PhoneSetup.route("PUT /setup?n=x HTTP/1.1", N, true, true));
    }

    @Test public void noNonceMatchesWhenTheScreenHasNone() {
        assertFalse(PhoneSetup.nonceMatches("n=", ""));
        assertFalse(PhoneSetup.nonceMatches("n=x", null));
    }

    @Test public void theNonceIsSpentOnceSaved() {
        assertEquals(Route.USED, PhoneSetup.route("GET /setup?n=" + N + " HTTP/1.1", N, true, false));
        assertEquals(Route.USED, PhoneSetup.route("POST /setup?n=" + N + " HTTP/1.1", N, true, true));
    }

    @Test public void theCallbackOnlyAfterALoginStarted() {
        String cb = "GET /callback?code=a&state=s HTTP/1.1";
        assertEquals(Route.NOT_FOUND, route(cb));
        assertEquals(Route.CALLBACK, PhoneSetup.route(cb, N, true, true));
        assertEquals(Route.NOT_FOUND, PhoneSetup.route("POST /callback?code=a HTTP/1.1", N, true, true));
    }

    @Test public void otherRequests() {
        assertEquals(Route.NOT_FOUND, route("GET / HTTP/1.1"));
        assertEquals(Route.NOT_FOUND, route("GET /favicon.ico HTTP/1.1"));
        assertEquals(Route.NOT_FOUND, route("GET /setup/?n=" + N + " HTTP/1.1"));
        assertEquals(Route.NOT_FOUND, route("GET /setupx?n=" + N + " HTTP/1.1"));
        assertEquals(Route.BAD_REQUEST, route("PUT /setup?n=" + N + " HTTP/1.1"));
        assertEquals(Route.BAD_REQUEST, route("garbage"));
        assertEquals(Route.BAD_REQUEST, route("GET /setup?n=" + N));
        assertEquals(Route.BAD_REQUEST, route(null));
    }

    @Test public void contentLength() {
        assertEquals(42, PhoneSetup.contentLength("Content-Length: 42"));
        assertEquals(0, PhoneSetup.contentLength("content-length:0"));
        assertEquals(-1, PhoneSetup.contentLength("Content-Type: text/plain"));
        assertEquals(-1, PhoneSetup.contentLength("garbage"));
        assertEquals(-2, PhoneSetup.contentLength("Content-Length: "));
        assertEquals(-2, PhoneSetup.contentLength("Content-Length: -1"));
        assertEquals(-2, PhoneSetup.contentLength("Content-Length: 1 2"));
        assertEquals(Long.MAX_VALUE, PhoneSetup.contentLength("Content-Length: 99999999999999999999999"));
    }

    @Test public void formBodiesDecodePercentAndPlus() {
        Map<String, String> f = PhoneSetup.parseForm("key=a%2Bb+c%26d&client_id=&login=1&&flag");
        assertEquals("a+b c&d", f.get("key"));
        assertEquals("", f.get("client_id"));
        assertEquals("1", f.get("login"));
        assertEquals("", f.get("flag"));
        assertEquals("日本", PhoneSetup.parseForm("x=%E6%97%A5%E6%9C%AC").get("x"));
        assertTrue(PhoneSetup.parseForm("").isEmpty());
    }

    @Test public void badFormBodiesAreRefused() {
        assertNull(PhoneSetup.parseForm("key=%zz"));
        assertNull(PhoneSetup.parseForm("key=a&key=b"));
    }

    @Test public void aNewKeyAndClientId() {
        Submission s = PhoneSetup.check("key=+secret+&client_id=+" + CID.toUpperCase() + "+&login=1", false, "");
        assertTrue(s.ok());
        assertEquals("secret", s.key);
        assertEquals(CID, s.clientId);
        assertEquals(CID, s.effectiveClientId);
        assertTrue(s.login);
    }

    @Test public void blankFieldsKeepWhatIsStored() {
        // Logging in again: nothing typed, login checked.
        Submission s = PhoneSetup.check("key=&client_id=&login=1", true, CID);
        assertTrue(s.ok());
        assertNull(s.key);
        assertNull(s.clientId);
        assertEquals(CID, s.effectiveClientId);
        assertTrue(s.login);
        s = PhoneSetup.check("key=%20%20&client_id=%20", true, "");
        assertTrue(s.ok());
        assertNull(s.key);
        assertNull(s.clientId);
        assertFalse(s.login);
    }

    @Test public void aKeyIsNeededWhenNoneIsStored() {
        assertEquals(Problem.NEED_KEY, PhoneSetup.check("key=+&client_id=" + CID, false, "").problem);
        assertEquals(Problem.NEED_KEY, PhoneSetup.check("", false, CID).problem);
    }

    @Test public void theClientIdIsCheckedAsSetupDid() {
        Submission s = PhoneSetup.check("key=k&client_id=abc&login=1", true, CID);
        assertEquals(Problem.BAD_CLIENT_ID, s.problem);
        assertEquals("abc", s.typedClientId);
        assertEquals(Problem.BAD_CLIENT_ID, PhoneSetup.check("client_id=" + CID + "0", true, "").problem);
    }

    @Test public void loggingInNeedsAClientId() {
        assertEquals(Problem.NEED_CLIENT_ID, PhoneSetup.check("key=k&login=1", true, "").problem);
        assertTrue(PhoneSetup.check("key=k", true, "").ok());
        assertTrue(PhoneSetup.check("login=1", true, CID).ok());
    }

    @Test public void aBadBodyIsAProblemThatStillOffersTheLogin() {
        Submission s = PhoneSetup.check("key=%zz", true, CID);
        assertEquals(Problem.BAD_FORM, s.problem);
        assertNull(s.key);
        assertTrue(s.login);
    }
}
