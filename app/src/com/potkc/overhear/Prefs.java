package com.potkc.overhear;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * App settings in MODE_PRIVATE SharedPreferences (only this app's uid can
 * read them). The Spotify developer API key and the Spotify Web API refresh
 * token live here; never log either.
 */
public final class Prefs {
    private static final String FILE = "overhear";
    private static final String API_KEY = "api_key";
    private static final String DEVICE_NAME = "device_name";
    private static final String AUTO_START = "auto_start";
    private static final String FULLSCREEN = "fullscreen";
    private static final String IDLE_STOP_MINUTES = "idle_stop_minutes";
    private static final String SPOTIFY_CLIENT_ID = "spotify_client_id";
    private static final String SPOTIFY_REFRESH = "spotify_refresh";
    private static final String LIBRARY_PATH = "library_path";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The API key, or "" when none is set. */
    public String getApiKey() {
        return prefs.getString(API_KEY, "");
    }

    public boolean hasApiKey() {
        return !getApiKey().isEmpty();
    }

    /** Stores the trimmed key; null or blank clears it. Returns whether it was written. */
    public boolean setApiKey(String key) {
        String k = key == null ? "" : key.trim();
        SharedPreferences.Editor e = prefs.edit();
        if (k.isEmpty()) e.remove(API_KEY);
        else e.putString(API_KEY, k);
        return e.commit();
    }

    /** The device name until the user sets one: the model name, or "Overhear". */
    public static String defaultDeviceName() {
        return SetupValues.defaultDeviceName(Build.MODEL);
    }

    public String getDeviceName() {
        String n = prefs.getString(DEVICE_NAME, "").trim();
        return n.isEmpty() ? defaultDeviceName() : n;
    }

    public void setDeviceName(String name) {
        prefs.edit().putString(DEVICE_NAME, name == null ? defaultDeviceName() : name.trim()).commit();
    }

    /**
     * Stores the device name, the Spotify Client ID, auto start, full screen,
     * the idle stop and, unless the key is blank, the key, in one commit.
     * A Client ID that changes the one in use also drops the refresh token
     * (it belongs to the old one) and the library page, as SpotifyAuth's
     * logOut() does, in the same commit; the caller then logs SpotifyAuth
     * out too (setClientId), for what it holds in memory. Returns whether
     * it was written.
     */
    boolean save(SetupValues v) {
        boolean newClient = !v.spotifyClientId().equals(getSpotifyClientId());
        SharedPreferences.Editor e = prefs.edit();
        if (v.keyToStore() != null) e.putString(API_KEY, v.keyToStore());
        e.putString(DEVICE_NAME, v.deviceName());
        if (v.clientIdToStore() == null) e.remove(SPOTIFY_CLIENT_ID);
        else e.putString(SPOTIFY_CLIENT_ID, v.clientIdToStore());
        if (newClient) {
            e.remove(SPOTIFY_REFRESH);
            e.putString(LIBRARY_PATH, "");
        }
        e.putBoolean(AUTO_START, v.autoStart());
        e.putBoolean(FULLSCREEN, v.fullscreen());
        e.putInt(IDLE_STOP_MINUTES, v.idleStopMinutes());
        return e.commit();
    }

    /** Start the service after boot (BootReceiver) and when the player opens. */
    public boolean isAutoStart() {
        return prefs.getBoolean(AUTO_START, SetupValues.DEFAULT_AUTO_START);
    }

    /** The player screen hides the status bar. */
    public boolean isFullscreen() {
        return prefs.getBoolean(FULLSCREEN, SetupValues.DEFAULT_FULLSCREEN);
    }

    /** Stop the service after this many minutes of not playing; 0 never. */
    public int getIdleStopMinutes() {
        return prefs.getInt(IDLE_STOP_MINUTES, SetupValues.DEFAULT_IDLE_STOP_MINUTES);
    }

    public void setAutoStart(boolean autoStart) {
        prefs.edit().putBoolean(AUTO_START, autoStart).commit();
    }

    /** The Spotify Client ID the user entered, or "" for the built-in one. */
    public String getStoredSpotifyClientId() {
        return prefs.getString(SPOTIFY_CLIENT_ID, "");
    }

    /** The Spotify Client ID in use: the stored one, else SpotifyConfig.CLIENT_ID. */
    public String getSpotifyClientId() {
        return SetupValues.effectiveClientId(getStoredSpotifyClientId());
    }

    /** The Spotify refresh token or null. Never log it. */
    public String getSpotifyRefresh() {
        String t = prefs.getString(SPOTIFY_REFRESH, null);
        return t == null || t.isEmpty() ? null : t;
    }

    /** Stores the refresh token (commit, so a login survives a crash); null or "" clears it. */
    public void setSpotifyRefresh(String token) {
        if (token == null || token.isEmpty()) clearSpotifyRefresh();
        else prefs.edit().putString(SPOTIFY_REFRESH, token).commit();
    }

    public void clearSpotifyRefresh() {
        prefs.edit().remove(SPOTIFY_REFRESH).commit();
    }

    /** The library page stack as LibraryPath encodes it, or "" when none. */
    public String getLibraryPath() {
        return prefs.getString(LIBRARY_PATH, "");
    }

    public void setLibraryPath(String encoded) {
        prefs.edit().putString(LIBRARY_PATH, encoded == null ? "" : encoded).apply();
    }
}
