package jp.tkc.st15iplayer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jp.tkc.st15iplayer.audio.AudioSink;
import jp.tkc.st15iplayer.runtime.ProgressListener;
import jp.tkc.st15iplayer.runtime.RuntimeInstaller;
import jp.tkc.st15iplayer.runtime.SoloistProcess;
import jp.tkc.st15iplayer.soloist.MainThreadExecutor;
import jp.tkc.st15iplayer.soloist.PlayerState;
import jp.tkc.st15iplayer.soloist.SoloistClient;

/**
 * The foreground service that makes the phone a Spotify Connect speaker. It
 * is the single owner of AudioSink, SoloistProcess, SoloistClient and
 * RuntimeInstaller (new ones per service lifetime, released in onDestroy),
 * and holds a MulticastLock (without it the Wi-Fi driver drops the mDNS
 * queries and phones never see the device), a high-performance WifiLock and
 * a partial wake lock while it wants Soloist running.
 *
 * Supervisor decides when to start, restart (backoff, expiry, Wi-Fi) or give
 * up; this class carries it out. Its methods and every callback run on the
 * main thread; installs, downloads and the fork run on one worker thread.
 *
 * Controls: notification actions (prev / play-pause / next), media buttons
 * through a RemoteControlClient, audio focus through AudioSink, and
 * "becoming noisy" (headphones unplugged) pauses.
 */
public final class PlayerService extends Service {
    private static final String TAG = "ST15iService";
    private static final String PKG = "jp.tkc.st15iplayer.action.";
    public static final String ACTION_START = PKG + "START";
    public static final String ACTION_STOP = PKG + "STOP";
    public static final String ACTION_PLAY_PAUSE = PKG + "PLAY_PAUSE";
    public static final String ACTION_PREV = PKG + "PREV";
    public static final String ACTION_NEXT = PKG + "NEXT";
    /** From MediaButtonReceiver; EXTRA_KEYCODE holds the key. */
    static final String ACTION_MEDIA_KEY = PKG + "MEDIA_KEY";
    static final String EXTRA_KEYCODE = "keycode";
    /** Debug: restart Soloist; EXTRA_TRACE_AUDIO turns fakepulse tracing on or off. */
    public static final String ACTION_RESTART_SOLOIST = PKG + "RESTART_SOLOIST";
    public static final String EXTRA_TRACE_AUDIO = "trace_audio";

    private static final int NOTIFICATION_ID = 1;
    private static final int NOTIFICATION_NO_KEY = 2;
    private static final long WIFI_DEBOUNCE_MS = 2000;
    private static final long PREVIOUS_EXIT_WAIT_MS = 3000;

    /** The live instance, for MediaButtonReceiver and the debug screen. Main thread. */
    private static PlayerService current;
    /** The previous instance's Soloist, which may still be shutting down. */
    private static volatile SoloistProcess previousSoloist;
    /** Serialises installs and downloads across service instances and SetupActivity. */
    static final Object INSTALL_LOCK = new Object();

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    private Prefs prefs;
    private RuntimeInstaller installer;
    private SoloistProcess soloist;
    private SoloistClient client;
    private AudioSink sink;
    private Supervisor supervisor;
    private AudioManager audioManager;
    private NotificationManager notifications;
    private ConnectivityManager connectivity;
    private WifiManager wifi;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private ComponentName mediaButtons;
    private RemoteControlClient rcc;
    private boolean begun;
    private boolean mediaRegistered;
    /** The generation of the last Effects.start (main thread). */
    private int runGen;
    /** The last login/key error from Soloist, shown until a login is reported. */
    private String authError;
    private PowerManager.WakeLock debounceLock;
    private boolean destroyed;
    private volatile boolean traceAudio;
    /** The Wi-Fi network last seen up ("" when down): a change means a new network. */
    private String wifiKey = "";
    private String status = "";
    private String lastNotified;
    private String lastMetadata;
    private int lastPlayState = -1;
    private PlayerState state = new PlayerState();
    private boolean connected;
    private final IBinder binder = new LocalBinder();
    private final CopyOnWriteArrayList<Runnable> uiListeners = new CopyOnWriteArrayList<Runnable>();

    /** For activities in this process: bind with flags 0 (binding never starts or keeps us). */
    final class LocalBinder extends Binder {
        PlayerService service() {
            return PlayerService.this;
        }
    }

