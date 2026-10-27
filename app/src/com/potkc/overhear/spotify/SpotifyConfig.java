package com.potkc.overhear.spotify;

/**
 * The app's Spotify Web API registration: the client ID, the relay page the
 * login redirects to, and the scopes it asks for (in this order: the QR code
 * size was checked with exactly this string). Pure Java, so the host tests
 * use the same values.
 */
public final class SpotifyConfig {
    public static final String CLIENT_ID = "efc26a16958343d0babe0e67afaa1a18";
    public static final String REDIRECT_URI = "https://potkc.github.io/overhear-auth/callback/";
    public static final String SCOPES = "user-read-playback-state user-modify-playback-state user-library-read "
            + "playlist-read-private user-follow-read user-read-recently-played";

    private SpotifyConfig() {
    }
}
