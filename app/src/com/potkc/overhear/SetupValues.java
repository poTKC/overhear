package com.potkc.overhear;

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

    /** A blank deviceName becomes defaultDeviceName(model); a negative idleStopMinutes, 0 (never). */
    SetupValues(String key, String deviceName, String model, boolean autoStart, boolean fullscreen,
            int idleStopMinutes) {
        String k = key == null ? "" : key.trim();
        this.key = k.isEmpty() ? null : k;
        String n = deviceName == null ? "" : deviceName.trim();
        this.deviceName = n.isEmpty() ? defaultDeviceName(model) : n;
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