    /** Starts the service (and Soloist) if it is not running. */
    public static void start(Context c) {
        c.startService(new Intent(c, PlayerService.class).setAction(ACTION_START));
    }

    /** Stops Soloist and the service. */
    public static void stop(Context c) {
        c.stopService(new Intent(c, PlayerService.class));
    }

    /** The running instance or null. Main thread only. */
    static PlayerService current() {
        return current;
    }

    // --- lifecycle ---

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        prefs = new Prefs(this);
        status = getString(R.string.status_starting_service);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("st15i-player-mdns");
        multicastLock.setReferenceCounted(false);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "st15i-player");
        wifiLock.setReferenceCounted(false);
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "st15i-player");
        wakeLock.setReferenceCounted(false);
        debounceLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "st15i-player-wifi");
        debounceLock.setReferenceCounted(false);

        startForeground(NOTIFICATION_ID, buildNotification());

        workerThread = new HandlerThread("ST15iWorker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        installer = new RuntimeInstaller(this);
        soloist = new SoloistProcess(installer, new SoloistProcess.Listener() {
            @Override public void onExit(final int tag, final int code, final boolean expired,
                    final boolean requested) {
                main.post(new Runnable() {
                    @Override public void run() {
                        onSoloistExit(tag, code, expired, requested);
                    }
                });
            }
        });
        supervisor = new Supervisor(effects);

        client = new SoloistClient(new MainThreadExecutor());
        client.setLogger(new SoloistClient.Logger() {
            @Override public void log(String message) {
                Log.i(TAG, "api " + message);
            }
        });
        client.addListener(clientListener);
        client.start();

        sink = new AudioSink(this, null);
        sink.setFocusHandler(new AudioSink.FocusHandler() {
            @Override public boolean pausePlayback() {
                Log.i(TAG, "audio focus lost: pause");
                client.pause();
                return client.isConnected();
            }

            @Override public void resumePlayback() {
                Log.i(TAG, "audio focus back: play");
                client.play();
            }
        });
        sink.start();

        mediaButtons = new ComponentName(this, MediaButtonReceiver.class);
        Intent mb = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(mediaButtons);
        rcc = new RemoteControlClient(PendingIntent.getBroadcast(this, 0, mb, 0));
        rcc.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                | RemoteControlClient.FLAG_KEY_MEDIA_NEXT | RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS);

        wifiKey = currentWifiKey();
        registerReceiver(connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        Log.i(TAG, "created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        // A null intent is a restart by the system after it killed us (sticky).
        if (action == null || ACTION_START.equals(action)) {
            begin();
        } else if (ACTION_STOP.equals(action)) {
            stopSelf();
        } else if (!begun) {
            // A stale notification or media key created us: do not start Soloist for it.
            Log.i(TAG, "ignoring " + action + " while not started");
            stopSelf(startId);
        } else if (ACTION_PLAY_PAUSE.equals(action)) {
            playPause();
        } else if (ACTION_PREV.equals(action)) {
            client.prev();
        } else if (ACTION_NEXT.equals(action)) {
            client.next();
        } else if (ACTION_MEDIA_KEY.equals(action)) {
            mediaKey(intent.getIntExtra(EXTRA_KEYCODE, 0));
        } else if (ACTION_RESTART_SOLOIST.equals(action)) {
            traceAudio = intent.getBooleanExtra(EXTRA_TRACE_AUDIO, false);
            Log.i(TAG, "restart requested" + (traceAudio ? " with audio trace" : ""));
            retry();
        }
        return START_STICKY;
    }

    private void begin() {
        if (!prefs.hasApiKey()) {
            Log.w(TAG, "no API key: not starting");
            Notification n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.notify_no_key))
                    .setContentIntent(activityIntent())
                    .setAutoCancel(true)
                    .build();
            notifications.notify(NOTIFICATION_NO_KEY, n);
            stopSelf();
            return;
        }
        notifications.cancel(NOTIFICATION_NO_KEY);
        if (!begun) Log.i(TAG, "starting");
        begun = true;
        acquireWifiLocks();
        registerMedia();
        supervisor.begin(isWifiUp());
        if (supervisor.state() == Supervisor.State.WAIT_WIFI) setStatus(getString(R.string.status_wait_wifi));
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "destroyed");
        destroyed = true;
        current = null;
        uiListeners.clear();
        supervisor.shutdown();
        installer.cancelDownload(); // sticky: a queued start fails at once
        soloist.release();
        previousSoloist = soloist;
        worker.removeCallbacksAndMessages(null);
        workerThread.quit();
        main.removeCallbacksAndMessages(null);
        client.removeListener(clientListener);
        client.stop();
        sink.setFocusHandler(null);
        sink.stop();
        sink.abandonFocus();
        unregisterReceiver(connectivityReceiver);
        unregisterReceiver(noisyReceiver);
        unregisterMedia();
        releaseLocks();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // --- for the player screen (main thread) ---

    /** Runs (on the main thread) whenever the state, connection or status changes. */
    void addUiListener(Runnable r) {
        uiListeners.addIfAbsent(r);
    }

    void removeUiListener(Runnable r) {
        uiListeners.remove(r);
    }

    private void notifyUi() {
        for (Runnable r : uiListeners) r.run();
    }

    PlayerState getPlayerState() {
        return state;
    }

    boolean isClientConnected() {
        return connected;
    }

    Supervisor.State getSupervisorState() {
        return supervisor.state();
    }

    String getStatus() {
        return status;
    }

    /** The last login/key error from Soloist, until a login is reported; or null. */
    String getAuthError() {
        return authError;
    }

    /** Commands for the player screen; dropped while disconnected. */
    SoloistClient client() {
        return client;
    }

    /** Pause while playing or buffering, else play. */
    void togglePlayPause() {
        playPause();
    }

    /** The user asked to try again (status line), or settings changed. */
    void retry() {
        acquireWifiLocks();
        registerMedia();
        supervisor.restart();
    }

    // --- Supervisor effects ---

    private final Runnable timer = new Runnable() {
        @Override public void run() {
            supervisor.onTimer();
        }
    };

    private final Supervisor.Effects effects = new Supervisor.Effects() {
        @Override public void start(final int gen, final boolean refresh) {
            runGen = gen;
            authError = null;
            // A retry after giving up (by the user or the cooldown) wants
            // the keys and Wi-Fi locks back; no-ops otherwise.
            acquireWifiLocks();
            registerMedia();
            setStatus(getString(refresh ? R.string.status_updating : R.string.status_starting));
            final boolean trace = traceAudio;
            worker.post(new Runnable() {
                @Override public void run() {
                    startOnWorker(gen, refresh, trace);
                }
            });
        }

        @Override public void stop() {
            soloist.stop();
            if (!soloist.isRunning()) {
                // Already gone (its exit may be queued too; the Supervisor
                // takes the first): complete the stop so nothing waits forever.
                final int gen = runGen;
                main.post(new Runnable() {
                    @Override public void run() {
                        onSoloistExit(gen, -1, false, true);
                    }
                });
            }
        }

        @Override public void setActive(boolean active) {
            Log.i(TAG, active ? "active: holding the wake lock" : "inactive: wake lock released");
            if (active) wakeLock.acquire();
            else if (wakeLock.isHeld()) wakeLock.release();
        }

        @Override public void schedule(long delayMs) {
            Log.i(TAG, "restarting Soloist in " + delayMs / 1000 + " s");
            if (supervisor.state() == Supervisor.State.BACKOFF) {
                setStatus(getString(R.string.status_restart_in, delayMs / 1000));
            }
            main.removeCallbacks(timer);
            main.postDelayed(timer, delayMs);
        }

        @Override public void cancelTimer() {
            main.removeCallbacks(timer);
        }

        @Override public void failed(String reason, boolean autoRetry) {
            Log.w(TAG, "giving up: " + reason + (autoRetry ? " (will retry)" : ""));
            setStatus(getString(R.string.status_stopped, reason));
            // Let other players have the media keys while we cannot play,
            // and stop holding Wi-Fi in high-performance mode for nothing.
            unregisterMedia();
            if (multicastLock.isHeld()) multicastLock.release();
            if (wifiLock.isHeld()) wifiLock.release();
        }
    };

    /** Worker thread: install, download if needed, start. Reports to the Supervisor on main. */
    private void startOnWorker(final int gen, boolean refresh, boolean trace) {
        String error = null;
        boolean retry = true;
        try {
            boolean previousRunning = waitForPreviousSoloist();
            if (!prefs.hasApiKey()) {
                error = getString(R.string.error_no_key);
                retry = false;
            } else {
                RuntimeInstaller.Result r;
                // One install at a time across service instances: a previous
                // instance's worker may still be finishing (it was cancelled).
                synchronized (INSTALL_LOCK) {
                    installer.ensureBundle();
                    ProgressListener progress = new ProgressListener() {
                        @Override public void onProgress(int percent) {
                            postStatus(getString(R.string.status_downloading, percent));
                        }

                        @Override public void onExtracting() {
                            postStatus(getString(R.string.status_extracting));
                        }
                    };
                    r = installer.ensureSoloist(refresh, progress);
                    if (!r.ok && r.noSpace) {
                        if (previousRunning) {
                            Log.w(TAG, "low storage, but the previous Soloist still runs: not clearing its cache");
                        } else {
                            // No Soloist is running, so its cache is fair game.
                            installer.clearSoloistCache();
                            r = installer.ensureSoloist(refresh, progress);
                        }
                    }
                }
                if (!r.ok) {
                    error = r.message;
                } else {
                    soloist.start(prefs.getDeviceName(), prefs.getApiKey(), false, trace, gen);
                }
            }
        } catch (IOException e) {
            error = e.getMessage();
        } catch (IllegalArgumentException e) {
            error = getString(R.string.error_bad_key, e.getMessage());
            retry = false;
        } catch (RuntimeException e) {
            // IllegalStateException: released (we are being destroyed) or still running.
            error = e.toString();
        }
        final String err = error;
        final boolean again = retry;
        final long now = SystemClock.elapsedRealtime();
        main.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                if (err == null) {
                    setStatus(getString(R.string.status_connecting));
                    supervisor.onStarted(gen, now);
                } else {
                    Log.w(TAG, "start failed: " + err);
                    setStatus(getString(R.string.status_start_failed, err));
                    supervisor.onStartFailed(gen, err, again);
                }
            }
        });
    }

    /** Waits a little for the previous instance's Soloist; true if it still runs. */
    private static boolean waitForPreviousSoloist() {
        SoloistProcess prev = previousSoloist;
        if (prev == null) return false;
        long deadline = SystemClock.elapsedRealtime() + PREVIOUS_EXIT_WAIT_MS;
        while (prev.isRunning() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        if (prev.isRunning()) return true;
        previousSoloist = null;
        return false;
    }

    private void onSoloistExit(int gen, int code, boolean expired, boolean requested) {
        if (destroyed) return;
        if (!requested) Log.w(TAG, "Soloist exited with " + code + (expired ? " (expired)" : ""));
        supervisor.onExit(gen, code, expired, requested, SystemClock.elapsedRealtime());
        if (supervisor.state() == Supervisor.State.WAIT_WIFI) setStatus(getString(R.string.status_wait_wifi));
    }

    // --- Wi-Fi ---

    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // Stay awake until the debounced check has run, even with the screen off.
            debounceLock.acquire(WIFI_DEBOUNCE_MS + 1000);
            main.removeCallbacks(wifiCheck);
            main.postDelayed(wifiCheck, WIFI_DEBOUNCE_MS);
        }
    };

    /** Runs WIFI_DEBOUNCE_MS after the last connectivity change. */
    private final Runnable wifiCheck = new Runnable() {
        @Override public void run() {
            String key = currentWifiKey();
            if (key.equals(wifiKey)) return;
            Log.i(TAG, "Wi-Fi " + (key.isEmpty() ? "down" : wifiKey.isEmpty() ? "up" : "changed"));
            boolean changed = !key.isEmpty() && !wifiKey.isEmpty();
            wifiKey = key;
            if (changed) supervisor.onWifi(false); // another network: restart as if it had dropped
            if (!key.isEmpty() && begun && supervisor.state() != Supervisor.State.FAILED) {
                // The driver may have dropped the multicast filter exemption.
                multicastLock.release();
                multicastLock.acquire();
            }
            supervisor.onWifi(!key.isEmpty());
            if (supervisor.state() == Supervisor.State.WAIT_WIFI) setStatus(getString(R.string.status_wait_wifi));
        }
    };

    private boolean isWifiUp() {
        NetworkInfo n = connectivity.getActiveNetworkInfo();
        return n != null && n.getType() == ConnectivityManager.TYPE_WIFI && n.isConnected();
    }

    /** "" while Wi-Fi is down, else something that changes with the network or address. */
    private String currentWifiKey() {
        if (!isWifiUp()) return "";
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null) return "wifi";
        // Not the BSSID: roaming between access points of one network must not restart Soloist.
        return info.getSSID() + "/" + info.getIpAddress();
    }

    /**
     * The multicast and Wi-Fi locks are held for the whole started service,
     * even with Wi-Fi down (so the sleep policy never turns Wi-Fi off; they
     * cost no CPU). The wake lock follows Supervisor activity (setActive).
     */
    private void acquireWifiLocks() {
        multicastLock.acquire();
        wifiLock.acquire();
    }

    private void releaseLocks() {
        if (multicastLock.isHeld()) multicastLock.release();
        if (wifiLock.isHeld()) wifiLock.release();
        if (wakeLock.isHeld()) wakeLock.release();
        if (debounceLock.isHeld()) debounceLock.release();
    }

    private void registerMedia() {
        if (mediaRegistered) return;
        mediaRegistered = true;
        audioManager.registerMediaButtonEventReceiver(mediaButtons);
        audioManager.registerRemoteControlClient(rcc);
        lastPlayState = -1;
        lastMetadata = null;
        updateRemoteControl();
    }

    private void unregisterMedia() {
        if (!mediaRegistered) return;
        mediaRegistered = false;
        audioManager.unregisterRemoteControlClient(rcc);
        audioManager.unregisterMediaButtonEventReceiver(mediaButtons);
    }

    // --- controls ---

    private void playPause() {
        PlayerState.Status s = state.getStatus();
        if (s == PlayerState.Status.PLAYING || s == PlayerState.Status.BUFFERING) client.pause();
        else client.play();
    }

    private void mediaKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                playPause();
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                client.play();
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                client.pause();
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                client.next();
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                client.prev();
                break;
            default:
                return;
        }
        Log.i(TAG, "media key " + keyCode);
    }

    /** Called by MediaButtonReceiver; false when no service is running. */
    static boolean dispatchMediaKey(Context c, int keyCode) {
        if (current == null || !current.begun || current.supervisor.state() == Supervisor.State.FAILED) {
            return false;
        }
        c.startService(new Intent(c, PlayerService.class).setAction(ACTION_MEDIA_KEY)
                .putExtra(EXTRA_KEYCODE, keyCode));
        return true;
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "audio becoming noisy: pause");
            client.pause();
        }
    };

    private final SoloistClient.Listener clientListener = new SoloistClient.Listener() {
        @Override public void onState(PlayerState s) {
            state = s;
            if (authError != null && s.isLoggedIn()) {
                authError = null;
                setReady();
            }
            updateNotification();
            updateRemoteControl();
            notifyUi();
        }

        @Override public void onConnected(boolean c) {
            connected = c;
            if (c) {
                // A login error stays shown until a state reports a login.
                if (authError == null) setReady();
                if (sink.isFocusMuted()) {
                    Log.i(TAG, "reconnected while muted for a focus loss: pause");
                    client.pause();
                }
            } else {
                state = new PlayerState();
                updateNotification();
                updateRemoteControl();
            }
            notifyUi();
        }

        @Override public void onError(String message) {
            Log.w(TAG, "Soloist: " + message);
            if (Supervisor.isAuthError(message)) {
                authError = message;
                supervisor.onAuthError();
                setStatus(getString(R.string.status_login_error, message));
            }
        }
    };

    // --- notification and remote control ---

    private void setReady() {
        setStatus(PlayerText.selectDevice(prefs.getDeviceName()));
    }

    private void postStatus(final String s) {
        main.post(new Runnable() {
            @Override public void run() {
                if (!destroyed) setStatus(s);
            }
        });
    }

    private void setStatus(String s) {
        status = s;
        updateNotification();
        notifyUi();
    }

    private boolean isPlaying() {
        PlayerState.Status s = state.getStatus();
        return s == PlayerState.Status.PLAYING || s == PlayerState.Status.BUFFERING;
    }

    private boolean hasTrack() {
        return client != null && client.isConnected() && !TextUtils.isEmpty(state.getTitle());
    }

    private void updateNotification() {
        if (destroyed || notifications == null) return;
        String title = hasTrack() ? state.getTitle() : getString(R.string.app_name);
        String text = hasTrack() ? TextUtils.join(", ", state.getArtists()) : status;
        String key = title + "\n" + text + "\n" + isPlaying() + "\n" + hasTrack();
        if (key.equals(lastNotified)) return;
        lastNotified = key;
        notifications.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        boolean track = hasTrack();
        boolean playing = track && isPlaying();
        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(track ? state.getTitle() : getString(R.string.app_name))
                .setContentText(track ? TextUtils.join(", ", state.getArtists()) : status)
                .setContentIntent(activityIntent())
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_DEFAULT);
        if (track) {
            if (!TextUtils.isEmpty(state.getAlbum())) b.setSubText(state.getAlbum());
            b.addAction(android.R.drawable.ic_media_previous, "", serviceIntent(ACTION_PREV));
            b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    "", serviceIntent(ACTION_PLAY_PAUSE));
            b.addAction(android.R.drawable.ic_media_next, "", serviceIntent(ACTION_NEXT));
        }
        return b.build();
    }

    private PendingIntent activityIntent() {
        Intent i = new Intent(this, PlayerActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, i, 0);
    }

    private PendingIntent serviceIntent(String action) {
        return PendingIntent.getService(this, 0, new Intent(this, PlayerService.class).setAction(action), 0);
    }

    private void updateRemoteControl() {
        if (rcc == null || !mediaRegistered) return;
        int ps;
        switch (client.isConnected() ? state.getStatus() : PlayerState.Status.IDLE) {
            case PLAYING:
                ps = RemoteControlClient.PLAYSTATE_PLAYING;
                break;
            case BUFFERING:
                ps = RemoteControlClient.PLAYSTATE_BUFFERING;
                break;
            case PAUSED:
                ps = RemoteControlClient.PLAYSTATE_PAUSED;
                break;
            default:
                ps = RemoteControlClient.PLAYSTATE_STOPPED;
                break;
        }
        if (ps != lastPlayState) {
            if (ps == RemoteControlClient.PLAYSTATE_PLAYING) {
                // Another player may have pushed itself on top of the media
                // button stack since onCreate; whoever plays should get the keys.
                audioManager.registerMediaButtonEventReceiver(mediaButtons);
            }
            lastPlayState = ps;
            rcc.setPlaybackState(ps);
        }
        String meta = state.getTrackUri() + "\n" + state.getTitle();
        if (meta.equals(lastMetadata)) return;
        lastMetadata = meta;
        rcc.editMetadata(true)
                .putString(MediaMetadataRetriever.METADATA_KEY_TITLE, state.getTitle())
                .putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, TextUtils.join(", ", state.getArtists()))
                .putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, state.getAlbum())
                .putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, state.getDurationMs())
                .apply();
    }

    // --- debug screen ---

    /** One-paragraph state for the debug screen. */
    String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("service: ").append(supervisor.state()).append(", Wi-Fi ")
                .append(supervisor.isWifiUp() ? "up" : "down").append('\n');
        sb.append("status: ").append(status).append('\n');
        sb.append("soloist: ").append(soloist.isRunning() ? "running" : "not running")
                .append(", api ").append(client.isConnected() ? "connected" : "disconnected").append('\n');
        if (hasTrack()) {
            sb.append(state.getStatus()).append(": ").append(state.getTitle()).append(" - ")
                    .append(TextUtils.join(", ", state.getArtists())).append('\n');
        }
        sb.append("locks: mc=").append(multicastLock.isHeld()).append(" wifi=").append(wifiLock.isHeld())
                .append(" wake=").append(wakeLock.isHeld()).append('\n');
        if (sink.isFocusMuted()) sb.append("muted (audio focus)\n");
        sb.append("fakepulse underflows: ").append(soloist.getUnderflowCount())
                .append(traceAudio ? " (tracing)" : " (trace off)");
        return sb.toString();
    }

    boolean isTracingAudio() {
        return traceAudio;
    }

    List<String> getLog() {
        return soloist.getLog();
    }
}
