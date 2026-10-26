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

    private final String key;
    private final String deviceName;
    private final boolean autoStart;
    private final boolean fullscreen;

    /**
     * The default device name: the phone's model name (android.os.Build.MODEL,
     * passed in so this stays pure Java), trimmed, or "Overhear" when blank.
     */
    static String defaultDeviceName(String model) {
        String m = model == null ? "" : model.trim();
        return m.isEmpty() ? FALLBACK_DEVICE_NAME : m;
    }

    /** A blank deviceName becomes defaultDeviceName(model). */
    SetupValues(String key, String deviceName, String model, boolean autoStart, boolean fullscreen) {
        String k = key == null ? "" : key.trim();
        this.key = k.isEmpty() ? null : k;
        String n = deviceName == null ? "" : deviceName.trim();
        this.deviceName = n.isEmpty() ? defaultDeviceName(model) : n;
        this.autoStart = autoStart;
        this.fullscreen = fullscreen;
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
}
