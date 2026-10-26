package com.potkc.overhear.ws;

import static com.potkc.overhear.ws.TestServer.UTF8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.potkc.overhear.ws.TestServer.Frame;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WebSocketClientTest {
    final BlockingQueue<String> messages = new LinkedBlockingQueue<String>();
    final BlockingQueue<String> closes = new LinkedBlockingQueue<String>();
    final WebSocketClient.Listener listener = new WebSocketClient.Listener() {
        @Override public void onOpen() { messages.add("<open>"); }
        @Override public void onMessage(String text) { messages.add(text); }
        @Override public void onClose(int code, String reason) { closes.add(code + " " + reason); }
    };

    TestServer ts;
    WebSocketClient client;

    @Before public void setUp() throws IOException {
        ts = new TestServer();
    }

    @After public void tearDown() throws IOException {
        if (client != null) client.close();
        ts.close();
    }

    void open() throws Exception {
        Thread t = ts.acceptAsync(true);
        client = new WebSocketClient("127.0.0.1", ts.port(), "/", listener);
        client.connect(3000);
        t.join();
        assertTrue(client.isOpen());
        assertEquals("<open>", nextMessage());
    }

    static byte[] text(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) ('a' + i % 26);
        return b;
    }

    static byte[] closePayload(int code, String reason) {
        byte[] r = reason.getBytes(UTF8);
        byte[] p = new byte[2 + r.length];
        p[0] = (byte) (code >> 8);
        p[1] = (byte) code;
        System.arraycopy(r, 0, p, 2, r.length);
        return p;
    }

    String nextMessage() throws InterruptedException {
        String m = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull("no message", m);
        return m;
    }

    String nextClose() throws InterruptedException {
        String c = closes.poll(5, TimeUnit.SECONDS);
        assertNotNull("no close", c);
        return c;
    }

    @Test public void acceptKeyMatchesRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                WebSocketClient.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test public void base64EncodesAllPaddings() {
        for (int n = 0; n < 40; n++) {
            byte[] b = new byte[n];
            for (int i = 0; i < n; i++) b[i] = (byte) (i * 37 + n);
            assertEquals(Base64.getEncoder().encodeToString(b), WebSocketClient.base64(b));
        }
    }

    @Test public void handshakeSendsGetWithPath() throws Exception {
        open();
        assertEquals("GET / HTTP/1.1", ts.requestLine);
    }

    @Test public void rejectsWrongAccept() throws Exception {
        Thread t = ts.acceptAsync(false);
        client = new WebSocketClient("127.0.0.1", ts.port(), "/", listener);
        try {
            client.connect(3000);
            fail("connected despite a wrong Sec-WebSocket-Accept");
        } catch (IOException expected) {
        }
        t.join();
        assertFalse(client.isOpen());
        assertNull(closes.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test public void connectFailsWhenNothingListens() throws Exception {
        ServerSocket s = new ServerSocket(0);
        int port = s.getLocalPort();
        s.close();
        client = new WebSocketClient("127.0.0.1", port, "/", listener);
        try {
            client.connect(3000);
            fail();
        } catch (IOException expected) {
        }
        assertFalse(client.isOpen());
    }

    @Test public void connectOnlyOnce() throws Exception {
        open();
        try {
            client.connect(3000);
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    @Test public void sendsMaskedTextFrames() throws Exception {
        open();
        client.send("{\"type\":\"command\",\"command\":\"get_state\"}");
        Frame f = ts.readFrame();
        assertEquals(1, f.opcode);
        assertTrue(f.fin);
        assertEquals("{\"type\":\"command\",\"command\":\"get_state\"}", f.text());

        String big = new String(text(300), UTF8) + "éあ";
        client.send(big);
        assertEquals(big, ts.readText());
        String huge = new String(text(70000), UTF8);
        client.send(huge);
        assertEquals(huge, ts.readText());
    }

    @Test public void receivesFramesOfEveryLengthEncoding() throws Exception {
        open();
        for (int n : new int[] {0, 125, 126, 65535, 70000}) {
            ts.frame(true, 1, text(n));
            assertEquals(new String(text(n), UTF8), nextMessage());
        }
    }

    @Test public void decodesUtf8() throws Exception {
        open();
        ts.frame(true, 1, "音楽 é".getBytes(UTF8));
        assertEquals("音楽 é", nextMessage());
    }

    @Test public void reassemblesFragmentsAroundPing() throws Exception {
        open();
        ts.frame(false, 1, "{\"a\":".getBytes(UTF8));
        ts.frame(false, 0, "12".getBytes(UTF8));
        ts.frame(true, 9, "p".getBytes(UTF8));
        ts.frame(true, 0, "3}".getBytes(UTF8));
        assertEquals("{\"a\":123}", nextMessage());
        Frame pong = ts.readFrame();
        assertEquals(10, pong.opcode);
        assertArrayEquals("p".getBytes(UTF8), pong.payload);
    }

    @Test public void answersPingWithPong() throws Exception {
        open();
        ts.frame(true, 9, "abc".getBytes(UTF8));
        Frame f = ts.readFrame();
        assertEquals(10, f.opcode);
        assertTrue(f.fin);
        assertArrayEquals("abc".getBytes(UTF8), f.payload);
        ts.frame(true, 10, new byte[0]); // an unsolicited pong is ignored
        ts.frame(true, 1, "after".getBytes(UTF8));
        assertEquals("after", nextMessage());
    }

    @Test public void ignoresBinaryMessages() throws Exception {
        open();
        ts.frame(false, 2, new byte[] {1, 2, 3});
        ts.frame(true, 0, new byte[] {4});
        ts.frame(true, 1, "t".getBytes(UTF8));
        assertEquals("t", nextMessage());
        assertTrue(messages.isEmpty());
    }

    @Test public void serverCloseIsEchoed() throws Exception {
        open();
        ts.frame(true, 8, closePayload(1001, "bye"));
        Frame f = ts.readFrame();
        assertEquals(8, f.opcode);
        assertEquals(1001, f.closeCode());
        assertEquals("1001 bye", nextClose());
        assertEquals(-1, ts.in.read()); // the client closed the TCP connection
        assertFalse(client.isOpen());
        try {
            client.send("x");
            fail("sent after close");
        } catch (IOException expected) {
        }
    }

    @Test public void serverCloseWithoutCode() throws Exception {
        open();
        ts.frame(true, 8, new byte[0]);
        assertEquals(8, ts.readFrame().opcode);
        assertEquals("1005 ", nextClose());
    }

    @Test public void clientCloseHandshake() throws Exception {
        open();
        client.close();
        Frame f = ts.readFrame();
        assertEquals(8, f.opcode);
        assertEquals(1000, f.closeCode());
        assertFalse(client.isOpen());
        ts.frame(true, 8, f.payload);
        assertEquals("1000 ", nextClose());
        assertEquals(-1, ts.in.read());
        client.close(); // idempotent
        assertNull(closes.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test public void clientCloseTimesOutWithoutReply() throws Exception {
        open();
        client.setCloseTimeoutMs(200);
        client.close();
        assertEquals(8, ts.readFrame().opcode);
        assertEquals("1006 ", nextClose()); // no close frame came back (RFC 6455 7.1.5)
        assertEquals(-1, ts.in.read());
    }

    @Test public void droppedConnectionReportsAbnormalClose() throws Exception {
        open();
        ts.dropPeer();
        assertEquals("1006 ", nextClose());
        assertFalse(client.isOpen());
    }

    @Test public void oversizedMessageClosesWith1009() throws Exception {
        open();
        client.setMaxMessageSize(100);
        ts.frame(false, 1, text(60));
        ts.frame(true, 0, text(60));
        Frame f = ts.readFrame();
        assertEquals(8, f.opcode);
        assertEquals(1009, f.closeCode());
        assertTrue(nextClose().startsWith("1009"));
        assertTrue(messages.isEmpty());
    }

    @Test public void maskedServerFrameIsProtocolError() throws Exception {
        open();
        ts.out.write(new byte[] {(byte) 0x81, (byte) 0x81, 0, 0, 0, 0, 'x'});
        ts.out.flush();
        Frame f = ts.readFrame();
        assertEquals(8, f.opcode);
        assertEquals(1002, f.closeCode());
        assertTrue(nextClose().startsWith("1002"));
    }

    @Test public void unexpectedContinuationIsProtocolError() throws Exception {
        open();
        ts.frame(true, 0, "x".getBytes(UTF8));
        assertTrue(nextClose().startsWith("1002"));
    }

    @Test public void throwingListenerClosesWith1011() throws Exception {
        Thread t = ts.acceptAsync(true);
        client = new WebSocketClient("127.0.0.1", ts.port(), "/", new WebSocketClient.Listener() {
            @Override public void onOpen() {}
            @Override public void onMessage(String text) {
                if (text.equals("boom")) throw new IllegalStateException("listener bug");
                messages.add(text);
            }
            @Override public void onClose(int code, String reason) { closes.add(code + " " + reason); }
        });
        client.connect(3000);
        t.join();
        ts.sendText("boom");
        Frame f = ts.readFrame();
        assertEquals(8, f.opcode);
        assertEquals(1011, f.closeCode());
        assertTrue(nextClose().startsWith("1011"));
        assertEquals(-1, ts.in.read());
    }

    @Test public void keepalivePingsAndDropsASilentPeer() throws Exception {
        Thread t = ts.acceptAsync(true);
        client = new WebSocketClient("127.0.0.1", ts.port(), "/", listener);
        client.setPingIntervalMs(300);
        client.connect(3000);
        t.join();
        assertEquals("<open>", nextMessage());
        // Idle: the client pings; a pong counts as traffic and keeps it open.
        Frame ping = ts.readFrame();
        assertEquals(9, ping.opcode);
        ts.frame(true, 10, ping.payload);
        assertEquals(9, ts.readFrame().opcode);
        assertTrue(client.isOpen());
        assertTrue(closes.isEmpty());
        // No answer to the second ping: dropped as abnormal.
        assertEquals("1006 ", nextClose());
        assertEquals(-1, ts.in.read());
        assertFalse(client.isOpen());
    }

    @Test public void closeAbortsAHandshakeThatNeverAnswers() throws Exception {
        client = new WebSocketClient("127.0.0.1", ts.port(), "/", listener);
        final BlockingQueue<Object> result = new LinkedBlockingQueue<Object>();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    client.connect(10000);
                    result.add("connected");
                } catch (Exception e) {
                    result.add(e);
                }
            }
        });
        t.start();
        ts.acceptRaw();
        TestServer.readLine(ts.in); // the request has arrived; no reply
        long start = System.nanoTime();
        client.close();
        Object r = result.poll(2, TimeUnit.SECONDS);
        assertTrue("connect() did not give up: " + r, r instanceof IOException);
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        assertFalse(client.isOpen());
        assertNull(closes.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test public void concurrentSendsDoNotInterleave() throws Exception {
        open();
        final int threads = 4, each = 50;
        Thread[] ts2 = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final String body = new String(text(200 + t), UTF8);
            ts2[t] = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        for (int i = 0; i < each; i++) client.send(body);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            ts2[t].start();
        }
        for (int i = 0; i < threads * each; i++) {
            Frame f = ts.readFrame();
            assertEquals(1, f.opcode);
            int len = f.payload.length;
            assertTrue(len >= 200 && len < 200 + threads);
            assertTrue(Arrays.equals(text(len), f.payload));
        }
        for (Thread t : ts2) t.join();
    }
}
