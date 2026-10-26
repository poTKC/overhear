package jp.tkc.st15iplayer.audio;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Plays the audio fakepulse streams to the abstract socket @st15i-player-audio
 * (see FrameReader for the framing and StreamSession for what each frame does)
 * through an AudioTrack.
 *
 * One thread accepts one client at a time and reads its frames. It must keep
 * reading while the stream plays: fakepulse drops the connection if a send
 * blocks for 2 s. The only thing that blocks it is AudioTrack.write, which is
 * the flow control.
 *
 * An instance is single use: start() it once, stop() it once, and create a new
 * one to listen again. A new instance retries the bind every second until the
 * previous one has let go of the socket name.
 */
public final class AudioSink {
    public static final String SOCKET_NAME = "st15i-player-audio";
    private static final String TAG = "ST15iAudio";
    private static final long BIND_RETRY_MS = 1000;
    /** Soloist runs as the shell user while it is started by hand from /data/local/tmp. */
    private static final int SHELL_UID = 2000;

    /**
     * Sink state for the service and UI. Callbacks run on the audio read
     * thread and must return quickly (post to a Handler); a slow callback
     * stalls the socket and fakepulse drops the connection after 2 s.
     */
    public interface Listener {
        void onClientConnected();

        void onClientDisconnected();

        /** True once audio is written after a start or resume; false on pause or disconnect. */
        void onPlaying(boolean playing);
    }

    private final AudioManager audioManager;
    private final Listener listener;
    private final boolean debuggable;

    private final Object lock = new Object();
    // Guarded by lock.
    private boolean started;
    private boolean running;
    private Thread thread;
    private LocalSocket client;
    private StreamSession session;

