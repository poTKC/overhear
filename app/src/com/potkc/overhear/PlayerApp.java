package com.potkc.overhear;

import android.app.Application;
import android.view.ViewConfiguration;

/**
 * Seeds ViewConfiguration's static per-density cache with the application
 * context before any activity exists. ViewConfiguration.get(context) keeps
 * the first ViewConfiguration made for a density for the life of the
 * process, and on this ROM (CyanogenMod 10.2) it holds that context
 * (mContext): left to the first PlayerActivity, it kept that destroyed
 * activity and its whole view tree alive (found with a heap dump).
 */
public final class PlayerApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ViewConfiguration.get(this);
    }
}
