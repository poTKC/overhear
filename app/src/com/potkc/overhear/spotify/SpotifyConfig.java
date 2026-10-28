package com.potkc.overhear.spotify;

/**
 * The app's Spotify Web API settings: the relay page the login redirects
 * to, and the scopes it asks for. There is no built-in Client ID: each
 * user registers their own app and enters its ID through 「スマホで認証」
 * (Prefs.getSpotifyClientId). Pure Java, so the host tests use the same
 * values.
 */
public final class SpotifyConfig {
    public static final String REDIRECT_URI = "https://potkc.github.io/overhear-auth/callback/";
    /** user-library-modify: Liked Songs (since 2026-10-28; older logins lack it until logged in again). */
    public static final String SCOPES = "user-read-playback-state user-modify-playback-state user-library-read "
            + "user-library-modify playlist-read-private user-follow-read user-read-recently-played";

    private SpotifyConfig() {
    }
}
