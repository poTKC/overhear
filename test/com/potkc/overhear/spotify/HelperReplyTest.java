package com.potkc.overhear.spotify;

import static org.junit.Assert.*;

import java.io.IOException;

import org.junit.Test;

public class HelperReplyTest {
    private static HelperReply parse(String s) throws IOException {
        return HelperReply.parse(s.getBytes("UTF-8"));
    }

    @Test public void statusAndBody() throws IOException {
        HelperReply r = parse("status 200\n\n{\"a\":1}");
        assertEquals(200, r.status);
        assertEquals(-1, r.retryAfterSeconds);
        assertEquals("{\"a\":1}", r.body);
    }

    @Test public void retryAfter() throws IOException {
        HelperReply r = parse("status 429\nretry-after 7\n\n");
        assertEquals(429, r.status);
        assertEquals(7, r.retryAfterSeconds);
        assertEquals("", r.body);
    }

    @Test public void bodyMayContainBlankLines() throws IOException {
        assertEquals("a\n\nb", parse("status 200\n\na\n\nb").body);
    }

    @Test(expected = IOException.class) public void missingStatus() throws IOException {
        parse("hello\n\n");
    }

    @Test(expected = IOException.class) public void missingBlankLine() throws IOException {
        parse("status 200\n");
    }
}
