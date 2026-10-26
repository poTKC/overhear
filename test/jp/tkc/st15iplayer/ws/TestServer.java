package jp.tkc.st15iplayer.ws;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Base64;

/** A minimal WebSocket server for the tests: one peer at a time, driven by the test thread. */
public final class TestServer implements Closeable {
    public static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Frame {
        public int opcode;
        public boolean fin;
        public byte[] payload;

        public String text() { return new String(payload, UTF8); }

        public int closeCode() { return ((payload[0] & 0xff) << 8) | (payload[1] & 0xff); }
    }

    public final ServerSocket server;
    public Socket peer;
    public DataInputStream in;
    public OutputStream out;
    public String requestLine;

    public TestServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(5000);
    }

    public int port() { return server.getLocalPort(); }

    /** Accepts one client and answers its handshake, with a wrong accept if asked. */
    public void accept(boolean correctAccept) throws Exception {
        Socket s = server.accept();
        s.setSoTimeout(5000);
        peer = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
        String key = null;
        String line;
        requestLine = readLine(in);
        while (!(line = readLine(in)).isEmpty()) {
            int c = line.indexOf(':');
            if (line.substring(0, c).trim().equalsIgnoreCase("Sec-WebSocket-Key")) {
                key = line.substring(c + 1).trim();
            }
        }
        assertNotNull(key);
        assertEquals(16, Base64.getDecoder().decode(key).length);
        String accept = expectedAccept(correctAccept ? key : key + "x");
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(UTF8));
        out.flush();
    }

    /** Accepts TCP only and never answers the handshake. */
    public Socket acceptRaw() throws IOException {
        Socket s = server.accept();
        s.setSoTimeout(5000);
        peer = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
        return s;
    }

    /** accept() on a new thread, for clients whose connect() blocks the test thread. */
    public Thread acceptAsync(final boolean correctAccept) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    accept(correctAccept);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        t.start();
        return t;
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != '\n') {
            if (c < 0) throw new IOException("eof");
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    static String expectedAccept(String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] d = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF8));
        return Base64.getEncoder().encodeToString(d);
    }

    /** Writes an unmasked frame. */
    public void frame(boolean fin, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write((fin ? 0x80 : 0) | opcode);
        int n = payload.length;
        if (n < 126) {
            b.write(n);
        } else if (n < 65536) {
            b.write(126);
            b.write(n >> 8);
            b.write(n);
        } else {
            b.write(127);
            for (int i = 7; i >= 0; i--) b.write((int) ((long) n >> (8 * i)));
        }
        b.write(payload);
        out.write(b.toByteArray());
        out.flush();
    }

    public void sendText(String text) throws IOException { frame(true, 1, text.getBytes(UTF8)); }

    /** Reads a client frame, checking that it is masked. */
    public Frame readFrame() throws IOException {
        Frame f = new Frame();
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        f.fin = (b0 & 0x80) != 0;
        f.opcode = b0 & 0x0f;
        assertTrue("client frames must be masked", (b1 & 0x80) != 0);
        long n = b1 & 0x7f;
        if (n == 126) n = in.readUnsignedShort();
        else if (n == 127) n = in.readLong();
        byte[] mask = new byte[4];
        in.readFully(mask);
        f.payload = new byte[(int) n];
        in.readFully(f.payload);
        for (int i = 0; i < f.payload.length; i++) f.payload[i] ^= mask[i & 3];
        return f;
    }

    /** Reads the next client text frame. */
    public String readText() throws IOException {
        Frame f = readFrame();
        assertEquals("expected a text frame", 1, f.opcode);
        return f.text();
    }

    public void dropPeer() throws IOException {
        if (peer != null) peer.close();
    }

    @Override public void close() throws IOException {
        dropPeer();
        server.close();
    }
}
