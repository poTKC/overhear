package jp.tkc.st15iplayer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App settings in MODE_PRIVATE SharedPreferences (only this app's uid can
 * read them). The Spotify developer API key lives here; never log it.
 */
public final class Prefs {
    public static final String DEFAULT_DEVICE_NAME = "ST15i";
    private static final String FILE = "st15i";
    private static final String API_KEY = "api_key";
    private static final String DEVICE_NAME = "device_name";
    private static final String AUTO_START = "auto_start";

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
     * Stores the device name, auto start and, unless key is null or blank,
     * the trimmed key, in one commit. Returns whether it was written.
     */
    public boolean save(String key, String deviceName, boolean autoStart) {
        SharedPreferences.Editor e = prefs.edit();
        String k = key == null ? "" : key.trim();
        if (!k.isEmpty()) e.putString(API_KEY, k);
        e.putString(DEVICE_NAME, deviceName == null ? DEFAULT_DEVICE_NAME : deviceName.trim());
        e.putBoolean(AUTO_START, autoStart);
        return e.commit();
    }

    /** Start the service after boot (BootReceiver) and when the player opens. */
    public boolean isAutoStart() {
        return prefs.getBoolean(AUTO_START, true);
    }

    public void setAutoStart(boolean autoStart) {
        prefs.edit().putBoolean(AUTO_START, autoStart).commit();
    }
}
