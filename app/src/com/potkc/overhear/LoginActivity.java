package com.potkc.overhear;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.potkc.overhear.spotify.OAuthCallback;
import com.potkc.overhear.spotify.Pkce;
import com.potkc.overhear.spotify.SpotifyApi;

import io.nayuki.qrcodegen.QrCode;

/**
 * Logs in to Spotify from a phone: shows the authorize URL as a QR code and
 * listens on the Wi-Fi address, port 8889, for the browser that the relay
 * page sends back with the code (state "<ip>:<port>~<nonce>"). The code is
 * traded for tokens through Spotify.run while the phone waits for the page
 * that tells it how it went.
 *
 * The listener runs on its own thread, one connection at a time, only while
 * this screen is open and for at most five minutes; onDestroy closes it.
 * Each connection gets five seconds in all to send its request line and
 * headers, so a slow peer cannot hold the listener. Leaving the screen
 * during an exchange does not stop it: the login may still complete, and
 * Setup then shows it (the tokens are saved either way).
 * The code, the verifier and the request line are never logged.
 */
public class LoginActivity extends Activity {
    static final int PORT = 8889;

    private static final String TAG = "OverhearLogin";
    private static final long LISTEN_MS = 5 * 60 * 1000L;
    private static final long FINISH_DELAY_MS = 2000;
    /** Longer than an exchange plus me() on the Spotify thread (40 s each at most). */
    private static final long EXCHANGE_WAIT_MS = 120 * 1000L;
    /** Per read; the whole request must arrive within REQUEST_MS of accept. */
    private static final int READ_TIMEOUT_MS = 2000;
    private static final long REQUEST_MS = 5000;
    private static final int MAX_LINE = 8 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    /** The relay page's "logged in" view, where a successful callback sends the browser on. */
    private static final String DONE_URL = Spotify.REDIRECT_URI + "?done=1";

    private final Handler main = new Handler(Looper.getMainLooper());
    private QrView qr;
    private TextView message;
    private Listener listener;
    /** Main thread. */
    private boolean destroyed;
    private boolean loggedIn;
    private boolean expired;

