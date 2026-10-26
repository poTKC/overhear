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
 * While started, a connection thread (re)connects, backing off 1 s, 2 s,
 * then every 5 s while attempts fail, and starting over at 1 s after a
 * connection opened. Each connection starts from an empty state (listeners
 * get it right after onConnected(true)) and requests get_state and
 * get_queue; the queue is requested again whenever the track or context
 * changes. Every opened connection is reported closed exactly once, by its
 * own close or by stop(); callbacks of a connection that is no longer
 * current are ignored, so stop() then start() never sees stale events.
 *
 * Commands are sent in order on a sender thread, so they may be called from
 * the main thread; they are dropped while disconnected. stop() does not
 * block or touch the network on the calling thread.
 *
 * Listeners run on the callbacks executor (on Android, MainThreadExecutor)
 * and receive immutable snapshots. Pure Java so it runs in the host tests.
 */
public final class SoloistClient {
    public interface Listener {
        void onState(PlayerState state);

        void onConnected(boolean connected);

        /** An error event from Soloist (e.g. a rejected command) or a failure handling one. */
        void onError(String message);
    }

    /** Receives connect and disconnect transitions (an android.util.Log adapter on the device). */
    public interface Logger {
        void log(String message);
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
    /** The connection being made or open; null while waiting to retry or stopped. */
    private Connection current; // guarded by lock
    /** Failed attempts since the last open, indexes backoffMs. */
    private int failures; // guarded by lock
    private volatile boolean connected;
    private volatile long[] backoffMs = {1000, 2000, 5000};
    private volatile Logger logger = new Logger() {
        @Override public void log(String message) {}
    };

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

    /** Delays before successive reconnect attempts; the last one repeats. */
    public void setBackoffMs(long... delays) {
        if (delays.length == 0) throw new IllegalArgumentException("no delays");
        backoffMs = delays.clone();
    }

    public void setLogger(Logger l) {
        logger = l;
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
            failures = 0;
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
        final Connection c;
        synchronized (lock) {
            if (!running) return;
            running = false;
            l = loop;
            loop = null;
            sender.shutdown();
            sender = null;
            c = current;
            current = null; // its callbacks are ignored from now on
            if (c != null && c.markClosed()) disconnected("stopped");
        }
        l.interrupt();
        if (c != null) {
            // close() writes a close frame: not allowed on Android's main thread.
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    c.ws.close();
                }
            }, "soloist-stop");
            t.setDaemon(true);
            t.start();
        }
    }

    /** False once stop() was called, even if start() made a new loop since. */
    private boolean isCurrentLoop() {
        synchronized (lock) {
            return running && loop == Thread.currentThread();
        }
    }

    private void connectLoop() {
        while (true) {
            Connection c;
            synchronized (lock) {
                if (!running || loop != Thread.currentThread()) return;
                c = new Connection();
                current = c; // published before connect() so stop() can abort it
            }
            try {
                c.ws.connect(CONNECT_TIMEOUT_MS);
                c.awaitClosed();
            } catch (IOException e) {
                // Soloist not up (yet); retry below.
            } catch (InterruptedException e) {
                c.ws.close();
                return;
            } finally {
                synchronized (lock) {
                    if (current == c) current = null;
                }
                if (!isCurrentLoop()) c.ws.close(); // idempotent
            }
            long delay;
            synchronized (lock) {
                long[] b = backoffMs;
                delay = b[Math.min(failures, b.length - 1)];
                failures++;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One connection attempt; its callbacks only act while it is current. */
    private final class Connection implements WebSocketClient.Listener {
        final WebSocketClient ws = new WebSocketClient(host, port, "/", this);
        private boolean opened; // guarded by lock
        private boolean closedReported; // guarded by lock
        private boolean ended; // guarded by this

        /** Marks an opened connection closed; true if the caller must report it. */
        boolean markClosed() { // under lock
            if (!opened || closedReported) return false;
            closedReported = true;
            return true;
        }

        @Override public void onOpen() {
            synchronized (lock) {
                if (current != this) return;
                opened = true;
                failures = 0;
                state = new PlayerState();
                connected = true;
                logger.log("connected to " + host + ":" + port);
                notifyConnected(true);
                notifyState(state.copy());
            }
            command(cmd("get_state"));
            refreshQueue();
        }

        @Override public void onMessage(String text) {
            try {
                handle(this, text);
            } catch (RuntimeException e) {
                notifyError("failed to handle an event: " + e);
            }
        }

        @Override public void onClose(int code, String reason) {
            try {
                synchronized (lock) {
                    if (current == this && markClosed()) {
                        disconnected(code + (reason.isEmpty() ? "" : " " + reason));
                    }
                }
            } finally {
                // Even if a listener threw, or the connect thread waits forever.
                synchronized (this) {
                    ended = true;
                    notifyAll();
                }
            }
        }

        synchronized void awaitClosed() throws InterruptedException {
            while (!ended) wait();
        }
    }

    private void disconnected(String why) { // under lock
        connected = false;
        logger.log("disconnected (" + why + ")");
        notifyConnected(false);
    }

    private void handle(Connection c, String text) {
        JSONObject e;
        try {
            e = new JSONObject(text);
        } catch (JSONException ex) {
            return;
        }
        String type = PlayerState.str(e, "type");
        synchronized (lock) {
            if (current != c) return;
            if ("error".equals(type)) notifyError(PlayerState.str(e, "message"));
            else if (state.apply(e)) notifyState(state.copy());
        }
        if ("track_changed".equals(type) || "context_changed".equals(type)) refreshQueue();
    }

    // Notifications are posted under lock, so they reach the executor in
    // order and none from a stale connection can follow a newer one.

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
                    Connection conn;
                    synchronized (lock) {
                        conn = current;
                    }
                    if (conn == null || !conn.ws.isOpen()) return;
                    try {
                        conn.ws.send(c.toString());
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
