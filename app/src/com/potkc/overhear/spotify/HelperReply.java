package com.potkc.overhear.spotify;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

/**
 * One reply of overhear-fetch's token and api subcommands: "status N", an
 * optional "retry-after V", a blank line, then the body as sent. Pure Java.
 */
public final class HelperReply {
    public final int status;
    /** Seconds from Retry-After, or -1 when absent or not a number of seconds. */
    public final int retryAfterSeconds;
    public final String body;

    public HelperReply(int status, int retryAfterSeconds, String body) {
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
        this.body = body;
    }

    public static HelperReply parse(byte[] out) throws IOException {
        String s;
        try {
            s = new String(out, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
        int end = s.indexOf("\n\n");
        if (end < 0) throw new IOException("no header end in helper reply");
        int status = -1, retry = -1;
        for (String line : s.substring(0, end).split("\n")) {
            if (line.startsWith("status ")) status = parseInt(line.substring(7), -1);
            else if (line.startsWith("retry-after ")) retry = parseInt(line.substring(12), -1);
        }
        if (status < 100) throw new IOException("no status in helper reply");
        return new HelperReply(status, retry, s.substring(end + 2));
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