    /** Held around stopping or releasing the current AudioTrack; never during write(). */
    private final Object trackLock = new Object();
    // Guarded by trackLock.
    private AudioTrack currentTrack;

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    // Pausing Soloist on focus loss comes with the service; for
                    // now the track keeps playing so the socket stays drained.
                    Log.i(TAG, "audio focus change " + change);
                }
            };

    private final StreamSession.Focus focus = new StreamSession.Focus() {
        @Override
        public boolean request() {
            return audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }

        @Override
        public void abandon() {
            audioManager.abandonAudioFocus(focusListener);
        }
    };

    private final StreamSession.Host host = new StreamSession.Host() {
        @Override
        public StreamSession.Track createTrack(int rate, int channels) {
            return newTrack(rate, channels);
        }

        @Override
        public void onPlaying(boolean playing) {
            if (listener != null) listener.onPlaying(playing);
        }

        @Override
        public void log(String msg) {
            Log.i(TAG, msg);
        }

        @Override
        public void debug(String msg) {
            Log.d(TAG, msg);
        }

        @Override
        public long uptimeMs() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public void sleep(long ms) {
            SystemClock.sleep(ms);
        }
    };

    /** @param listener may be null */
    public AudioSink(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        this.audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        this.debuggable = (app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        this.listener = listener;
    }

    /** @throws IllegalStateException if this instance was started before */
    public void start() {
        synchronized (lock) {
            if (started) throw new IllegalStateException("AudioSink is single use");
            started = true;
            running = true;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve();
                }
            }, "ST15iAudioSink");
            thread.start();
        }
    }

    /** Closes the server socket and any client, releases the track and waits for the thread. */
    public void stop() {
        Thread t;
        synchronized (lock) {
            if (!running) return;
            running = false;
            t = thread;
            thread = null;
            if (session != null) session.close(); // stop writing before waking write()
            if (client != null) {
                try {
                    client.shutdownInput(); // wakes a blocked read; close alone may not
                } catch (IOException ignored) {
                }
            }
        }
        synchronized (trackLock) {
            if (currentTrack != null) {
                try {
                    currentTrack.stop(); // wakes a blocked write
                } catch (IllegalStateException ignored) {
                }
            }
        }
        wakeAccept();
        t.interrupt();
        try {
            t.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) Log.w(TAG, "sink thread did not stop");
    }

    private boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    /** accept() does not return when the server socket is closed, so connect to it. */
    private static void wakeAccept() {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
        } catch (IOException ignored) {
            // not listening
        } finally {
            closeQuietly(s);
        }
    }

    private void serve() {
        LocalServerSocket server = null;
        try {
            while (isRunning()) {
                if (server == null) {
                    try {
                        server = new LocalServerSocket(SOCKET_NAME);
                        Log.i(TAG, "listening on @" + SOCKET_NAME);
                    } catch (IOException e) {
                        Log.w(TAG, "bind @" + SOCKET_NAME + " failed, retrying: " + e.getMessage());
                        SystemClock.sleep(BIND_RETRY_MS);
                        continue;
                    }
                }
                LocalSocket s;
                try {
                    s = server.accept();
                } catch (IOException e) {
                    if (!isRunning()) break;
                    Log.w(TAG, "accept failed: " + e.getMessage());
                    closeQuietly(server);
                    server = null;
                    SystemClock.sleep(BIND_RETRY_MS);
                    continue;
                }
                StreamSession sess = new StreamSession(host, focus);
                synchronized (lock) {
                    if (!running) {
                        closeQuietly(s);
                        break;
                    }
                    client = s;
                    session = sess;
                }
                try {
                    if (allowed(s)) runSession(s, sess);
                } finally {
                    synchronized (lock) {
                        client = null;
                        session = null;
                        closeQuietly(s);
                    }
                }
            }
        } finally {
            if (server != null) closeQuietly(server);
            Log.i(TAG, "sink stopped");
        }
    }

    /** Only this app, and Soloist started from adb shell in a debuggable build. */
    private boolean allowed(LocalSocket s) {
        int uid;
        try {
            Credentials c = s.getPeerCredentials();
            uid = c.getUid();
        } catch (IOException e) {
            Log.w(TAG, "no peer credentials, rejecting: " + e.getMessage());
            return false;
        }
        if (uid == Process.myUid() || (debuggable && uid == SHELL_UID)) return true;
        Log.w(TAG, "rejecting client uid " + uid);
        return false;
    }

    private void runSession(LocalSocket s, StreamSession sess) {
        Log.i(TAG, "client connected");
        if (listener != null) listener.onClientConnected();
        try {
            new FrameReader(new RunningInputStream(s.getInputStream())).run(sess);
            Log.i(TAG, isRunning() ? "client closed the stream" : "stopping");
        } catch (FrameReader.ProtocolException e) {
            Log.w(TAG, "protocol error, dropping client: " + e.getMessage());
        } catch (IOException e) {
            if (isRunning()) Log.i(TAG, "client gone: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.e(TAG, "sink failed, dropping client", e);
        } finally {
            sess.finish();
            Log.i(TAG, "client disconnected");
            if (listener != null) listener.onClientDisconnected();
        }
    }

    /** Ends the stream once stop() has run, even if data is still queued in the socket. */
    private final class RunningInputStream extends FilterInputStream {
        RunningInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            return isRunning() ? super.read() : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return isRunning() ? super.read(b, off, len) : -1;
        }
    }

    /** @return a stopped track, or null if the device rejects the format */
    private StreamSession.Track newTrack(int rate, int channels) {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            Log.w(TAG, "getMinBufferSize rejected " + rate + " Hz, " + channels + " ch: " + minBuf);
            return null;
        }
        // Twice the minimum, but at least 250 ms (fakepulse reports that as
        // its latency, FAKEPULSE_LATENCY_US), in whole frames.
        int frameBytes = channels * 2;
        int size = Math.max(minBuf * 2, rate * frameBytes / 4);
        size = (size + frameBytes - 1) / frameBytes * frameBytes;
        final AudioTrack t;
        try {
            t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, mask, AudioFormat.ENCODING_PCM_16BIT, size,
                    AudioTrack.MODE_STREAM);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "AudioTrack rejected " + rate + " Hz, " + channels + " ch: " + e.getMessage());
            return null;
        }
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack init failed for " + rate + " Hz, " + channels + " ch");
            t.release();
            return null;
        }
        synchronized (trackLock) {
            currentTrack = t;
        }
        Log.i(TAG, "track created: buffer " + size + " bytes (min " + minBuf + ")");
        return new StreamSession.Track() {
            @Override
            public void play() {
                t.play();
            }

            @Override
            public void pause() {
                t.pause();
            }

            @Override
            public void flush() {
                t.flush();
            }

            @Override
            public int write(byte[] buf, int off, int len) {
                return t.write(buf, off, len); // not under trackLock: it blocks
            }

            @Override
            public long head() {
                return t.getPlaybackHeadPosition() & 0xffffffffL;
            }

            @Override
            public void release() {
                synchronized (trackLock) {
                    if (currentTrack == t) currentTrack = null;
                    try {
                        t.stop();
                    } catch (IllegalStateException ignored) {
                    }
                    t.release();
                }
            }
        };
    }

    private static void closeQuietly(LocalSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(LocalServerSocket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
