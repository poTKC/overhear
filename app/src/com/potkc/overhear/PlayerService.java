package com.potkc.overhear;

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
import android.view.View;
import android.widget.RemoteViews;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.potkc.overhear.audio.AudioSink;
import com.potkc.overhear.runtime.ProgressListener;
import com.potkc.overhear.runtime.RuntimeInstaller;
import com.potkc.overhear.runtime.SoloistProcess;
import com.potkc.overhear.soloist.MainThreadExecutor;
import com.potkc.overhear.soloist.PlayerState;
import com.potkc.overhear.soloist.SoloistClient;
import com.potkc.overhear.spotify.OthersTracker;
import com.potkc.overhear.spotify.SpotifyApi;

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
 * Controls: notification buttons (prev / play-pause / next, and stop, which
 * ends the service: onDestroy stops Soloist and releases every lock), media buttons
 * through a RemoteControlClient, audio focus through AudioSink, and
 * "becoming noisy" (headphones unplugged) pauses.
 *
 * Queue jumps: where QueueRoute allows (logged in, a track of an album,
 * playlist or liked songs context ahead of any queued row, no shuffle), one
 * Web API play of the context from the tapped track; otherwise, or if that
 * call fails, QueueJump steps skip_next to the entry with the track muted
 * (AudioSink.setJumpMuted); any control pressed meanwhile ends it.
 *
 * Another user's playlist (the library cannot list it): from the play
 * the library asks for, an OthersTracker follows Soloist's state and its
 * whole queue (FULL_QUEUE_LIMIT) here, outliving the library screen, and
 * runs its timed checks; the library shows its list while the playlist is
 * the context. A new play replaces it; a finished one is kept.
 *
 * Volume: fakepulse plays at unity gain and VolumeSync ties Soloist's volume
 * (the phone's Spotify slider) to the STREAM_MUSIC index both ways, while
 * the service runs.
 */
public final class PlayerService extends Service {
    private static final String TAG = "OverhearService";
    private static final String PKG = "com.potkc.overhear.action.";
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
    /** Hidden on API 18 but sent by AudioService for every stream volume change. */
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    /** The volume panel for a refused raise, at most this often. */
    private static final long SAFE_VOLUME_UI_MS = 10000;
    /** An output change settles in AudioService a little after its broadcasts. */
    private static final long OUTPUT_RECHECK_MS = 1500;

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
    private VolumeSync volumeSync;
    private long lastSafeVolumeUi = Long.MIN_VALUE / 2;
    /** AudioManager.isStreamMute (hidden on API 18); null if missing. */
    private Method isStreamMute;
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
    /** The on-screen form of status (PlayerText.status), with its number. */
    private PlayerText.Phase phase = PlayerText.Phase.STARTING;
    private int phaseValue;
    private String lastNotified;
    private String lastMetadata;
    private int lastPlayState = -1;
    private PlayerState state = new PlayerState();
    private boolean connected;
    /** The queue jump in progress, or null. */
    private QueueJump jump;
    /** Jumps finished so far, and whether the last one reached its target (for the player screen). */
    private int jumpsFinished;
    private boolean lastJumpOk;
    /** The Web API jump (jumpTo) in progress, until it ends; or null. */
    private WebJump webJump;
    /** When a control was last pressed or a queue jump started (elapsedRealtime); 0 before. */
    private long lastControlMs;
    /** The last play of another user's playlist the library asked for; or null. */
    private OthersTracker others;
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
        phase = PlayerText.Phase.STARTING;
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        volumeSync = new VolumeSync(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), volumeTarget);
        try {
            isStreamMute = AudioManager.class.getMethod("isStreamMute", int.class);
        } catch (NoSuchMethodException e) {
            isStreamMute = null;
        }
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("overhear-mdns");
        multicastLock.setReferenceCounted(false);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "overhear");
        wifiLock.setReferenceCounted(false);
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "overhear");
        wakeLock.setReferenceCounted(false);
        debounceLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "overhear-wifi");
        debounceLock.setReferenceCounted(false);

        startForeground(NOTIFICATION_ID, buildNotification());

        workerThread = new HandlerThread("OverhearWorker");
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
        registerReceiver(volumeReceiver, new IntentFilter(VOLUME_CHANGED_ACTION));
        registerReceiver(headsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
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
            userInput();
            playPause();
        } else if (ACTION_PREV.equals(action)) {
            userInput();
            client.prev();
        } else if (ACTION_NEXT.equals(action)) {
            userInput();
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
        if (supervisor.state() == Supervisor.State.WAIT_WIFI) setStatus(getString(R.string.status_wait_wifi), PlayerText.Phase.WAIT_WIFI, 0);
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
        jump = null; // the sink goes with it
        client.removeListener(clientListener);
        client.stop();
        sink.setFocusHandler(null);
        sink.stop();
        sink.abandonFocus();
        unregisterReceiver(connectivityReceiver);
        unregisterReceiver(noisyReceiver);
        unregisterReceiver(volumeReceiver);
        unregisterReceiver(headsetReceiver);
        volumeSync.reset();
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

    /**
     * Runs (on the main thread) whenever the state, connection or status
     * changes, and after the others tracker's timed checks.
     */
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

    /** Gave up over the settings (key): the status line should open setup. */
    boolean needsSetup() {
        return supervisor.needsSetup();
    }

    String getStatus() {
        return status;
    }

    PlayerText.Phase getPhase() {
        return phase;
    }

    int getPhaseValue() {
        return phaseValue;
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
        userInput();
        playPause();
    }

    /** A control was pressed (here, in the notification or by a media key): a queue jump ends. */
    void userInput() {
        lastControlMs = SystemClock.elapsedRealtime();
        if (jump != null) jump.onUserInput();
        if (webJump != null) {
            // The user's command wins: a PUT still queued is cancelled; one already out may still land.
            webJump.onUserInput();
            notifyUi();
        }
    }

    // --- another user's playlist ---

    /**
     * Starts following a play of playlistUri (another user's playlist),
     * replacing the tracker before; call it before asking to play, and
     * othersPlayReturned once that request returned.
     */
    OthersTracker startOthers(String playlistUri) {
        main.removeCallbacks(othersTick);
        others = new OthersTracker(playlistUri);
        others.onOpen(client.getState());
        Log.i(TAG, "others: following " + playlistUri);
        notifyUi();
        return others;
    }

    /** The play request for t returned (done, or after the fallback): the timed checks start. */
    void othersPlayReturned(OthersTracker t) {
        if (destroyed || t != others) return;
        t.onPlayReturned(System.currentTimeMillis());
        main.postDelayed(othersTick, OthersTracker.ADOPT_AFTER_MS + 100);
        main.postDelayed(othersTick, OthersTracker.GIVE_UP_AFTER_MS + 100);
        main.postDelayed(othersTick, OthersTracker.LAST_CHECK_AFTER_MS + 100);
    }

    /** The last tracker startOthers made, finished or not; or null. Read it only. */
    OthersTracker getOthers() {
        return others;
    }

    private final Runnable othersTick = new Runnable() {
        @Override public void run() {
            if (destroyed || others == null) return;
            others.onTick(client.getState(), System.currentTimeMillis());
            askOthersQueue();
            notifyUi();
        }
    };

    /** Asks for the whole queue when the tracker wants it. */
    private void askOthersQueue() {
        if (others != null && others.takeQueueRequest(client.isConnected())) {
            Log.i(TAG, "others: asking for the whole queue");
            client.requestQueue(SoloistClient.FULL_QUEUE_LIMIT);
        }
    }

    // --- queue jump ---

    /**
     * Starts a jump to entry index of the upcoming queue (item as the screen
     * shows it): a Web API play of the context from item when QueueRoute
     * allows, else QueueJump. Either way it counts in getJumpsFinished once
     * done. False if a jump runs, the API is down, or the entry is no longer
     * there.
     */
    boolean jumpTo(int index, PlayerState.QueueItem item) {
        if (jump != null || webJump != null || !connected || item.uri == null) return false;
        lastControlMs = SystemClock.elapsedRealtime();
        Spotify spotify = Spotify.get(this);
        if (QueueRoute.useWebApi(state.getQueue(), index, state.getTrackUri(), state.getContextUri(),
                state.isShuffle(), spotify.isLoggedIn())) {
            webJump(spotify, index, item, state.getContextUri());
            return true;
        }
        return startQueueJump(index, item);
    }

    /**
     * Plays contextUri from item through the Web API; WebJump decides when it
     * ends (answer, control, timeout, or a fallback to QueueJump for the entry
     * if it is still where it was). Callbacks of an ended jump, or after
     * onDestroy (Spotify.run can answer then), do nothing.
     */
    private void webJump(final Spotify spotify, final int index, final PlayerState.QueueItem item,
            final String contextUri) {
        final String device = prefs.getDeviceName();
        Log.i(TAG, "web jump: to entry " + index + " " + item.uri);
        final WebJump[] self = new WebJump[1];
        WebJump.Effects fx = new WebJump.Effects() {
            @Override public void sendPlay(final WebJump.Ticket ticket) {
                spotify.run(new Spotify.Call<Void>() {
                    @Override public Void run(SpotifyApi api) throws IOException {
                        // Queued behind other calls: the jump may have ended meanwhile.
                        if (!ticket.begin()) return null;
                        api.play(device, contextUri, item.uri);
                        return null;
                    }
                }, new Spotify.Callback<Void>() {
                    @Override public void done(Void result) {
                        if (!webJumpLive(self[0])) return;
                        // Soloist reports the new track (track_changed); nothing more to do.
                        self[0].onPlayDone();
                        afterWebJumpEvent();
                    }

                    @Override public void failed(IOException e) {
                        if (!webJumpLive(self[0])) return;
                        self[0].onPlayFailed(e.getMessage());
                        afterWebJumpEvent();
                    }
                });
            }

            @Override public boolean startQueueJumpFallback() {
                // QueueJump takes over and counts itself.
                clearWebJump();
                return startQueueJump(index, item);
            }

            @Override public void log(String message) {
                Log.w(TAG, "web jump: " + message);
            }

            @Override public void finished(boolean ok, String why) {
                if (ok) Log.i(TAG, "web jump: " + why);
                else Log.w(TAG, "web jump: " + why);
                clearWebJump();
                countJump(ok);
                // The caller notifies the UI.
            }
        };
        WebJump j = new WebJump(index, item.uid, item.uri, webJumpClock, WebJump.DEFAULT_TIMEOUT_MS,
                WebJump.DEFAULT_RECHECK_MS, fx);
        self[0] = j;
        webJump = j;
        j.start(state);
        scheduleWebJumpTimer();
        notifyUi();
    }

    private boolean webJumpLive(WebJump j) {
        return !destroyed && j != null && j == webJump;
    }

    /** After an input to webJump: re-arm its timer and show the change. */
    private void afterWebJumpEvent() {
        scheduleWebJumpTimer();
        notifyUi();
    }

    private final WebJump.Clock webJumpClock = new WebJump.Clock() {
        @Override public long nowMs() {
            return SystemClock.elapsedRealtime();
        }
    };

    private final Runnable webJumpTimer = new Runnable() {
        @Override public void run() {
            if (destroyed || webJump == null) return;
            webJump.onTimer();
            afterWebJumpEvent();
        }
    };

    private void scheduleWebJumpTimer() {
        main.removeCallbacks(webJumpTimer);
        if (webJump != null) main.postDelayed(webJumpTimer, webJump.msUntilDeadline());
    }

    /** Forgets the web jump without counting it. */
    private void clearWebJump() {
        webJump = null;
        main.removeCallbacks(webJumpTimer);
    }

    private void countJump(boolean ok) {
        jumpsFinished++;
        lastJumpOk = ok;
    }

    /** QueueJump's skip_next steps to entry index; false if the entry is gone or the API is down. */
    private boolean startQueueJump(int index, PlayerState.QueueItem item) {
        if (jump != null || !connected) return false;
        QueueJump j = new QueueJump(index, item.uid, item.uri, jumpClock, QueueJump.DEFAULT_TIMEOUT_MS,
                jumpEffects);
        jump = j;
        if (!j.start(state)) {
            jump = null;
            Log.i(TAG, "queue jump: entry " + index + " is no longer in the queue");
            return false;
        }
        Log.i(TAG, "queue jump: to entry " + index + " (" + (index + 1) + " skips) " + item.uri + ", "
                + (j.wasPlaying() ? "playing" : "paused"));
        scheduleJumpTimer();
        notifyUi();
        return true;
    }

    /** When a control was last pressed or a queue jump started (elapsedRealtime); 0 if never. */
    long lastControlMs() {
        return lastControlMs;
    }

    /** A queue jump (QueueJump or the Web API's) is under way. */
    boolean isJumping() {
        return jump != null || webJump != null;
    }

    /** The running queue jump, or null. */
    QueueJump getJump() {
        return jump;
    }

    /**
     * A Web API jump is under way (getWebJumpUri and the rest describe its
     * target). It outlives a disconnect from Soloist until it ends (answer,
     * control, timeout).
     */
    boolean isWebJumping() {
        return webJump != null;
    }

    String getWebJumpUri() {
        return webJump == null ? null : webJump.getUri();
    }

    /** The target's uid as tapped, or null if it had none. */
    String getWebJumpUid() {
        return webJump == null ? null : webJump.getUid();
    }

    int getWebJumpIndex() {
        return webJump == null ? -1 : webJump.getIndex();
    }

    int getJumpsFinished() {
        return jumpsFinished;
    }

    boolean lastJumpOk() {
        return lastJumpOk;
    }

    private final QueueJump.Clock jumpClock = new QueueJump.Clock() {
        @Override public long nowMs() {
            return SystemClock.elapsedRealtime();
        }
    };

    private final QueueJump.Effects jumpEffects = new QueueJump.Effects() {
        private int skips;

        @Override public void skip() {
            Log.i(TAG, "queue jump: skip_next " + (++skips));
            client.next();
        }

        @Override public void mute() {
            skips = 0;
            sink.setJumpMuted(true);
        }

        @Override public void unmute() {
            sink.setJumpMuted(false);
        }

        @Override public void play() {
            Log.i(TAG, "queue jump: play (the target always plays)");
            client.play();
        }

        @Override public void finished(boolean ok, String why) {
            if (ok) Log.i(TAG, "queue jump: done, " + why);
            else Log.w(TAG, "queue jump: gave up, " + why);
            jump = null;
            countJump(ok);
            main.removeCallbacks(jumpTimer);
            // The caller notifies the UI (onState, onConnected, the timer).
        }
    };

    private final Runnable jumpTimer = new Runnable() {
        @Override public void run() {
            if (jump == null) return;
            jump.onTimer();
            scheduleJumpTimer();
            notifyUi();
        }
    };

    private void scheduleJumpTimer() {
        main.removeCallbacks(jumpTimer);
        if (jump != null) main.postDelayed(jumpTimer, jump.msUntilDeadline());
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
            setStatus(getString(refresh ? R.string.status_updating : R.string.status_starting),
                    refresh ? PlayerText.Phase.UPDATING : PlayerText.Phase.STARTING, 0);
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
                setStatus(getString(R.string.status_restart_in, delayMs / 1000), PlayerText.Phase.RESTARTING,
                        (int) (delayMs / 1000));
            }
            main.removeCallbacks(timer);
            main.postDelayed(timer, delayMs);
        }

        @Override public void cancelTimer() {
            main.removeCallbacks(timer);
        }

        @Override public void failed(String reason, boolean autoRetry) {
            Log.w(TAG, "giving up: " + reason + (autoRetry ? " (will retry)" : ""));
            setStatus(getString(R.string.status_stopped, reason), PlayerText.Phase.STOPPED, 0);
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
                            postStatus(getString(R.string.status_downloading, percent),
                                    PlayerText.Phase.DOWNLOADING, percent);
                        }

                        @Override public void onExtracting() {
                            postStatus(getString(R.string.status_extracting), PlayerText.Phase.EXTRACTING, 0);
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
                    setStatus(getString(R.string.status_connecting), PlayerText.Phase.CONNECTING, 0);
                    supervisor.onStarted(gen, now);
                } else {
                    Log.w(TAG, "start failed: " + err);
                    setStatus(getString(R.string.status_start_failed, err), PlayerText.Phase.START_FAILED, 0);
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
        if (supervisor.state() == Supervisor.State.WAIT_WIFI) setStatus(getString(R.string.status_wait_wifi), PlayerText.Phase.WAIT_WIFI, 0);
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
            if (supervisor.state() == Supervisor.State.WAIT_WIFI) setStatus(getString(R.string.status_wait_wifi), PlayerText.Phase.WAIT_WIFI, 0);
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
        userInput(); // keys handled above only
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
            recheckOutputVolume(); // the output is about to change to the speaker
        }
    };

    // --- volume ---

    private int mediaIndex() {
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    private final VolumeSync.Target volumeTarget = new VolumeSync.Target() {
        @Override public int setDeviceIndex(int index) {
            Log.i(TAG, "volume: Soloist " + state.getVolume() + " -> media " + index + "/" + volumeSync.max());
            int before = mediaIndex();
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
            int now = mediaIndex();
            if (now >= index || index <= before) return now;
            // Muted (getStreamVolume reads 0): the index is stored for the
            // unmute, nothing to step. Report the 0 in effect, quietly.
            if (before == 0 || isMusicMuted()) return now;
            // Refused: Android 4.3's safe headphone volume, where the device
            // setting enables it. On this ROM it stops the headphone media
            // volume at 10 of 15 (the keys too) and no confirmation dialog
            // ever appears; with flags 0 the raise is
            // only kept pending. Go as high as allowed and show the volume
            // panel (at most every SAFE_VOLUME_UI_MS) so the cap is visible.
            for (int i = index - 1; i > now; i--) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, i, 0);
                if (mediaIndex() == i) break;
            }
            long t = SystemClock.elapsedRealtime();
            if (t - lastSafeVolumeUi >= SAFE_VOLUME_UI_MS) {
                lastSafeVolumeUi = t;
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI);
            }
            now = mediaIndex();
            Log.i(TAG, "volume: media " + index + " refused (safe volume), at " + now);
            return now;
        }

        @Override public void sendPercent(int percent) {
            Log.i(TAG, "volume: media " + mediaIndex() + "/" + volumeSync.max() + " -> Soloist " + percent);
            client.setVolume(percent);
        }
    };

    /**
     * Any STREAM_MUSIC change: keys, the system panel, other apps, and our own
     * setStreamVolume. The extras may be stale by the time this runs, so the
     * index is read back from AudioManager; VolumeSync ignores our own.
     */
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return;
            volumeSync.onDevice(mediaIndex(), SystemClock.elapsedRealtime());
        }
    };

    private boolean isMusicMuted() {
        if (isStreamMute == null) return false;
        try {
            return (Boolean) isStreamMute.invoke(audioManager, AudioManager.STREAM_MUSIC);
        } catch (Exception e) {
            return false;
        }
    }

    private final Runnable volumeRecheck = new Runnable() {
        @Override public void run() {
            volumeSync.onDevice(mediaIndex(), SystemClock.elapsedRealtime());
        }
    };

    /**
     * The output changed (or is about to): each output keeps its own media
     * index, so read it now and once more when AudioService has switched, and
     * let VolumeSync pass it on to the phone's slider.
     */
    private void recheckOutputVolume() {
        main.removeCallbacks(volumeRecheck);
        main.post(volumeRecheck);
        main.postDelayed(volumeRecheck, OUTPUT_RECHECK_MS);
    }

    /** Headphones plugged or unplugged. The sticky delivery at registration is ignored. */
    private final BroadcastReceiver headsetReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (isInitialStickyBroadcast()) return;
            Log.i(TAG, "headset " + (intent.getIntExtra("state", 0) != 0 ? "plugged" : "unplugged"));
            recheckOutputVolume();
        }
    };

    private final SoloistClient.Listener clientListener = new SoloistClient.Listener() {
        @Override public void onState(PlayerState s) {
            state = s;
            if (webJump != null) webJump.onState(s);
            if (jump != null) {
                jump.onState(s);
                scheduleJumpTimer();
            }
            if (others != null) {
                others.onState(s);
                askOthersQueue();
            }
            volumeSync.onSoloist(s.isLoggedIn(), s.getVolume(), mediaIndex(), SystemClock.elapsedRealtime());
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
                if (jump != null) jump.onDisconnected();
                volumeSync.reset();
                state = new PlayerState();
                if (webJump != null) webJump.onState(state);
                updateNotification();
                updateRemoteControl();
            }
            if (others != null) {
                others.onConnected(c, client.getState());
                askOthersQueue();
            }
            notifyUi();
        }

        @Override public void onError(String message) {
            Log.w(TAG, "Soloist: " + message);
            if (Supervisor.isAuthError(message)) {
                authError = message;
                supervisor.onAuthError();
                setStatus(getString(R.string.status_login_error, message), PlayerText.Phase.LOGIN_ERROR, 0);
            }
        }
    };

    // --- notification and remote control ---

    private void setReady() {
        setStatus(PlayerText.selectDevice(prefs.getDeviceName()), PlayerText.Phase.READY, 0);
    }

    private void postStatus(final String s, final PlayerText.Phase p, final int value) {
        main.post(new Runnable() {
            @Override public void run() {
                if (!destroyed) setStatus(s, p, value);
            }
        });
    }

    /** s for the notification (Japanese); p and value for the player's status line. */
    private void setStatus(String s, PlayerText.Phase p, int value) {
        status = s;
        phase = p;
        phaseValue = value;
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
        String title = track ? state.getTitle() : getString(R.string.app_name);
        String text = track ? TextUtils.join(", ", state.getArtists()) : status;
        // Android 4.1-4.3 shows at most three actions, and only expanded: a
        // custom row holds prev / play-pause / next (with a track) and stop.
        RemoteViews v = new RemoteViews(getPackageName(), R.layout.notification);
        v.setTextViewText(R.id.n_title, title);
        v.setTextViewText(R.id.n_text, text);
        int controls = track ? View.VISIBLE : View.GONE;
        v.setViewVisibility(R.id.n_prev, controls);
        v.setViewVisibility(R.id.n_play_pause, controls);
        v.setViewVisibility(R.id.n_next, controls);
        if (track) {
            v.setImageViewResource(R.id.n_play_pause,
                    playing ? R.drawable.ic_n_pause : R.drawable.ic_n_play);
            v.setOnClickPendingIntent(R.id.n_prev, serviceIntent(ACTION_PREV));
            v.setOnClickPendingIntent(R.id.n_play_pause, serviceIntent(ACTION_PLAY_PAUSE));
            v.setOnClickPendingIntent(R.id.n_next, serviceIntent(ACTION_NEXT));
        }
        v.setOnClickPendingIntent(R.id.n_stop, serviceIntent(ACTION_STOP));
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(title)
                .setContentText(text)
                .setContent(v)
                .setContentIntent(activityIntent())
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .build();
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
        if (webJump != null) sb.append("web jump to entry ").append(webJump.getIndex()).append(' ')
                .append(webJump.getUri()).append(", ")
                .append(SystemClock.elapsedRealtime() - webJump.getStartMs()).append(" ms\n");
        if (jump != null) sb.append("queue jump to entry ").append(jump.getIndex()).append(", ")
                .append(jump.getSteps()).append(" skips done\n");
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
