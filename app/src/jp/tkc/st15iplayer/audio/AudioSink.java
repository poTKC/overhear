package jp.tkc.st15iplayer.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

/**
 * Plays the audio fakepulse streams to the abstract socket @st15i-player-audio
 * (see FrameReader for the framing) through an AudioTrack.
 *
 * One thread accepts one client at a time and reads its frames. It must keep
 * reading while the stream plays: fakepulse drops the connection if a send
 * blocks for 2 s. The only thing that blocks it is AudioTrack.write, which is
 * the flow control. Listener calls come from that thread.
 */
public final class AudioSink {
    public static final String SOCKET_NAME = "st15i-player-audio";
    private static final String TAG = "ST15iAudio";
    private static final long BIND_RETRY_MS = 1000;
    private static final long STATS_INTERVAL_MS = 5000;

    public interface Listener {
        void onClientConnected();

        void onClientDisconnected();

        void onPlaying(boolean playing);
    }

    private final AudioManager audioManager;
    private final Listener listener;

    private Thread thread;
    private volatile boolean running;
    private volatile LocalSocket client;
    private volatile AudioTrack track;

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    // Pausing Soloist on focus loss comes with the service; for
                    // now the track keeps playing so the socket stays drained.
                    Log.i(TAG, "audio focus change " + change);
                }
            };

    /** @param listener may be null */
    public AudioSink(Context context, Listener listener) {
        this.audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
    }

    public synchronized void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                serve();
            }
        }, "ST15iAudioSink");
        thread.start();
    }

    /** Closes the server socket and any client, releases the track and waits for the thread. */
    public void stop() {
        Thread t;
        synchronized (this) {
            t = thread;
            if (t == null) return;
            thread = null;
            running = false;
        }
        LocalSocket c = client;
        if (c != null) {
            try {
                c.shutdownInput(); // wakes a blocked read; close alone may not
            } catch (IOException ignored) {
            }
        }
        AudioTrack tr = track;
        if (tr != null) {
            try {
                tr.stop(); // wakes a blocked write
            } catch (IllegalStateException ignored) {
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

    /** accept() does not return when the server socket is closed, so connect to it. */
    private static void wakeAccept() {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
        } catch (IOException ignored) {
            // not listening
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void serve() {
        LocalServerSocket server = null;
        try {
            while (running) {
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
                    if (!running) break;
                    Log.w(TAG, "accept failed: " + e.getMessage());
                    closeQuietly(server);
                    server = null;
                    SystemClock.sleep(BIND_RETRY_MS);
                    continue;
                }
                if (!running) {
                    closeQuietly(s);
                    break;
                }
                client = s;
                try {
                    new Session().run(s);
                } finally {
                    client = null;
                    closeQuietly(s);
                }
            }
        } finally {
            if (server != null) closeQuietly(server);
            Log.i(TAG, "sink stopped");
        }
    }

    /** One client connection. */
    private final class Session implements FrameReader.Listener {
        private boolean corked;
        private boolean playing;
        private boolean focused;
        private int rate, channels;
        private long frames, bytes, dropped, statsAt;

        void run(LocalSocket s) {
            Log.i(TAG, "client connected");
            if (listener != null) listener.onClientConnected();
            try {
                InputStream in = s.getInputStream();
                new FrameReader(in).run(this);
                Log.i(TAG, running ? "client closed the stream" : "stopping");
            } catch (FrameReader.ProtocolException e) {
                Log.w(TAG, "protocol error, dropping client: " + e.getMessage());
            } catch (IOException e) {
                if (running) Log.i(TAG, "client gone: " + e.getMessage());
            } catch (RuntimeException e) {
                Log.e(TAG, "sink failed, dropping client", e);
            } finally {
                logStats("final");
                releaseTrack();
                setPlaying(false);
                abandonFocus();
                Log.i(TAG, "client disconnected");
                if (listener != null) listener.onClientDisconnected();
            }
        }

        @Override
        public void header(int rate, int channels) {
            Log.i(TAG, "H rate " + rate + " channels " + channels);
            if (track != null && rate == this.rate && channels == this.channels) return;
            releaseTrack();
            setPlaying(false);
            if (channels != 1 && channels != 2) throw new IllegalArgumentException("unsupported channels " + channels);
            int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) throw new IllegalArgumentException("unsupported rate " + rate + " (" + minBuf + ")");
            // About FAKEPULSE_LATENCY_US (250 ms) of audio, whole frames.
            int frameBytes = channels * 2;
            int size = Math.max(minBuf * 2, rate * frameBytes / 4);
            size = (size + frameBytes - 1) / frameBytes * frameBytes;
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, mask, AudioFormat.ENCODING_PCM_16BIT,
                    size, AudioTrack.MODE_STREAM);
            if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                t.release();
                throw new IllegalStateException("AudioTrack init failed");
            }
            Log.i(TAG, "track created: buffer " + size + " bytes (min " + minBuf + ")");
            this.rate = rate;
            this.channels = channels;
            track = t;
            if (!corked) startPlaying();
        }

        @Override
        public void pcm(byte[] buf, int off, int len) {
            AudioTrack t = track;
            if (t == null || corked) {
                // Never block the reader on a paused track; fakepulse sends no
                // audio while corked, and none before 'H'.
                if (dropped++ == 0) Log.w(TAG, "dropping PCM " + (t == null ? "before header" : "while corked"));
                return;
            }
            while (len > 0 && running) {
                int w = t.write(buf, off, len);
                if (w < 0) throw new IllegalStateException("AudioTrack.write " + w);
                if (w == 0) break; // stopped
                off += w;
                len -= w;
                bytes += w;
            }
            frames++;
            long now = SystemClock.elapsedRealtime();
            if (statsAt == 0) statsAt = now;
            if (now - statsAt >= STATS_INTERVAL_MS) {
                statsAt = now;
                logStats("stats");
            }
        }

        @Override
        public void flush() {
            Log.i(TAG, "F flush");
            AudioTrack t = track;
            if (t == null) return;
            t.pause();
            t.flush();
            if (playing) t.play();
        }

        @Override
        public void cork(boolean c) {
            Log.i(TAG, "C " + (c ? "1 pause" : "0 resume"));
            corked = c;
            if (track == null) return;
            if (c) {
                track.pause();
                setPlaying(false);
                abandonFocus();
            } else {
                startPlaying();
            }
        }

        private void startPlaying() {
            if (!focused) {
                int r = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
                focused = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                Log.i(TAG, "audio focus " + (focused ? "granted" : "denied"));
            }
            track.play();
            setPlaying(true);
        }

        private void abandonFocus() {
            if (!focused) return;
            audioManager.abandonAudioFocus(focusListener);
            focused = false;
        }

        private void setPlaying(boolean p) {
            if (playing == p) return;
            playing = p;
            if (listener != null) listener.onPlaying(p);
        }

        private void releaseTrack() {
            AudioTrack t = track;
            if (t == null) return;
            track = null;
            try {
                t.stop();
            } catch (IllegalStateException ignored) {
            }
            t.release();
        }

        private void logStats(String what) {
            AudioTrack t = track;
            long head = t != null ? t.getPlaybackHeadPosition() & 0xffffffffL : -1;
            double secs = rate > 0 && channels > 0 ? bytes / (double) (rate * channels * 2) : 0;
            Log.i(TAG, String.format("%s: %d P frames, %d bytes (%.1f s), head %d, dropped %d",
                    what, frames, bytes, secs, head, dropped));
        }
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
