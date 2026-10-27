package com.potkc.overhear.spotify;

import java.io.IOException;

/** A Web API failure the screens show differently. */
public class SpotifyException extends IOException {
    public enum Reason {
        /** No refresh token, or Spotify refused it (invalid_grant): log in again. */
        LOGIN_REQUIRED,
        /** 429; retryAfterSeconds says when to try again (-1 if unknown). */
        RATE_LIMITED,
        /**
         * 403 "Insufficient client scope": the login lacks a scope this call
         * needs (added since); log in again.
         */
        SCOPE,
        /** Any other HTTP status; see status. */
        HTTP
    }

    public final Reason reason;
    public final int status;
    public final int retryAfterSeconds;

    public SpotifyException(Reason reason, int status, int retryAfterSeconds, String message) {
        super(message);
        this.reason = reason;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
