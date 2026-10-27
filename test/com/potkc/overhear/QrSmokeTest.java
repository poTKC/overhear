package com.potkc.overhear;

import static org.junit.Assert.assertTrue;

import com.potkc.overhear.spotify.Pkce;
import com.potkc.overhear.spotify.SpotifyConfig;

import io.nayuki.qrcodegen.QrCode;

import org.junit.Test;

public class QrSmokeTest {
    @Test public void encodesALongAuthorizeUrl() {
        String challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        String nonce = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String url = Pkce.authorizeUrl(SpotifyConfig.CLIENT_ID,
                SpotifyConfig.REDIRECT_URI, SpotifyConfig.SCOPES, challenge,
                Pkce.state("192.168.40.95", 8889, nonce));
        QrCode qr = QrCode.encodeText(url, QrCode.Ecc.LOW);
        System.out.println("QrSmokeTest: " + url.length() + " chars, version " + qr.version + ", " + qr.size + " modules");
        // Version <= 15: with the 4-module quiet zone, version 14 (73 + 8) and 15 (77 + 8)
        // both draw at 3 px per module on the 320 px wide screen, so 15 looks no worse.
        assertTrue("size " + qr.size, qr.size <= 77);
    }
}
