package com.potkc.overhear.soloist;

import java.util.concurrent.Executor;

import android.os.Handler;
import android.os.Looper;

/** Runs SoloistClient callbacks on the main thread. */
public final class MainThreadExecutor implements Executor {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public void execute(Runnable r) {
        handler.post(r);
    }
}