    private final Runnable expire = new Runnable() {
        @Override public void run() {
            if (loggedIn) return;
            expired = true;
            stopListening();
            qr.setVisibility(View.INVISIBLE);
            message.setText(R.string.login_expired);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        qr = (QrView) findViewById(R.id.qr);
        message = (TextView) findViewById(R.id.message);
        findViewById(R.id.cancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        });

        byte[] ip = wifiAddress(this);
        if (ip == null) {
            message.setText(R.string.login_need_wifi);
            return;
        }
        SecureRandom random = seededRandom();
        if (random == null) {
            message.setText(R.string.login_no_random);
            return;
        }
        Pkce pkce = Pkce.create(random);
        String host = (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
        String state = Pkce.state(host, PORT, pkce.nonce);
        InetAddress address;
        try {
            address = InetAddress.getByAddress(ip);
        } catch (IOException e) {
            throw new AssertionError(e); // only for a wrong length
        }
        // As saved: setup's SpotifyAuth.setClientId may still wait behind a call,
        // but runs before this login's exchange, which checks the ID again.
        String clientId = new Prefs(this).getSpotifyClientId();
        listener = new Listener(address, state, pkce.verifier, clientId);
        listener.start();
        main.postDelayed(expire, LISTEN_MS);
        qr.setCode(QrCode.encodeText(Pkce.authorizeUrl(clientId, Spotify.REDIRECT_URI, Spotify.SCOPES,
                pkce.challenge, state), QrCode.Ecc.LOW));
        message.setText(R.string.login_waiting);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        stopListening();
        super.onDestroy();
    }

    private void stopListening() {
        if (listener != null) listener.shutdown();
    }

    /** Runs r on the main thread unless the screen is gone by then. */
    private void ui(final Runnable r) {
        main.post(new Runnable() {
            @Override public void run() {
                if (!destroyed) r.run();
            }
        });
    }

    private void show(final String text) {
        ui(new Runnable() {
            @Override public void run() {
                message.setText(text);
            }
        });
    }

    private void onLoggedIn(String id) { // main thread, not destroyed
        loggedIn = true;
        main.removeCallbacks(expire);
        stopListening();
        qr.setVisibility(View.INVISIBLE);
        message.setText(id.isEmpty() ? getString(R.string.login_done) : getString(R.string.login_done_as, id));
        main.postDelayed(new Runnable() {
            @Override public void run() {
                finish();
            }
        }, FINISH_DELAY_MS);
    }

    /** The Wi-Fi IPv4 address in network order, or null when not connected. */
    private static byte[] wifiAddress(Context c) {
        WifiManager wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
        int ip = info == null ? 0 : info.getIpAddress();
        if (ip == 0) return null;
        // getIpAddress() puts the first octet in the low byte.
        return new byte[] {(byte) ip, (byte) (ip >> 8), (byte) (ip >> 16), (byte) (ip >>> 24)};
    }

    /**
     * A SecureRandom seeded from /dev/urandom, or null if it cannot be read:
     * on Android 4.1 to 4.3 an app forked from Zygote may otherwise get a
     * weakly seeded one, and this ROM may lack the fix.
     */
    private static SecureRandom seededRandom() {
        byte[] seed = new byte[64];
        FileInputStream in = null;
        try {
            in = new FileInputStream("/dev/urandom");
            for (int n = 0; n < seed.length; ) {
                int r = in.read(seed, n, seed.length - n);
                if (r < 0) return null;
                n += r;
            }
        } catch (IOException e) {
            Log.w(TAG, "cannot read /dev/urandom: " + e);
            return null;
        } finally {
            closeQuietly(in);
        }
        SecureRandom random = new SecureRandom();
        random.setSeed(seed);
        return random;
    }

    /** The accept loop. */
    private final class Listener extends Thread {
        private final InetAddress address;
        private final String state;
        private final String verifier;
        /** The Client ID in the authorize URL. */
        private final String clientId;
        private volatile boolean stopped;
        private volatile ServerSocket server;
        /** The connection being served, closed by shutdown() to end a pending read. */
        private volatile Socket current;

        Listener(InetAddress address, String state, String verifier, String clientId) {
            super("overhear-login");
            setDaemon(true);
            this.address = address;
            this.state = state;
            this.verifier = verifier;
            this.clientId = clientId;
        }

        /** Any thread; closes the port right away. */
        void shutdown() {
            stopped = true;
            closeQuietly(server);
            closeQuietly(current);
        }

        @Override public void run() {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(address, PORT));
                ss.setSoTimeout(1000);
            } catch (IOException e) {
                closeQuietly(ss);
                Log.w(TAG, "cannot listen: " + e);
                final String why = String.valueOf(e.getMessage());
                ui(new Runnable() {
                    @Override public void run() {
                        main.removeCallbacks(expire);
                        qr.setVisibility(View.INVISIBLE);
                        message.setText(getString(R.string.login_listen_failed, why));
                    }
                });
                return;
            }
            server = ss;
            long deadline = SystemClock.elapsedRealtime() + LISTEN_MS;
            try {
                while (!stopped && SystemClock.elapsedRealtime() < deadline) {
                    Socket client;
                    try {
                        client = ss.accept();
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (IOException e) {
                        break; // closed by shutdown()
                    }
                    current = client;
                    try {
                        if (stopped) continue; // shutdown() may have missed it
                        client.setSoTimeout(READ_TIMEOUT_MS);
                        serve(client);
                    } catch (IOException e) {
                        Log.w(TAG, "connection failed: " + e.getClass().getSimpleName());
                    } finally {
                        current = null;
                        closeQuietly(client);
                    }
                }
            } finally {
                closeQuietly(ss);
            }
            // Timed out here rather than stopped: the main thread's timer may
            // have been held up while the device slept.
            if (!stopped) ui(expire);
        }

        private void serve(Socket client) throws IOException {
            InputStream in = new BufferedInputStream(client.getInputStream());
            long deadline = SystemClock.elapsedRealtime() + REQUEST_MS;
            String line = readLine(in, MAX_LINE, deadline);
            OAuthCallback cb = OAuthCallback.parse(line, state);
            if (line != null) skipHeaders(in, deadline);
            Log.i(TAG, "request: " + cb.kind);
            switch (cb.kind) {
                case ERROR:
                    ui(new Runnable() {
                        @Override public void run() {
                            if (!loggedIn && !expired) message.setText(R.string.login_denied);
                        }
                    });
                    respond(client, "200 OK", getString(R.string.login_html_denied));
                    break;
                case CODE:
                    boolean ok = exchange(cb.value);
                    if (ok) {
                        // To the relay's own done page: the browser may load this URL again
                        // (a tab restored from Spotify's app, say) once nothing listens here.
                        redirect(client, DONE_URL);
                        stopped = true;
                    } else {
                        respond(client, "200 OK", getString(R.string.login_html_failed));
                    }
                    break;
                default:
                    respond(client, "400 Bad Request", "Bad Request");
                    break;
            }
        }

        /** Trades the code for tokens on the Spotify thread and waits for the outcome. */
        private boolean exchange(final String code) {
            show(getString(R.string.login_exchanging));
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] ok = new boolean[1]; // written before countDown(), read after await()
            Spotify.get(LoginActivity.this).run(new Spotify.Call<String>() {
                @Override public String run(SpotifyApi api) throws IOException {
                    api.auth().exchangeCode(code, verifier, Spotify.REDIRECT_URI, clientId);
                    try {
                        return api.me();
                    } catch (IOException e) {
                        return ""; // logged in all the same
                    }
                }
            }, new Spotify.Callback<String>() {
                @Override public void done(String id) {
                    ok[0] = true;
                    done.countDown();
                    // A new login (perhaps another account): the library starts from its top.
                    new Prefs(getApplicationContext()).setLibraryPath("");
                    if (!destroyed) onLoggedIn(id);
                }

                @Override public void failed(IOException e) {
                    done.countDown();
                    Log.w(TAG, "login failed: " + e.getClass().getSimpleName());
                    if (!destroyed && !loggedIn && !expired) message.setText(R.string.login_failed);
                }
            });
            try {
                if (!done.await(EXCHANGE_WAIT_MS, TimeUnit.MILLISECONDS)) return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return ok[0];
        }
    }

