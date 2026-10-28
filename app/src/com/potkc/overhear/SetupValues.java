package com.potkc.overhear;

import java.util.Locale;

import com.potkc.overhear.spotify.SpotifyConfig;

/**
 * What the setup screen saves, normalized for Prefs.save (one commit), and
 * the settings' defaults. Pure Java (host tests).
 */
final class SetupValues {
    /** The device name when the model name (android.os.Build.MODEL) is blank. */
    static final String FALLBACK_DEVICE_NAME = "Overhear";
    /** Start at boot and when the player opens. */
    static final boolean DEFAULT_AUTO_START = true;
    /** The player hides the status bar. */
    static final boolean DEFAULT_FULLSCREEN = true;
    /** The service stops itself after this many minutes of not playing (PlayerService); 0 never. */
    static final int DEFAULT_IDLE_STOP_MINUTES = 15;
    /** The idle stop's choices in setup, in order (R.array.setup_idle_stop_entries). */
    static final int[] IDLE_STOP_CHOICES = {5, 15, 30, 0};

    private final String key;
    private final String deviceName;
    private final String clientId;
    private final boolean autoStart;
    private final boolean fullscreen;
    private final int idleStopMinutes;

    /**
     * The default device name: the phone's model name (android.os.Build.MODEL,
     * passed in so this stays pure Java), trimmed, or "Overhear" when blank.
     */
    static String defaultDeviceName(String model) {
        String m = model == null ? "" : model.trim();
        return m.isEmpty() ? FALLBACK_DEVICE_NAME : m;
    }

    /** The choice for minutes (IDLE_STOP_CHOICES), or the default's for a value not offered. */
    static int idleStopIndex(int minutes) {
        int fallback = 0;
        for (int i = 0; i < IDLE_STOP_CHOICES.length; i++) {
            if (IDLE_STOP_CHOICES[i] == minutes) return i;
            if (IDLE_STOP_CHOICES[i] == DEFAULT_IDLE_STOP_MINUTES) fallback = i;
        }
        return fallback;
    }

    /** The minutes of choice index; the default if out of range. */
    static int idleStopMinutes(int index) {
        return index >= 0 && index < IDLE_STOP_CHOICES.length ? IDLE_STOP_CHOICES[index] : DEFAULT_IDLE_STOP_MINUTES;
    }

    /** The typed Spotify Client ID trimmed and in lower case (as the dashboard shows it); "" when blank. */
    static String normalizeClientId(String typed) {
        return typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
    }

    /** Blank (the built-in one) or, once normalized, 32 hex digits. */
    static boolean isValidClientId(String typed) {
        String c = normalizeClientId(typed);
        if (c.isEmpty()) return true;
        if (c.length() != 32) return false;
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if ((ch < '0' || ch > '9') && (ch < 'a' || ch > 'f')) return false;
        }
        return true;
    }

    /** The Client ID in use: the stored one, or SpotifyConfig.CLIENT_ID when none (null or blank). */
    static String effectiveClientId(String stored) {
        return stored == null || stored.trim().isEmpty() ? SpotifyConfig.CLIENT_ID : stored.trim();
    }

    /**
     * A blank deviceName becomes defaultDeviceName(model); a negative
     * idleStopMinutes, 0 (never); a blank clientId, the built-in one. The
     * clientId must pass isValidClientId (SetupActivity checks first).
     */
    SetupValues(String key, String deviceName, String model, String clientId, boolean autoStart,
            boolean fullscreen, int idleStopMinutes) {
        String k = key == null ? "" : key.trim();
        this.key = k.isEmpty() ? null : k;
        String n = deviceName == null ? "" : deviceName.trim();
        this.deviceName = n.isEmpty() ? defaultDeviceName(model) : n;
        String c = normalizeClientId(clientId);
        this.clientId = c.isEmpty() ? null : c;
        this.autoStart = autoStart;
        this.fullscreen = fullscreen;
        this.idleStopMinutes = Math.max(0, idleStopMinutes);
    }

    /** The trimmed key, or null to keep the stored one. */
    String keyToStore() {
        return key;
    }

    String deviceName() {
        return deviceName;
    }

    /** The normalized Client ID, or null for the built-in one (nothing stored). */
    String clientIdToStore() {
        return clientId;
    }

    /** The Client ID these values put in use (effectiveClientId). */
    String spotifyClientId() {
        return effectiveClientId(clientId);
    }

    boolean autoStart() {
        return autoStart;
    }

    boolean fullscreen() {
        return fullscreen;
    }

    int idleStopMinutes() {
        return idleStopMinutes;
    }
}
