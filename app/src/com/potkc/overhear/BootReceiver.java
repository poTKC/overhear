package com.potkc.overhear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts PlayerService after boot when auto start is on and an API key is set. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Prefs prefs = new Prefs(context);
        if (!prefs.isAutoStart() || !prefs.hasApiKey()) {
            Log.i("OverhearService", "boot: auto start " + (prefs.isAutoStart() ? "on" : "off")
                    + ", API key " + (prefs.hasApiKey() ? "set" : "missing") + ": not starting");
            return;
        }
        Log.i("OverhearService", "boot: starting the player service");
        PlayerService.start(context);
    }
}
