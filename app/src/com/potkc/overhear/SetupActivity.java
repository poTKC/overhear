package com.potkc.overhear;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;

import com.potkc.overhear.runtime.ProgressListener;
import com.potkc.overhear.runtime.RuntimeInstaller;
import com.potkc.overhear.spotify.SpotifyApi;

/**
 * In three sections: アカウント (read only: whether the Soloist API key and
 * the Spotify Client ID are stored, and the Spotify login, with ログアウト
 * while logged in, which takes effect at once; 「スマホで認証」
 * (LoginActivity) is the only way to change them, and onResume shows them
 * again), 再生 (the device name, the service row in edit mode, auto start)
 * and 表示 (full screen for the player and the library); then save and the
 * licence notices. Saving needs a stored key; it installs the runtime and
 * downloads Soloist (with progress, cancellable) when the service is not
 * running yet, then starts the service and opens the player; from the
 * player (EXTRA_EDIT) a change restarts Soloist through the running service
 * and returns. There (edit mode only) a button also stops or starts the
 * service at once, as its label says (PlayerText.serviceLabel).
 */
public class SetupActivity extends Activity {
    /** Opened from the player's settings: return there when done. */
    static final String EXTRA_EDIT = "edit";

    private final Handler main = new Handler(Looper.getMainLooper());
    /** After the service button, when to show the service's state again (a slow start or stop: the second). */
    private static final long SERVICE_RECHECK_MS = 500;
    private static final long SERVICE_RECHECK_LATE_MS = 1500;
    private Prefs prefs;
    private EditText name;
    private CheckBox autoStart;
    private CheckBox fullscreen;
    /** Choices in SetupValues.IDLE_STOP_CHOICES order. */
    private Spinner idleStop;
    private Button save;
    private Button cancel;
    private ProgressBar progress;
    private TextView message;
    private TextView keyStatus;
    private TextView clientIdStatus;
    private TextView spotifyStatus;
    private Button spotifyLogout;
    private Button phoneSetup;
    private TextView serviceStatus;
    private Button serviceButton;
    /** Bumped per Spotify status refresh: older me() results are ignored. Main thread. */
    private int spotifyRefresh;
    private boolean edit;
    private boolean destroyed;
    /** The running install's installer (for cancel), or null. Main thread. */
    private RuntimeInstaller installing;
    /** Bumped per install and cancel: results of older attempts are ignored. */
    private volatile int attempt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        prefs = new Prefs(this);
        edit = getIntent().getBooleanExtra(EXTRA_EDIT, false);
        name = (EditText) findViewById(R.id.device_name);
        autoStart = (CheckBox) findViewById(R.id.auto_start);
        fullscreen = (CheckBox) findViewById(R.id.fullscreen);
        idleStop = (Spinner) findViewById(R.id.idle_stop);
        ArrayAdapter<CharSequence> idleStops = ArrayAdapter.createFromResource(this,
                R.array.setup_idle_stop_entries, R.layout.setup_spinner_item);
        idleStops.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        idleStop.setAdapter(idleStops);
        save = (Button) findViewById(R.id.save);
        cancel = (Button) findViewById(R.id.cancel);
        progress = (ProgressBar) findViewById(R.id.progress);
        message = (TextView) findViewById(R.id.message);
        keyStatus = (TextView) findViewById(R.id.key_status);
        clientIdStatus = (TextView) findViewById(R.id.client_id_status);
        spotifyStatus = (TextView) findViewById(R.id.spotify_status);
        spotifyLogout = (Button) findViewById(R.id.spotify_logout);
        phoneSetup = (Button) findViewById(R.id.phone_setup);

