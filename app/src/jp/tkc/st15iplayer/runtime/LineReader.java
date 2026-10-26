package jp.tkc.st15iplayer.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Reads UTF-8 lines from a process pipe in chunks, capping each line at
 * maxBytes (the rest of an over-long line is skipped and the line is marked
 * " [truncated]"), so a runaway line cannot grow memory. Strips "\r\n".
 * Truncation keeps whole characters only.
 *
 * An optional Filter (e.g. key redaction) sees each line before it is cut:
 * up to maxBytes + slack bytes are kept, filtered, and only then truncated,
 * so a secret straddling the cap is redacted rather than shown in part. A
 * secret no longer than slack that starts inside the visible part is always
 * complete when the filter runs; anything starting past the cap is dropped.
 *
 * Pure Java (host tests). Not thread-safe.
 */
public final class LineReader {
    static final String TRUNCATED = " [truncated]";
    /** Default bytes kept past maxBytes for the filter. */
    static final int DEFAULT_SLACK = 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Rewrites a whole (or slack-extended) line before it is truncated. */
    interface Filter {
        String apply(String line);
    }

    private final InputStream in;
    private final int maxBytes;
    private final int keepBytes;
    private final Filter filter;
    private final byte[] buf = new byte[8192];
    private int pos;
    private int len;
    private boolean eof;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    public LineReader(InputStream in, int maxBytes) {
        this(in, maxBytes, DEFAULT_SLACK, null);
    }

    /** @param filter may be null */
    LineReader(InputStream in, int maxBytes, int slack, Filter filter) {
        this.in = in;
        this.maxBytes = maxBytes;
        this.keepBytes = maxBytes + slack;
        this.filter = filter;
    }

    /** The next line, or null at end of stream. */
    public String readLine() throws IOException {
        line.reset();
        boolean overflow = false; // more than keepBytes: the tail was skipped
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
            if (!overflow) {
                int room = keepBytes - line.size();
                if (n > room) {
                    line.write(buf, start, room);
                    overflow = true;
                } else {
                    line.write(buf, start, n);
                }
            }
            if (pos < len) { // found '\n'
                pos++;
                return finish(overflow);
            }
        }
        return any ? finish(overflow) : null;
    }

    private String finish(boolean overflow) {
        byte[] raw = line.toByteArray();
        int n = raw.length;
        if (!overflow && n > 0 && raw[n - 1] == '\r') n--;
        String s = new String(raw, 0, n, UTF8);
        if (filter != null) s = filter.apply(s);
        // Judged after filtering: a redaction may bring a line under the cap.
        String kept = cut(s, maxBytes);
        return !overflow && kept.length() == s.length() ? s : kept + TRUNCATED;
    }

    /** The longest prefix of s, in whole code points, that is at most max UTF-8 bytes. */
    static String cut(String s, int max) {
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int b = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + b > max) break;
            bytes += b;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }
}
