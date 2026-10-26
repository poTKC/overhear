package com.potkc.overhear.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class LineReaderTest {
    private static List<String> read(String s, int max) throws IOException {
        LineReader r = new LineReader(new ByteArrayInputStream(s.getBytes("UTF-8")), max);
        List<String> out = new ArrayList<String>();
        String line;
        while ((line = r.readLine()) != null) out.add(line);
        return out;
    }

    @Test public void splitsLines() throws IOException {
        assertEquals(Arrays.asList("a", "", "bc", "last"), read("a\n\nbc\nlast", 100));
        assertEquals(Arrays.asList("a", "b"), read("a\r\nb\n", 100));
        assertEquals(new ArrayList<String>(), read("", 100));
    }

    @Test public void decodesUtf8() throws IOException {
        assertEquals(Arrays.asList("connect to “ST15i” — ok"),
                read("connect to “ST15i” — ok\n", 100));
    }

    @Test public void capsLongLinesAndSkipsTheRest() throws IOException {
        assertEquals(Arrays.asList("abcd [truncated]", "next"), read("abcdefghij\nnext\n", 4));
        assertEquals(Arrays.asList("abcd"), read("abcd\n", 4));
        assertEquals(Arrays.asList("abcd [truncated]"), read("abcdefgh", 4));
    }

    @Test public void readsInChunksWithoutWaitingForNewline() throws IOException {
        // A stream that never ends a long line must not grow memory unbounded.
        InputStream endless = new InputStream() {
            int n;
            @Override public int read() { return n++ < 10000000 ? 'x' : -1; }
        };
        LineReader r = new LineReader(endless, 4096);
        String first = r.readLine();
        assertEquals(4096 + " [truncated]".length(), first.length());
        assertNull(r.readLine());
    }
    /** Hands out 1, 2, 3, 1, 2, 3... bytes per read, so lines and characters split across reads. */
    private static InputStream trickle(byte[] data) {
        final ByteArrayInputStream in = new ByteArrayInputStream(data);
        return new InputStream() {
            int turn;
            @Override public int read() { return in.read(); }
            @Override public int read(byte[] b, int off, int len) {
                int n = Math.min(len, 1 + turn++ % 3);
                return in.read(b, off, n);
            }
        };
    }

    private static List<String> readAll(LineReader r) throws IOException {
        List<String> out = new ArrayList<String>();
        String line;
        while ((line = r.readLine()) != null) out.add(line);
        return out;
    }

    private static List<String> trickled(String s, int max) throws IOException {
        return readAll(new LineReader(trickle(s.getBytes("UTF-8")), max));
    }

    @Test public void crlfSplitAcrossReads() throws IOException {
        // Every offset of the "\r\n" relative to the read boundaries.
        assertEquals(Arrays.asList("a", "bc", "def", "g"), trickled("a\r\nbc\r\ndef\r\ng\r\n", 100));
        assertEquals(Arrays.asList("x", "y"), trickled("x\r\ny", 100));
    }

    @Test public void multiByteCharSplitAcrossReads() throws IOException {
        // "€" is 3 bytes; with 1-3 byte reads it lands on every split position.
        assertEquals(Arrays.asList("€", "a€", "ab€", "€€€"), trickled("€\na€\nab€\n€€€\n", 100));
    }

    @Test public void truncationNeverCutsACharacter() throws IOException {
        // The cap falls inside "€" (bytes 3..5): keep whole characters only.
        assertEquals(Arrays.asList("abc [truncated]", "z"), read("abc€def\nz\n", 4));
        assertEquals(Arrays.asList("abc [truncated]", "z"), read("abc€def\nz\n", 5));
        assertEquals(Arrays.asList("abc€ [truncated]"), read("abc€def", 6));
        // A 4-byte character (a surrogate pair in Java) is not split either.
        assertEquals(Arrays.asList("a [truncated]"), read("a\uD83C\uDFB5b", 3));
        assertEquals(Arrays.asList("abc [truncated]", "z"), trickled("abc€def\nz\n", 4));
    }

    @Test public void filterRunsBeforeTruncation() throws IOException {
        final String key = "SECRETKEY0123456789";
        LineReader.Filter redact = new LineReader.Filter() {
            @Override public String apply(String line) { return line.replace(key, "<k>"); }
        };
        // The cap (10 bytes) falls inside the key: no fragment of it may survive.
        String line = "key=" + key + " tail\nnext " + key + "\n";
        List<String> out = readAll(new LineReader(trickle(line.getBytes("UTF-8")), 10, 64, redact));
        assertEquals(Arrays.asList("key=<k> ta [truncated]", "next <k>"), out);
        // Without the filter the fragment would show.
        assertEquals("key=SECRET [truncated]", read(line, 10).get(0));
        // A key past the slack is cut away before anything is shown.
        String far = "0123456789abcdefghij" + key + "\n";
        assertEquals(Arrays.asList("0123456789 [truncated]"),
                readAll(new LineReader(trickle(far.getBytes("UTF-8")), 10, 12, redact)));
    }

    @Test public void filterAppliesToShortLines() throws IOException {
        LineReader.Filter upper = new LineReader.Filter() {
            @Override public String apply(String line) { return line.toUpperCase(); }
        };
        assertEquals(Arrays.asList("AB", "C"),
                readAll(new LineReader(new ByteArrayInputStream("ab\r\nc".getBytes("UTF-8")), 100, 16, upper)));
    }

    @Test public void slackBoundsMemoryForEndlessLines() throws IOException {
        InputStream endless = new InputStream() {
            int n;
            @Override public int read() { return n++ < 1000000 ? 'x' : -1; }
        };
        LineReader r = new LineReader(endless, 8, 4, null);
        assertEquals("xxxxxxxx [truncated]", r.readLine());
        assertNull(r.readLine());
    }
}
