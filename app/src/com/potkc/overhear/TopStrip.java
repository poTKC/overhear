package com.potkc.overhear;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Calendar;

/**
 * The full-screen top strip (layout/top_strip.xml) of the player and the
 * library: the time left (system 12/24 h, no AM/PM: the digits font has no
 * letters), a battery dot and number right, as StatusStrip decides. apply()
 * sets the window's FLAG_FULLSCREEN from Prefs.isFullscreen and shows the
 * strip only then; call it before setContentView (no jump at launch) and on
 * every resume (setup may have changed it). The clock and battery receivers
 * run only between resume() and pause(): the strip is not seen otherwise.
 * Main thread only.
 */
final class TopStrip {
    /** Rubik Regular's digits (subset, tools/make_fonts.py), loaded once. */
    private static Typeface digits;

    private final Activity activity;
    private final View strip;
    private final TextView clock;
    private final TextView battery;
    private final GradientDrawable batteryDot;
    private boolean registered;

    /** For the strip included in activity's content view. */
    TopStrip(Activity activity) {
        this.activity = activity;
        strip = activity.findViewById(R.id.top_strip);
        clock = (TextView) activity.findViewById(R.id.clock);
        battery = (TextView) activity.findViewById(R.id.battery);
        batteryDot = new GradientDrawable();
        batteryDot.setShape(GradientDrawable.OVAL);
        batteryDot.setColor(StatusStrip.UNKNOWN);
        activity.findViewById(R.id.battery_dot).setBackground(batteryDot);
        // Digits and ':' only (StatusStrip.DIGITS_FONT_CHARS).
        Typeface d = digits(activity);
        if (d != null) {
            clock.setTypeface(d);
            battery.setTypeface(d);
        }
    }

    /**
     * Sets activity's full screen from prefs, and with a strip (null before
     * the content view is set) its visibility; whether the strip shows.
     */
    static boolean apply(Activity activity, TopStrip strip, Prefs prefs) {
        int full = WindowManager.LayoutParams.FLAG_FULLSCREEN;
        boolean want = prefs.isFullscreen();
        boolean shown = StatusStrip.visible(want);
        if (strip != null) strip.strip.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (((activity.getWindow().getAttributes().flags & full) != 0) != want) {
            if (want) activity.getWindow().addFlags(full);
            else activity.getWindow().clearFlags(full);
        }
        return shown;
    }

    /** The strip's height (its layout's), for what lies below it. */
    int height() {
        return strip.getLayoutParams().height;
    }

    /** From onResume: starts the clock and battery updates. */
    void resume() {
        if (registered) return;
        registered = true;
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        activity.registerReceiver(clockReceiver, time);
        updateBattery(activity.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        updateClock();
    }

    /** From onPause: stops them. */
    void pause() {
        if (!registered) return;
        registered = false;
        activity.unregisterReceiver(clockReceiver);
        activity.unregisterReceiver(batteryReceiver);
    }

    /** The digits typeface from assets, or null if it cannot be read (the default font then). */
    static Typeface digits(Context c) {
        if (digits == null) {
            try {
                digits = Typeface.createFromAsset(c.getAssets(), "fonts/overhear-digits.ttf");
            } catch (RuntimeException e) {
                Log.w("OverhearPlayer", "digits font: " + e);
            }
        }
        return digits;
    }

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
        }
    };

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        setText(clock, StatusStrip.clockText(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(activity)));
    }

    /** From the sticky ACTION_BATTERY_CHANGED (null if none yet). */
    private void updateBattery(Intent i) {
        if (i == null) return;
        int pct = StatusStrip.percent(i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                i.getIntExtra(BatteryManager.EXTRA_SCALE, -1));
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING;
        setText(battery, StatusStrip.batteryText(pct));
        batteryDot.setColor(StatusStrip.batteryColor(pct, charging));
    }

    private static void setText(TextView v, String text) {
        if (!text.contentEquals(v.getText())) v.setText(text);
    }
}