        serviceStatus = (TextView) findViewById(R.id.service_status);
        serviceButton = (Button) findViewById(R.id.service_button);
        if (edit) {
            save.setText(R.string.setup_save_edit);
            findViewById(R.id.service_label).setVisibility(View.VISIBLE);
            findViewById(R.id.service_row).setVisibility(View.VISIBLE);
            serviceButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onServiceButton();
                }
            });
        }
        if (savedInstanceState == null) {
            name.setText(prefs.getDeviceName());
            autoStart.setChecked(prefs.isAutoStart());
            fullscreen.setChecked(prefs.isFullscreen());
            idleStop.setSelection(SetupValues.idleStopIndex(prefs.getIdleStopMinutes()));
        }
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
        spotifyLogout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onLogout();
            }
        });
        phoneSetup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SetupActivity.this, LoginActivity.class));
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
    protected void onResume() {
        super.onResume();
        showAccount(); // スマホで認証 may have changed it
        showService();
    }

    /** The account section as stored now. */
    private void showAccount() {
        keyStatus.setText(prefs.hasApiKey() ? R.string.setup_key_saved : R.string.setup_key_missing);
        clientIdStatus.setText(prefs.getSpotifyClientId().isEmpty()
                ? R.string.setup_client_id_missing : R.string.setup_client_id_saved);
        showSpotify();
    }

    /** Whether the service runs, and the button that changes it (edit mode). */
    private void showService() {
        if (!edit) return;
        boolean up = PlayerService.current() != null;
        serviceStatus.setText(up ? R.string.setup_service_running : R.string.setup_service_stopped);
        serviceButton.setText(PlayerText.serviceLabel(up));
    }

    private void onServiceButton() {
        // The button's label decides, as shown: a tap on 停止 never starts.
        if (PlayerText.serviceAction(PlayerText.STOP.contentEquals(serviceButton.getText()))
                == PlayerText.Action.STOP) {
            PlayerService.stop(this);
        } else {
            PlayerService.start(this);
        }
        // Starting and stopping take a moment (onCreate / onDestroy set PlayerService.current).
        main.postDelayed(showServiceLater, SERVICE_RECHECK_MS);
        main.postDelayed(showServiceLater, SERVICE_RECHECK_LATE_MS);
        showService();
    }

    private final Runnable showServiceLater = new Runnable() {
        @Override public void run() {
            if (!destroyed) showService();
        }
    };

    /** Back from the notification shade (which does not pause on 4.3), where ✕ may have stopped the service. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showService();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacks(showServiceLater);
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

    private void onLogout() {
        // logOut() takes the auth lock, which a call in progress may hold for
        // up to its timeout: do it on the Spotify thread, not here.
        spotifyLogout.setEnabled(false);
        Spotify.get(this).run(new Spotify.Call<Void>() {
            @Override public Void run(SpotifyApi api) {
                api.auth().logOut();
                return null;
            }
        }, new Spotify.Callback<Void>() {
            @Override public void done(Void result) {
                if (!destroyed) showSpotify();
            }

            @Override public void failed(IOException e) {
                if (!destroyed) showSpotify();
            }
        });
    }

    /** Shows the login state now, then the user ID once me() answers. */
    private void showSpotify() {
        final int my = ++spotifyRefresh;
        Spotify spotify = Spotify.get(this);
        spotifyLogout.setEnabled(installing == null);
        if (!spotify.isLoggedIn()) {
            spotifyStatus.setText(spotify.hasClientId()
                    ? R.string.setup_spotify_logged_out : R.string.setup_spotify_need_client_id);
            spotifyLogout.setVisibility(View.GONE);
            return;
        }
        spotifyStatus.setText(R.string.setup_spotify_logged_in);
        spotifyLogout.setVisibility(View.VISIBLE);
        spotify.run(new Spotify.Call<String>() {
            @Override public String run(SpotifyApi api) throws IOException {
                return api.me();
            }
        }, new Spotify.Callback<String>() {
            @Override public void done(String id) {
                if (destroyed || my != spotifyRefresh || id.isEmpty()) return;
                spotifyStatus.setText(getString(R.string.setup_spotify_logged_in_as, id));
            }

            @Override public void failed(IOException e) {
                // A login that expired was dropped: say so, without ログアウト.
                if (!destroyed && my == spotifyRefresh && !Spotify.get(SetupActivity.this).isLoggedIn()) {
                    showSpotify();
                }
            }
        });
    }

    private void onSave() {
        if (installing != null) return;
        if (!prefs.hasApiKey()) {
            message.setText(R.string.setup_need_key);
            return;
        }
        String n = name.getText().toString().trim();
        if (n.isEmpty()) n = Prefs.defaultDeviceName();
        boolean changed = !n.equals(prefs.getDeviceName());
        SetupValues values = new SetupValues(n, android.os.Build.MODEL, autoStart.isChecked(),
                fullscreen.isChecked(), SetupValues.idleStopMinutes(idleStop.getSelectedItemPosition()));
        if (!prefs.save(values)) {
            message.setText(R.string.setup_save_failed);
            return;
        }
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
        phoneSetup.setEnabled(!working);
        spotifyLogout.setEnabled(!working);
        name.setEnabled(!working);
        autoStart.setEnabled(!working);
        fullscreen.setEnabled(!working);
        idleStop.setEnabled(!working);
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
