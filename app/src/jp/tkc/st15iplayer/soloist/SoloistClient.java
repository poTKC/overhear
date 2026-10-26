package jp.tkc.st15iplayer.soloist;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

import jp.tkc.st15iplayer.ws.WebSocketClient;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Keeps a WebSocket connection to Soloist's local API, folds its events into
 * a PlayerState and sends playback commands.
 *
 * A connection thread (re)connects every reconnect delay (1 s) while
 * started. On each connection the state starts empty and get_state and
 * get_queue are requested; the queue is requested again whenever the track
 * or context changes. Commands are sent in order on a sender thread, so they
 * may be called from the main thread; they are dropped while disconnected.
 *
 * Listeners run on the callbacks executor (on Android, MainThreadExecutor)
 * and receive immutable snapshots. Pure Java so it runs in the host tests.
 */
public final class SoloistClient {
    public interface Listener {
        void onState(PlayerState state);

        void onConnected(boolean connected);

        /** An error event from Soloist, e.g. a rejected command. */
        void onError(String message);
    }

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8765;
    public static final int QUEUE_LIMIT = 10;
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private final String host;
    private final int port;
    private final Executor callbacks;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final Object lock = new Object();

    private PlayerState state = new PlayerState(); // guarded by lock
    private boolean running; // guarded by lock
    private Thread loop; // guarded by lock
    private ExecutorService sender; // guarded by lock
    private volatile WebSocketClient ws;
    private volatile boolean connected;
    private volatile long reconnectDelayMs = 1000;

    public SoloistClient(Executor callbacks) {
        this(DEFAULT_HOST, DEFAULT_PORT, callbacks);
    }

    public SoloistClient(int port, Executor callbacks) {
        this(DEFAULT_HOST, port, callbacks);
    }

    public SoloistClient(String host, int port, Executor callbacks) {
        this.host = host;
        this.port = port;
        this.callbacks = callbacks;
    }

