package com.potkc.overhear;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;

import com.potkc.overhear.runtime.ProgressListener;
import com.potkc.overhear.runtime.RuntimeInstaller;

/**
 * The API key, device name and auto start, and the licence notices. Saving installs the runtime and downloads
 * Soloist (with progress, cancellable) when the service is not running yet,
 * then starts the service and opens the player; from the player
 * (EXTRA_EDIT) a change restarts Soloist through the running service and
 * returns.
 *
 * The stored key is never put into the field (nor saved instance state or
 * screenshots: FLAG_SECURE): the field stays empty with a hint, and only a
 * newly typed key replaces it.
 */
public class SetupActivity extends Activity {
    /** Opened from the player's settings: return there when done. */
    static final String EXTRA_EDIT = "edit";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private EditText key;
    private EditText name;
    private CheckBox showKey;
    private CheckBox autoStart;
    private CheckBox fullscreen;
    private Button save;
    private Button cancel;
    private ProgressBar progress;
    private TextView message;
    private boolean edit;
    private boolean destroyed;
    /** The running install's installer (for cancel), or null. Main thread. */
    private RuntimeInstaller installing;
    /** Bumped per install and cancel: results of older attempts are ignored. */
    private volatile int attempt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The key is a secret: no screenshots, no recents thumbnail.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_setup);
        prefs = new Prefs(this);
        edit = getIntent().getBooleanExtra(EXTRA_EDIT, false);
        key = (EditText) findViewById(R.id.api_key);
        name = (EditText) findViewById(R.id.device_name);
        showKey = (CheckBox) findViewById(R.id.show_key);
        autoStart = (CheckBox) findViewById(R.id.auto_start);
        fullscreen = (CheckBox) findViewById(R.id.fullscreen);
        save = (Button) findViewById(R.id.save);
        cancel = (Button) findViewById(R.id.cancel);
        progress = (ProgressBar) findViewById(R.id.progress);
        message = (TextView) findViewById(R.id.message);

        if (edit) save.setText(R.string.setup_save_edit);
        if (prefs.hasApiKey()) key.setHint(R.string.setup_key_saved_hint);
        if (savedInstanceState == null) {
            name.setText(prefs.getDeviceName());
            autoStart.setChecked(prefs.isAutoStart());
            fullscreen.setChecked(prefs.isFullscreen());
        }
        showKey.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                int sel = key.getSelectionEnd();
                key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                if (sel >= 0) key.setSelection(Math.min(sel, key.length()));
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onSave();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cancelInstall();
            }
        });
        TextView licenses = (TextView) findViewById(R.id.licenses);
        // White like the rest of setup; the underline keeps it reading as a link.
        licenses.setPaintFlags(licenses.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        licenses.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Licenses.show(SetupActivity.this);
            }
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (installing != null) {
            cancelInstall();
            return;
        }
        super.onBackPressed();
    }

    private void onSave() {
        if (installing != null) return;
        String typed = key.getText().toString().trim();
        String n = name.getText().toString().trim();
        if (typed.isEmpty() && !prefs.hasApiKey()) {
            message.setText(R.string.setup_need_key);
            key.requestFocus();
            return;
        }
        if (n.isEmpty()) n = Prefs.defaultDeviceName();
        boolean changed = (!typed.isEmpty() && !typed.equals(prefs.getApiKey()))
                || !n.equals(prefs.getDeviceName());
        if (!prefs.save(new SetupValues(typed, n, android.os.Build.MODEL, autoStart.isChecked(), fullscreen.isChecked()))) {
            message.setText(R.string.setup_save_failed);
            return;
        }
        key.setText("");
        key.setHint(R.string.setup_key_saved_hint);

        PlayerService running = PlayerService.current();
        if (running != null) {
            // The service owns the runtime: let it restart with the new settings
            // (or retry after giving up).
            if (changed || running.getSupervisorState() == Supervisor.State.FAILED) running.retry();
            else PlayerService.start(this);
            done();
            return;
        }
        install();
    }

    /** ensureBundle, then ensureSoloist with progress, on a worker thread; then done(). */
    private void install() {
        final Context app = getApplicationContext();
        final RuntimeInstaller installer = new RuntimeInstaller(app);
        final int my = ++attempt;
        installing = installer;
        setWorking(true);
        message.setText(R.string.setup_installing);
        new Thread(new Runnable() {
            @Override public void run() {
                String error;
                try {
                    synchronized (PlayerService.INSTALL_LOCK) {
                        installer.ensureBundle();
                        RuntimeInstaller.Result r = installer.ensureSoloist(false, new ProgressListener() {
                            @Override public void onProgress(final int percent) {
                                post(my, new Runnable() {
                                    @Override public void run() {
                                        progress.setIndeterminate(false);
                                        progress.setProgress(percent);
                                        message.setText(getString(R.string.setup_downloading, percent));
                                    }
                                });
                            }

                            @Override public void onExtracting() {
                                post(my, new Runnable() {
                                    @Override public void run() {
                                        progress.setIndeterminate(true);
                                        message.setText(R.string.setup_extracting);
                                    }
                                });
                            }
                        });
                        error = r.ok ? null : r.message;
                    }
                } catch (IOException e) {
                    error = e.getMessage();
                }
                final String err = error;
                // Even if the screen went away meanwhile, but not after a cancel.
                if (err == null && my == attempt) PlayerService.start(app);
                post(my, new Runnable() {
                    @Override public void run() {
                        installing = null;
                        setWorking(false);
                        if (err == null) {
                            done();
                        } else {
                            message.setText(getString(R.string.setup_failed, err));
                            save.setText(R.string.setup_retry);
                        }
                    }
                });
            }
        }, "setup-install").start();
    }

    private void cancelInstall() {
        RuntimeInstaller i = installing;
        if (i == null) return;
        i.cancelDownload(); // the worker's result is ignored (attempt changes)
        installing = null;
        attempt++;
        setWorking(false);
        message.setText(R.string.setup_cancelled);
        save.setText(R.string.setup_retry);
    }

    private void setWorking(boolean working) {
        save.setEnabled(!working);
        key.setEnabled(!working);
        name.setEnabled(!working);
        showKey.setEnabled(!working);
        autoStart.setEnabled(!working);
        fullscreen.setEnabled(!working);
        cancel.setVisibility(working ? View.VISIBLE : View.GONE);
        progress.setVisibility(working ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(true);
    }

    /** Runs r on the main thread unless the screen is gone or attempt my was cancelled. */
    private void post(final int my, final Runnable r) {
        main.post(new Runnable() {
            @Override public void run() {
                if (!destroyed && my == attempt) r.run();
            }
        });
    }

    private void done() {
        if (!edit) startActivity(new Intent(this, PlayerActivity.class));
        finish();
    }
}
