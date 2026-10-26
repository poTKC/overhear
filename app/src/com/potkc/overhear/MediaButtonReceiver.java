package com.potkc.overhear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/**
 * Receives media button presses (headset hook, play/pause, next, previous)
 * routed to us through AudioManager.registerMediaButtonEventReceiver and the
 * RemoteControlClient, and hands them to the running PlayerService. Presses
 * while the service is not running are left alone.
 */
public final class MediaButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (ev == null) return;
        if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() != 0) {
            if (PlayerService.current() != null && isOrderedBroadcast()) abortBroadcast();
            return;
        }
        if (PlayerService.dispatchMediaKey(context, ev.getKeyCode()) && isOrderedBroadcast()) abortBroadcast();
    }
}
