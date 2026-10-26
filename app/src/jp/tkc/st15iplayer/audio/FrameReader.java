package jp.tkc.st15iplayer.audio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Parses the fakepulse socket framing (see native/fakepulse.c). Each frame is
 * little endian: u8 type, u32 length, payload.
 * <ul>
 * <li>'H' rate (u32), channels (u32): s16le PCM follows in 'P' frames</li>
 * <li>'P' interleaved s16le PCM</li>
 * <li>'F' flush, empty</li>
 * <li>'C' cork, u8 1 = pause, 0 = resume</li>
 * </ul>
 * Pure Java (no Android APIs) so it runs in the host tests. Not thread safe.
 */
public final class FrameReader {
    /** Largest payload accepted; fakepulse sends about 20 ms (3.5 KiB) per frame. */
    public static final int MAX_PAYLOAD = 1 << 20;

    private static final int INITIAL_BUFFER = 64 * 1024;

    /**
     * Receives frames on the reading thread. The pcm array is the reader's own
     * buffer: it is only valid until the callback returns.
     */
    public interface Listener {
        void header(int rate, int channels);

        void pcm(byte[] buf, int off, int len);

        void flush();

        void cork(boolean corked);
    }

    /** Malformed data on the stream; the connection should be dropped. */
    public static final class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }
    }

    private final InputStream in;
    private byte[] buf = new byte[INITIAL_BUFFER];

    public FrameReader(InputStream in) {
        this.in = in;
    }

    /**
     * Reads one frame and passes it to the listener.
     *
     * @return false at end of stream between frames
     * @throws EOFException if the stream ends inside a frame
     * @throws ProtocolException on an unknown type or a bad length or value
     */
    public boolean readNext(Listener listener) throws IOException {
        if (!readFully(buf, 0, 5, true)) return false;
        int type = buf[0] & 0xff;
        long len = u32(buf, 1);
        if (len > MAX_PAYLOAD) throw new ProtocolException("frame '" + (char) type + "' too long: " + len);
        int n = (int) len;
        switch (type) {
            case 'H':
                expectLength(type, n, 8);
                readFully(buf, 0, n, false);
                long rate = u32(buf, 0), channels = u32(buf, 4);
                if (rate == 0 || rate > Integer.MAX_VALUE || channels == 0 || channels > Integer.MAX_VALUE) {
                    throw new ProtocolException("bad header: rate " + rate + ", channels " + channels);
                }
                listener.header((int) rate, (int) channels);
                return true;
            case 'P':
                if (n > buf.length) buf = new byte[n];
                readFully(buf, 0, n, false);
                if (n > 0) listener.pcm(buf, 0, n);
                return true;
            case 'F':
                expectLength(type, n, 0);
                listener.flush();
                return true;
            case 'C':
                expectLength(type, n, 1);
                readFully(buf, 0, 1, false);
                if ((buf[0] & 0xfe) != 0) throw new ProtocolException("bad cork value " + (buf[0] & 0xff));
                listener.cork(buf[0] == 1);
                return true;
            default:
                throw new ProtocolException("unknown frame type 0x" + Integer.toHexString(type));
        }
    }

    /** Reads frames until a clean end of stream. */
    public void run(Listener listener) throws IOException {
        while (readNext(listener)) {
            // keep reading
        }
    }

    private static void expectLength(int type, int len, int want) throws ProtocolException {
        if (len != want) throw new ProtocolException("frame '" + (char) type + "' length " + len + ", want " + want);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xffL) | (b[off + 1] & 0xffL) << 8 | (b[off + 2] & 0xffL) << 16 | (b[off + 3] & 0xffL) << 24;
    }

    /** @return false if the stream ended before any byte and eofOk is set */
    private boolean readFully(byte[] b, int off, int len, boolean eofOk) throws IOException {
        int done = 0;
        while (done < len) {
            int r = in.read(b, off + done, len - done);
            if (r < 0) {
                if (done == 0 && eofOk) return false;
                throw new EOFException("stream ended inside a frame");
            }
            done += r;
        }
        return true;
    }
}
