package com.potkc.overhear.spotify;

import java.io.IOException;

/** Sends requests through overhear-fetch on the device, or a fake in the tests. Blocking. */
public interface ApiTransport {
    /** POSTs a form-encoded body to accounts.spotify.com/api/token. */
    HelperReply token(String form) throws IOException;

    /** Calls https://api.spotify.com/v1 + path; body is JSON or "" for none. */
    HelperReply api(String method, String path, String accessToken, String body) throws IOException;
}
