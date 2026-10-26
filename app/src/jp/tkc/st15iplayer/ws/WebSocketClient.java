package jp.tkc.st15iplayer.ws;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * A minimal RFC 6455 WebSocket client for text messages over plain TCP
 * (ws:// only). Pure Java so it runs in the host tests.
 *
 * connect() does the opening handshake on the calling thread and then starts
 * a reader thread that calls the listener's onOpen and then delivers
 * messages. Binary messages are
 * read and dropped, pings are answered, fragmented messages are reassembled.
 * The listener's onClose is called exactly once, on the reader thread, after
 * the socket is closed: with the peer's close code, 1006 if the connection
 * dropped, 1002 on a protocol error or 1009 if a message exceeded the limit.
 * send() and close() may be called from any thread.
 */
public final class WebSocketClient {
    public interface Listener {
        /** Called on the reader thread before any message. */
        void onOpen();

        void onMessage(String text);

        void onClose(int code, String reason);
    }

    public static final int DEFAULT_MAX_MESSAGE = 4 << 20;
    public static final int CLOSE_NORMAL = 1000;
    public static final int CLOSE_NO_STATUS = 1005;
    public static final int CLOSE_ABNORMAL = 1006;
    public static final int CLOSE_PROTOCOL_ERROR = 1002;
    public static final int CLOSE_TOO_BIG = 1009;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER = 8192;
    private static final int OP_CONT = 0, OP_TEXT = 1, OP_BINARY = 2, OP_CLOSE = 8, OP_PING = 9,
            OP_PONG = 10;

    private final String host;
    private final int port;
    private final String path;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final Object writeLock = new Object();

    private volatile int maxMessage = DEFAULT_MAX_MESSAGE;
    private volatile long closeTimeoutMs = 1000;

    private Socket socket; // guarded by writeLock, set once open
    private Socket connecting; // guarded by writeLock, set during connect()
    private InputStream in;
    private OutputStream out; // guarded by writeLock
    private volatile Thread reader;
    private boolean started;
    /** Set once a close frame was sent; nothing else may be sent after it. */
    private boolean closeSent; // guarded by writeLock
    private volatile boolean open;
    private volatile boolean closeRequested;

    public WebSocketClient(String host, int port, String path, Listener listener) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.listener = listener;
    }

    public void setMaxMessageSize(int bytes) {
        maxMessage = bytes;
    }

    /** How long close() waits for the peer's close frame before dropping the socket. */
    public void setCloseTimeoutMs(long ms) {
        closeTimeoutMs = ms;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Connects and performs the opening handshake, waiting up to timeoutMs for
     * each of the TCP connect and the server's response. Starts the reader on
     * success. One client connects once; create a new one to reconnect.
     */
    public void connect(int timeoutMs) throws IOException {
        synchronized (this) {
            if (started) throw new IllegalStateException("already connected");
            started = true;
        }
        Socket s = new Socket();
        synchronized (writeLock) {
            connecting = s;
        }
        try {
            if (closeRequested) throw new IOException("closed during connect");
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            InputStream i = new BufferedInputStream(s.getInputStream());
            OutputStream o = new BufferedOutputStream(s.getOutputStream());
            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            String key = base64(nonce);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            o.write(request.getBytes(UTF8));
            o.flush();
            checkResponse(readHeader(i), acceptFor(key));
            s.setSoTimeout(0);
            synchronized (writeLock) {
                // close() sets closeRequested before taking writeLock.
                if (closeRequested) throw new IOException("closed during connect");
                socket = s;
                in = i;
                out = o;
                open = true;
            }
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
        reader = new Thread(new Runnable() {
            @Override public void run() {
                readLoop();
            }
        }, "ws-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0; // matched bytes of "\r\n\r\n"
        while (state < 4) {
            int c = in.read();
            if (c < 0) throw new EOFException("connection closed during handshake");
            if (b.size() >= MAX_HEADER) throw new IOException("handshake response too long");
            b.write(c);
            if (c == (state % 2 == 0 ? '\r' : '\n')) state++;
            else state = c == '\r' ? 1 : 0;
        }
        return new String(b.toByteArray(), UTF8);
    }

    private static void checkResponse(String header, String expectedAccept) throws IOException {
        String[] lines = header.split("\r\n");
        String[] status = lines[0].split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/") || !status[1].equals("101")) {
            throw new IOException("handshake failed: " + lines[0]);
        }
        String upgrade = null, connection = null, accept = null;
        for (int n = 1; n < lines.length; n++) {
            int c = lines[n].indexOf(':');
            if (c <= 0) continue;
            String name = lines[n].substring(0, c).trim().toLowerCase(Locale.US);
            String value = lines[n].substring(c + 1).trim();
            if (name.equals("upgrade")) upgrade = value;
            else if (name.equals("connection")) connection = value;
            else if (name.equals("sec-websocket-accept")) accept = value;
        }
        if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
            throw new IOException("handshake failed: Upgrade " + upgrade);
        }
        if (connection == null || !connection.toLowerCase(Locale.US).contains("upgrade")) {
            throw new IOException("handshake failed: Connection " + connection);
        }
        if (!expectedAccept.equals(accept)) {
            throw new IOException("handshake failed: bad Sec-WebSocket-Accept " + accept);
        }
    }

    /** Sends one text message. Throws if the connection is not open. */
    public void send(String text) throws IOException {
        sendFrame(OP_TEXT, text.getBytes(UTF8), false);
    }

    /**
     * Starts the closing handshake without blocking: sends a close frame and
     * drops the socket if the peer does not answer within the close timeout.
     */
    public void close() {
        closeRequested = true;
        synchronized (this) {
            if (!started) {
                started = true; // a later connect() must not open it
                return;
            }
        }
        synchronized (writeLock) {
            open = false;
            if (socket == null) closeQuietly(connecting); // aborts a blocked connect()
        }
        try {
            sendFrame(OP_CLOSE, closePayload(CLOSE_NORMAL, ""), true);
        } catch (IOException e) {
            dropSocket();
            return;
        }
        final Thread r = reader;
        if (r == null) return;
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    r.join(closeTimeoutMs);
                } catch (InterruptedException ignored) {
                    // drop the socket now
                }
                if (r.isAlive()) dropSocket();
            }
        }, "ws-close");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Writes one frame. Data frames fail once a close frame was sent; a
     * second close frame is silently skipped.
     */
    private void sendFrame(int opcode, byte[] payload, boolean control) throws IOException {
        synchronized (writeLock) {
            if (closeSent || out == null) {
                if (opcode == OP_CLOSE) return;
                if (control) return;
                throw new IOException("WebSocket is not open");
            }
            if (opcode == OP_CLOSE) closeSent = true;
            int n = payload.length;
            int headerLen = 2 + (n < 126 ? 0 : n < 65536 ? 2 : 8) + 4;
            byte[] frame = new byte[headerLen + n];
            int p = 0;
            frame[p++] = (byte) (0x80 | opcode);
            if (n < 126) {
                frame[p++] = (byte) (0x80 | n);
            } else if (n < 65536) {
                frame[p++] = (byte) (0x80 | 126);
                frame[p++] = (byte) (n >> 8);
                frame[p++] = (byte) n;
            } else {
                frame[p++] = (byte) (0x80 | 127);
                for (int i = 7; i >= 0; i--) frame[p++] = (byte) ((long) n >> (8 * i));
            }
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            System.arraycopy(mask, 0, frame, p, 4);
            p += 4;
            for (int i = 0; i < n; i++) frame[p + i] = (byte) (payload[i] ^ mask[i & 3]);
            try {
                out.write(frame);
                out.flush();
            } catch (IOException e) {
                open = false;
                closeSent = true;
                throw e;
            }
        }
    }

    private static final class ProtocolException extends IOException {
        final int code;

        ProtocolException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private void readLoop() {
        int code = CLOSE_ABNORMAL;
        String reason = "";
        try {
            listener.onOpen();
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            int messageOp = -1; // opcode of the message being reassembled
            while (true) {
                int b0 = readByte();
                int b1 = readByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;
                if ((b0 & 0x70) != 0) throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "reserved bits set");
                if ((b1 & 0x80) != 0) throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "masked server frame");
                long len = b1 & 0x7f;
                if (len == 126) {
                    len = (readByte() << 8) | readByte();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                    if (len < 0) throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "bad length");
                }
                if (opcode >= 8) {
                    if (!fin || len > 125) {
                        throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "bad control frame");
                    }
                    byte[] payload = readFully((int) len);
                    if (opcode == OP_PING) {
                        sendFrame(OP_PONG, payload, true);
                    } else if (opcode == OP_CLOSE) {
                        if (payload.length >= 2) {
                            code = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
                            reason = new String(payload, 2, payload.length - 2, UTF8);
                        } else {
                            code = CLOSE_NO_STATUS;
                        }
                        open = false;
                        // Echo the code back (RFC 6455 5.5.1); skipped if we started the close.
                        sendFrame(OP_CLOSE, payload.length >= 2 ? closePayload(code, "") : new byte[0], true);
                        break;
                    } else if (opcode != OP_PONG) {
                        throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "unknown opcode " + opcode);
                    }
                    continue;
                }
                if (opcode == OP_CONT) {
                    if (messageOp < 0) throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "unexpected continuation");
                } else if (opcode == OP_TEXT || opcode == OP_BINARY) {
                    if (messageOp >= 0) throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "unfinished message");
                    messageOp = opcode;
                } else {
                    throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "unknown opcode " + opcode);
                }
                if (message.size() + len > maxMessage) {
                    throw new ProtocolException(CLOSE_TOO_BIG, "message too big");
                }
                byte[] payload = readFully((int) len);
                if (messageOp == OP_TEXT) message.write(payload, 0, payload.length);
                if (fin) {
                    if (messageOp == OP_TEXT) {
                        String text = new String(message.toByteArray(), UTF8);
                        message.reset();
                        listener.onMessage(text);
                    }
                    messageOp = -1;
                }
            }
        } catch (ProtocolException e) {
            code = e.code;
            reason = e.getMessage();
            open = false;
            try {
                sendFrame(OP_CLOSE, closePayload(code, reason), true);
            } catch (IOException ignored) {
                // closing anyway
            }
        } catch (IOException e) {
            code = CLOSE_ABNORMAL;
            reason = "";
        } finally {
            open = false;
            dropSocket();
            listener.onClose(code, reason);
        }
    }

    private int readByte() throws IOException {
        int c = in.read();
        if (c < 0) throw new EOFException();
        return c;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return b;
    }

    private void dropSocket() {
        Socket s;
        synchronized (writeLock) {
            s = socket;
            closeSent = true;
        }
        closeQuietly(s);
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private static byte[] closePayload(int code, String reason) {
        byte[] r = reason.getBytes(UTF8);
        int n = Math.min(r.length, 123);
        byte[] p = new byte[2 + n];
        p[0] = (byte) (code >> 8);
        p[1] = (byte) code;
        System.arraycopy(r, 0, p, 2, n);
        return p;
    }

    /** The Sec-WebSocket-Accept value the server must answer to {@code key}. */
    static String acceptFor(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return base64(sha1.digest((key + GUID).getBytes(UTF8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Standard base64 with padding (java.util.Base64 is not on API 18). */
    static String base64(byte[] b) {
        StringBuilder sb = new StringBuilder((b.length + 2) / 3 * 4);
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xff) << 16;
            if (i + 1 < b.length) n |= (b[i + 1] & 0xff) << 8;
            if (i + 2 < b.length) n |= b[i + 2] & 0xff;
            sb.append(B64[n >> 18 & 63]).append(B64[n >> 12 & 63]);
            sb.append(i + 1 < b.length ? B64[n >> 6 & 63] : '=');
            sb.append(i + 2 < b.length ? B64[n & 63] : '=');
        }
        return sb.toString();
    }
}