    /**
     * One line without its line ending (ISO-8859-1: request lines are ASCII),
     * or null at the end of the stream or past max bytes. Throws once the
     * request's deadline (elapsedRealtime) has passed.
     */
    private static String readLine(InputStream in, int max, long deadline) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (;;) {
            if (SystemClock.elapsedRealtime() > deadline) throw new SocketTimeoutException("request too slow");
            int b = in.read();
            if (b < 0) return null;
            if (b == '\n') break;
            if (sb.length() >= max) return null;
            sb.append((char) b);
        }
        int n = sb.length();
        if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
        return sb.toString();
    }

    /**
     * Reads up to the blank line ending the headers, so closing does not
     * reset the reply; at most MAX_HEADER_BYTES, within the deadline.
     */
    private static void skipHeaders(InputStream in, long deadline) throws IOException {
        int left = MAX_HEADER_BYTES;
        while (left > 0) {
            String h = readLine(in, left, deadline);
            if (h == null || h.isEmpty()) return;
            left -= h.length() + 2;
        }
        throw new IOException("headers too long");
    }

    /** A 303 to location (a constant https URL), with a link in case the browser stays. */
    private static void redirect(Socket client, String location) throws IOException {
        byte[] body = ("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Overhear</title></head><body>"
                + "<a href=\"" + TextUtils.htmlEncode(location) + "\">Overhear</a></body></html>").getBytes("UTF-8");
        String head = "HTTP/1.1 303 See Other\r\n"
                + "Location: " + location + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }

    private static void respond(Socket client, String status, String text) throws IOException {
        String html = "<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Overhear</title></head><body style=\"font-family:sans-serif;padding:1em\"><p>"
                + TextUtils.htmlEncode(text) + "</p></body></html>";
        byte[] body = html.getBytes("UTF-8");
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    // Socket and ServerSocket only implement Closeable from API 19.
    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
