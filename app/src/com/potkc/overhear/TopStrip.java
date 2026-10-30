package com.potkc.overhear;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.MotionEvent;
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
 *
 * Up to Android 4.3 the hidden status bar cannot be pulled down, so in full
 * screen dispatchTouch() takes a pull from the top edge (TopPull) and opens
 * the notification panel itself (EXPAND_STATUS_BAR). From 4.4 the system
 * shows the bar on that swipe and the touch never reaches the window, so
 * PULL is off there. The panel lives in the status bar's window, which stays hidden over a
 * full-screen window: the pull lifts full screen (laid out as before, the
 * bar over the strip) until the window has focus again (windowFocus).
 * Main thread only.
 */
final class TopStrip {
    /** Rubik Regular's digits (subset, tools/make_fonts.py), loaded once. */
    private static Typeface digits;
    /** Before 4.4 (KITKAT, 19; built against API 18): the top-edge pull. */
    private static final boolean PULL = Build.VERSION.SDK_INT < 19;

    private final Activity activity;
    private final View strip;
    private final TextView clock;
    private final TextView battery;
    private final GradientDrawable batteryDot;
    private final TopPull pull;
    /** The current touch became a pull: the rest of it is taken. */
    private boolean pulled;
    /** Full screen is lifted for the notification panel. */
    private boolean lifted;
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
        float density = activity.getResources().getDisplayMetrics().density;
        pull = new TopPull(24 * density, 32 * density);
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

    /**
     * From the activity's dispatchTouchEvent, before its views: whether e is
     * taken. In full screen, once a touch becomes a pull from the top edge,
     * the views get a cancel instead of it, the notification panel opens,
     * and the rest of the touch is taken.
     */
    boolean dispatchTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pulled = false;
                boolean full = (activity.getWindow().getAttributes().flags
                        & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
                if (PULL && full) pull.down(e.getRawX(), e.getRawY());
                else pull.end();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (pulled) return true;
                if (!pull.move(e.getRawX(), e.getRawY())) return false;
                pulled = true;
                MotionEvent cancel = MotionEvent.obtain(e);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                activity.getWindow().superDispatchTouchEvent(cancel);
                cancel.recycle();
                openNotifications();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pull.end();
                return pulled;
            default:
                return pulled;
        }
    }

    /** From onWindowFocusChanged: back with focus, the panel is closed and full screen returns. */
    void windowFocus(boolean hasFocus) {
        if (!hasFocus || !lifted) return;
        lifted = false;
        // Unless setup turned it off meanwhile (apply on resume hid the strip).
        if (strip.getVisibility() == View.VISIBLE) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        activity.getWindow().getDecorView().setSystemUiVisibility(0);
    }

    /** StatusBarManager is hidden API: expand() on 4.1, expandNotificationsPanel() from 4.2. */
    private void openNotifications() {
        lifted = true;
        // Laid out as in full screen: the content does not move under the bar.
        activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try {
            Object bar = activity.getSystemService("statusbar");
            String method = Build.VERSION.SDK_INT >= 17 ? "expandNotificationsPanel" : "expand";
            bar.getClass().getMethod(method).invoke(bar);
        } catch (Exception e) {
            Log.w("OverhearPlayer", "notification panel: " + e);
        }
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
