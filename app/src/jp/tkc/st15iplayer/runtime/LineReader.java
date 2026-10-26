package jp.tkc.st15iplayer.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Reads UTF-8 lines from a process pipe in chunks, capping each line at
 * maxBytes (the rest of an over-long line is skipped and the line is marked
 * " [truncated]"), so a runaway line cannot grow memory. Strips "\r\n".
 * Pure Java (host tests). Not thread-safe.
 */
final class LineReader {
    static final String TRUNCATED = " [truncated]";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final InputStream in;
    private final int maxBytes;
    private final byte[] buf = new byte[8192];
    private int pos;
    private int len;
    private boolean eof;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    LineReader(InputStream in, int maxBytes) {
        this.in = in;
        this.maxBytes = maxBytes;
    }

    /** The next line, or null at end of stream. */
    String readLine() throws IOException {
        line.reset();
        boolean truncated = false;
        boolean any = false;
        while (true) {
            if (pos == len) {
                if (eof) break;
                len = in.read(buf, 0, buf.length);
                pos = 0;
                if (len <= 0) {
                    len = 0;
                    eof = true;
                    break;
                }
            }
            any = true;
            int start = pos;
            while (pos < len && buf[pos] != '\n') pos++;
            int n = pos - start;
            if (!truncated) {
                int room = maxBytes - line.size();
                if (n > room) {
                    line.write(buf, start, room);
                    truncated = true;
                } else {
                    line.write(buf, start, n);
                }
            }
            if (pos < len) { // found '\n'
                pos++;
                return finish(truncated);
            }
        }
        return any ? finish(truncated) : null;
    }

    private String finish(boolean truncated) {
        String s = new String(line.toByteArray(), UTF8);
        if (!truncated && s.endsWith("\r")) s = s.substring(0, s.length() - 1);
        return truncated ? s + TRUNCATED : s;
    }
}
