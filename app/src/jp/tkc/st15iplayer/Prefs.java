package jp.tkc.st15iplayer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App settings in MODE_PRIVATE SharedPreferences (only this app's uid can
 * read them). The Spotify developer API key lives here; never log it.
 */
public final class Prefs {
    public static final String DEFAULT_DEVICE_NAME = SetupValues.DEFAULT_DEVICE_NAME;
    private static final String FILE = "st15i";
    private static final String API_KEY = "api_key";
    private static final String DEVICE_NAME = "device_name";
    private static final String AUTO_START = "auto_start";
    private static final String FULLSCREEN = "fullscreen";

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

    public String getDeviceName() {
        String n = prefs.getString(DEVICE_NAME, DEFAULT_DEVICE_NAME).trim();
        return n.isEmpty() ? DEFAULT_DEVICE_NAME : n;
    }

    public void setDeviceName(String name) {
        prefs.edit().putString(DEVICE_NAME, name == null ? DEFAULT_DEVICE_NAME : name.trim()).commit();
    }

    /**
     * Stores the device name, auto start, full screen and, unless the key is
     * blank, the key, in one commit. Returns whether it was written.
     */
    boolean save(SetupValues v) {
        SharedPreferences.Editor e = prefs.edit();
        if (v.keyToStore() != null) e.putString(API_KEY, v.keyToStore());
        e.putString(DEVICE_NAME, v.deviceName());
        e.putBoolean(AUTO_START, v.autoStart());
        e.putBoolean(FULLSCREEN, v.fullscreen());
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

    public void setAutoStart(boolean autoStart) {
        prefs.edit().putBoolean(AUTO_START, autoStart).commit();
    }
}
