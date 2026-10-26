package jp.tkc.st15iplayer.runtime;

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
}
