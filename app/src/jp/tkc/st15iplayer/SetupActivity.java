package jp.tkc.st15iplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;

import jp.tkc.st15iplayer.runtime.ProgressListener;
import jp.tkc.st15iplayer.runtime.RuntimeInstaller;

/**
 * The API key and device name. Saving installs the runtime and downloads
 * Soloist (with progress) when the service is not running yet, then starts
 * the service and opens the player; from the player (EXTRA_EDIT) a change
 * restarts Soloist through the running service and returns.
 */
public class SetupActivity extends Activity {
    /** Opened from the player's settings: return there when done. */
    static final String EXTRA_EDIT = "edit";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private EditText key;
    private EditText name;
    private CheckBox showKey;
    private Button save;
    private ProgressBar progress;
    private TextView message;
    private boolean edit;
    private boolean working;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        prefs = new Prefs(this);
        edit = getIntent().getBooleanExtra(EXTRA_EDIT, false);
        key = (EditText) findViewById(R.id.api_key);
        name = (EditText) findViewById(R.id.device_name);
        showKey = (CheckBox) findViewById(R.id.show_key);
        save = (Button) findViewById(R.id.save);
        progress = (ProgressBar) findViewById(R.id.progress);
        message = (TextView) findViewById(R.id.message);

        if (edit) save.setText(R.string.setup_save_edit);
        if (savedInstanceState == null) {
            key.setText(prefs.getApiKey());
            name.setText(prefs.getDeviceName());
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
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (working) return; // let the download finish (or fail) first
        super.onBackPressed();
    }

    private void onSave() {
        if (working) return;
        String k = key.getText().toString().trim();
        String n = name.getText().toString().trim();
        if (k.isEmpty()) {
            message.setText(R.string.setup_need_key);
            key.requestFocus();
            return;
        }
        if (n.isEmpty()) n = Prefs.DEFAULT_DEVICE_NAME;
        final boolean changed = !k.equals(prefs.getApiKey()) || !n.equals(prefs.getDeviceName());
        if (!prefs.setApiKey(k)) {
            message.setText(R.string.setup_save_failed);
            return;
        }
        prefs.setDeviceName(n);

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
        working = true;
        save.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        message.setText(R.string.setup_installing);
        new Thread(new Runnable() {
            @Override public void run() {
                String error;
                RuntimeInstaller installer = new RuntimeInstaller(SetupActivity.this);
                try {
                    synchronized (PlayerService.INSTALL_LOCK) {
                        installer.ensureBundle();
                        RuntimeInstaller.Result r = installer.ensureSoloist(false, new ProgressListener() {
                            @Override public void onProgress(final int percent) {
                                post(new Runnable() {
                                    @Override public void run() {
                                        progress.setIndeterminate(false);
                                        progress.setProgress(percent);
                                        message.setText(getString(R.string.setup_downloading, percent));
                                    }
                                });
                            }

                            @Override public void onExtracting() {
                                post(new Runnable() {
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
                // Start even if the screen went away meanwhile.
                if (err == null) PlayerService.start(getApplicationContext());
                post(new Runnable() {
                    @Override public void run() {
                        working = false;
                        progress.setVisibility(View.GONE);
                        if (err == null) {
                            done();
                        } else {
                            message.setText(getString(R.string.setup_failed, err));
                            save.setText(R.string.setup_retry);
                            save.setEnabled(true);
                        }
                    }
                });
            }
        }, "setup-install").start();
    }

    private void post(final Runnable r) {
        main.post(new Runnable() {
            @Override public void run() {
                if (!destroyed) r.run();
            }
        });
    }

    private void done() {
        if (!edit) startActivity(new Intent(this, PlayerActivity.class));
        finish();
    }
}