    public void setReconnectDelayMs(long ms) {
        reconnectDelayMs = ms;
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean isConnected() {
        return connected;
    }

    /** A snapshot of the current state. */
    public PlayerState getState() {
        synchronized (lock) {
            return state.copy();
        }
    }

    public void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            sender = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "soloist-send");
                    t.setDaemon(true);
                    return t;
                }
            });
            loop = new Thread(new Runnable() {
                @Override public void run() {
                    connectLoop();
                }
            }, "soloist-connect");
            loop.setDaemon(true);
            loop.start();
        }
    }

    /** Closes the connection and stops reconnecting. Does not block. */
    public void stop() {
        Thread l;
        synchronized (lock) {
            if (!running) return;
            running = false;
            l = loop;
            loop = null;
            sender.shutdown();
            sender = null;
        }
        l.interrupt();
        WebSocketClient w = ws;
        if (w != null) w.close();
    }

    /** False once stop() was called, even if start() made a new loop since. */
    private boolean isCurrentLoop() {
        synchronized (lock) {
            return running && loop == Thread.currentThread();
        }
    }

    private void connectLoop() {
        while (isCurrentLoop()) {
            final Object done = new Object();
            final boolean[] closed = {false};
            WebSocketClient w = new WebSocketClient(host, port, "/", new WebSocketClient.Listener() {
                @Override public void onOpen() {
                    synchronized (lock) {
                        state = new PlayerState();
                    }
                    connected = true;
                    notifyConnected(true);
                    command(cmd("get_state"));
                    refreshQueue();
                }

                @Override public void onMessage(String text) {
                    handle(text);
                }

                @Override public void onClose(int code, String reason) {
                    connected = false;
                    notifyConnected(false);
                    synchronized (done) {
                        closed[0] = true;
                        done.notifyAll();
                    }
                }
            });
            synchronized (lock) {
                if (!running || loop != Thread.currentThread()) return;
                ws = w; // published before connect() so stop() can abort it
            }
            try {
                w.connect(CONNECT_TIMEOUT_MS);
                synchronized (done) {
                    while (!closed[0]) done.wait();
                }
            } catch (IOException e) {
                // Soloist not up (yet); retry below.
            } catch (InterruptedException e) {
                return; // stop()
            } finally {
                ws = null;
            }
            try {
                Thread.sleep(reconnectDelayMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void handle(String text) {
        JSONObject e;
        try {
            e = new JSONObject(text);
        } catch (JSONException ex) {
            return;
        }
        String type = PlayerState.str(e, "type");
        if ("error".equals(type)) {
            notifyError(PlayerState.str(e, "message"));
            return;
        }
        PlayerState snapshot = null;
        synchronized (lock) {
            if (state.apply(e)) snapshot = state.copy();
        }
        if (snapshot != null) notifyState(snapshot);
        if ("track_changed".equals(type) || "context_changed".equals(type)) refreshQueue();
    }

    private void notifyState(final PlayerState s) {
        for (final Listener l : listeners) {
            callbacks.execute(new Runnable() {
                @Override public void run() {
                    l.onState(s);
                }
            });
        }
    }

    private void notifyConnected(final boolean c) {
        for (final Listener l : listeners) {
            callbacks.execute(new Runnable() {
                @Override public void run() {
                    l.onConnected(c);
                }
            });
        }
    }

    private void notifyError(final String message) {
        for (final Listener l : listeners) {
            callbacks.execute(new Runnable() {
                @Override public void run() {
                    l.onError(message);
                }
            });
        }
    }

    // --- commands ---

    public void play() {
        command(cmd("play"));
    }

    /** Plays a track or context URI. */
    public void play(String uri) {
        command(put(cmd("play"), "uri", uri));
    }

    public void pause() {
        command(cmd("pause"));
    }

    public void next() {
        command(cmd("skip_next"));
    }

    public void prev() {
        command(cmd("skip_prev"));
    }

    public void seek(long positionMs) {
        command(put(cmd("seek"), "position_ms", Math.max(0, positionMs)));
    }

    /** 0..100, clamped. */
    public void setVolume(int volume) {
        command(put(cmd("set_volume"), "volume", Math.max(0, Math.min(100, volume))));
    }

    public void setShuffle(boolean enabled) {
        command(put(cmd("set_shuffle"), "enabled", enabled));
    }

    /**
     * Soloist has separate track and context repeat flags; the one being
     * turned off goes first so both are never on together.
     */
    public void setRepeat(PlayerState.Repeat repeat) {
        switch (repeat) {
            case TRACK:
                command(put(cmd("set_repeat_context"), "enabled", false));
                command(put(cmd("set_repeat_track"), "enabled", true));
                break;
            case CONTEXT:
                command(put(cmd("set_repeat_track"), "enabled", false));
                command(put(cmd("set_repeat_context"), "enabled", true));
                break;
            default:
                command(put(cmd("set_repeat_track"), "enabled", false));
                command(put(cmd("set_repeat_context"), "enabled", false));
                break;
        }
    }

    /** Makes this device the active Spotify Connect device. */
    public void activate() {
        command(cmd("activate"));
    }

    public void refreshState() {
        command(cmd("get_state"));
    }

    public void refreshQueue() {
        command(put(cmd("get_queue"), "limit", QUEUE_LIMIT));
    }

    private void command(final JSONObject c) {
        ExecutorService s;
        synchronized (lock) {
            s = sender;
        }
        if (s == null) return;
        try {
            s.execute(new Runnable() {
                @Override public void run() {
                    WebSocketClient w = ws;
                    if (w == null || !w.isOpen()) return;
                    try {
                        w.send(c.toString());
                    } catch (IOException e) {
                        // The reader sees the broken connection and reconnects.
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // stopped
        }
    }

    // org.json's put throws the checked JSONException on Android.

    private static JSONObject cmd(String name) {
        return put(put(new JSONObject(), "type", "command"), "command", name);
    }

    private static JSONObject put(JSONObject o, String key, Object value) {
        try {
            return o.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
