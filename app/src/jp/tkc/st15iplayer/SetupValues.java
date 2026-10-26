package jp.tkc.st15iplayer;

/**
 * What the setup screen saves, normalized for Prefs.save (one commit), and
 * the settings' defaults. Pure Java (host tests).
 */
final class SetupValues {
    static final String DEFAULT_DEVICE_NAME = "ST15i";
    /** Start at boot and when the player opens. */
    static final boolean DEFAULT_AUTO_START = true;
    /** The player hides the status bar. */
    static final boolean DEFAULT_FULLSCREEN = true;

    private final String key;
    private final String deviceName;
    private final boolean autoStart;
    private final boolean fullscreen;

    SetupValues(String key, String deviceName, boolean autoStart, boolean fullscreen) {
        String k = key == null ? "" : key.trim();
        this.key = k.isEmpty() ? null : k;
        String n = deviceName == null ? "" : deviceName.trim();
        this.deviceName = n.isEmpty() ? DEFAULT_DEVICE_NAME : n;
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
