package com.potkc.overhear;

import static org.junit.Assert.assertTrue;

import io.nayuki.qrcodegen.QrCode;

import org.junit.Test;

public class QrSmokeTest {
    @Test public void encodesTheSetupUrl() {
        // スマホで認証's QR code (LoginActivity) at its longest address; the
        // authorize URL is sent by a 303, not drawn.
        String nonce = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String url = "http://255.255.255.255:8889" + PhoneSetup.PATH + "?n=" + nonce;
        QrCode qr = QrCode.encodeText(url, QrCode.Ecc.LOW);
        System.out.println("QrSmokeTest: " + url.length() + " chars, version " + qr.version + ", " + qr.size + " modules");
        // Version <= 5: with the 4-module quiet zone (37 + 8), 6 px per module
        // on the 320 px wide screen, easy to read from a distance.
        assertTrue("size " + qr.size, qr.size <= 37);
    }
}
