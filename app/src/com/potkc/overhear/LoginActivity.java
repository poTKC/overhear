package com.potkc.overhear;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
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
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.potkc.overhear.spotify.OAuthCallback;
import com.potkc.overhear.spotify.Pkce;
import com.potkc.overhear.spotify.SpotifyApi;

import io.nayuki.qrcodegen.QrCode;

/**
 * 「スマホで認証」, the only way to set the Soloist API key and the Spotify
 * Client ID, and to log in to Spotify: shows a QR code for
 * http://<Wi-Fi address>:8889/setup?n=<nonce> and listens there. The phone
 * gets a form (PhoneSetupPage) and posts it back (PhoneSetup checks it; a
 * blank field keeps what is stored). The save runs on the main thread
 * through Prefs.saveAccount: a new Client ID logs out
 * (SpotifyAuth.setClientId), a new key restarts Soloist through a running
 * service (with none running, setup's save installs and starts it, and this
 * screen says so). If the form asks to log in, the reply is a 303 to
 * Spotify's authorize URL (PKCE; state "<ip>:<port>~<nonce>", another nonce
 * than the QR code's); the relay page sends the browser back here
 * (/callback) with the code, which is traded for tokens through Spotify.run
 * while the phone waits, and the browser goes on to the relay's done page.
 * The QR code's nonce is spent by the first save that goes through; the
 * login it started can still come back.
 *
 * The listener runs on its own thread, one connection at a time, only while
 * this screen is open and for at most five minutes; onDestroy closes it.
 * Each connection gets five seconds in all to send its request (a form body
 * of at most 8 KB included), so a slow peer cannot hold the listener.
 * Leaving the screen during an exchange does not stop it: the login may
 * still complete, and Setup then shows it (the tokens are saved either way).
 * The key, the code, the verifier and the request line are never logged.
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

    /** How long to wait for the main thread's save. */
    private static final long SAVE_WAIT_MS = 30 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private QrView qr;
    private TextView message;
    private Button cancel;
    private Listener listener;
    /** Main thread. */
    private boolean destroyed;
    private boolean loggedIn;
    private boolean expired;
    /** A form was saved (Prefs.saveAccount). */
    private boolean saved;
    /** Nothing more to wait for: saved without a login, or logged in. */
    private boolean finished;
    /** Line added to the final message: the new key waits for setup's save to start the service; or "". */
    private String note = "";

    private final Runnable expire = new Runnable() {
        @Override public void run() {
            if (finished) return;
            expired = true;
            stopListening();
            qr.setVisibility(View.INVISIBLE);
            message.setText(saved ? R.string.login_expired_saved : R.string.login_expired);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        prefs = new Prefs(this);
        qr = (QrView) findViewById(R.id.qr);
        message = (TextView) findViewById(R.id.message);
        cancel = (Button) findViewById(R.id.cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
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
        String nonce = Pkce.nonce(random);
        String host = (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
        String state = Pkce.state(host, PORT, pkce.nonce);
        InetAddress address;
        try {
            address = InetAddress.getByAddress(ip);
        } catch (IOException e) {
            throw new AssertionError(e); // only for a wrong length
        }
        listener = new Listener(address, nonce, state, pkce);
        listener.start();
        main.postDelayed(expire, LISTEN_MS);
        qr.setCode(QrCode.encodeText("http://" + host + ":" + PORT + PhoneSetup.PATH + "?n=" + nonce,
                QrCode.Ecc.LOW));
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

    private void show(final int text) {
        show(getString(text));
    }

    /** Saved from the phone (main thread, not destroyed); login: the phone goes on to Spotify. */
    private void onSaved(boolean login) {
        saved = true;
        qr.setVisibility(View.INVISIBLE);
        if (login) message.setText(R.string.login_logging_in);
        else onFinished(getString(R.string.login_saved));
    }

    private void onLoggedIn(String id) { // main thread, not destroyed
        loggedIn = true;
        qr.setVisibility(View.INVISIBLE);
        onFinished(id.isEmpty() ? getString(R.string.login_done) : getString(R.string.login_done_as, id));
    }

    /**
     * Nothing more to wait for: the listener stops after its reply. Closes by
     * itself unless the note asks for setup's save.
     */
    private void onFinished(String text) {
        finished = true;
        main.removeCallbacks(expire);
        if (listener != null) listener.finishAfterReply();
        message.setText(note.isEmpty() ? text : text + "\n" + note);
        cancel.setText(R.string.login_close);
        if (!note.isEmpty()) return;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                finish();
            }
        }, FINISH_DELAY_MS);
    }

    /**
     * Stores the submission as setup's save did (main thread, not destroyed);
     * whether it was written. A new Client ID logs Spotify out on its thread,
     * before any login this save starts; a new key restarts a running
     * service's Soloist (as does a service that gave up).
     */
    private boolean store(PhoneSetup.Submission s) {
        boolean newKey = s.key != null && !s.key.equals(prefs.getApiKey());
        final String clientId = s.effectiveClientId;
        boolean newClient = !clientId.equals(prefs.getSpotifyClientId());
        if (!prefs.saveAccount(s.key, s.clientId)) return false;
        if (newClient) {
            Spotify.get(this).run(new Spotify.Call<Boolean>() {
                @Override public Boolean run(SpotifyApi api) {
                    return api.auth().setClientId(clientId);
                }
            }, new Spotify.Callback<Boolean>() {
                @Override public void done(Boolean loggedOut) {
                }

                @Override public void failed(IOException e) {
                }
            });
        }
        PlayerService running = PlayerService.current();
        if (running != null) {
            if (newKey || running.getSupervisorState() == Supervisor.State.FAILED) running.retry();
        } else if (s.key != null) {
            note = getString(R.string.login_note_start);
        }
        return true;
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
        /** The QR code's: every /setup request must carry it. */
        private final String nonce;
        private final String state;
        private final String verifier;
        private final String challenge;
        /** Listener thread only: a save went through (the nonce is spent), and it started a login. */
        private boolean spent;
        private boolean loginStarted;
        /** Listener thread only: the Client ID in the authorize URL, once a login started. */
        private String clientId;
        private volatile boolean stopped;
        private volatile ServerSocket server;
        /** The connection being served, closed by shutdown() to end a pending read. */
        private volatile Socket current;

        Listener(InetAddress address, String nonce, String state, Pkce pkce) {
            super("overhear-login");
            setDaemon(true);
            this.address = address;
            this.nonce = nonce;
            this.state = state;
            this.verifier = pkce.verifier;
            this.challenge = pkce.challenge;
        }

        /** Any thread; closes the port right away. */
        void shutdown() {
            stopped = true;
            closeQuietly(server);
            closeQuietly(current);
        }

        /** Any thread; the connection being served still gets its reply, then the port closes. */
        void finishAfterReply() {
            stopped = true;
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
            long length = line == null ? -1 : readHeaders(in, deadline);
            PhoneSetup.Route route = PhoneSetup.route(line, nonce, spent, loginStarted);
            Log.i(TAG, "request: " + route);
            switch (route) {
                case FORM:
                    show(R.string.login_form_opened);
                    page(client, "200 OK", PhoneSetupPage.form(nonce, prefs.hasApiKey(),
                            prefs.getSpotifyClientId(), "", true, null));
                    break;
                case SAVE:
                    save(client, in, length, deadline);
                    break;
                case USED:
                    page(client, "410 Gone", PhoneSetupPage.message(PhoneSetupPage.USED));
                    break;
                case CALLBACK:
                    callback(client, OAuthCallback.parse(line, state));
                    break;
                case FORBIDDEN:
                    respond(client, "403 Forbidden", "Forbidden");
                    break;
                case NOT_FOUND:
                    respond(client, "404 Not Found", "Not Found");
                    break;
                default:
                    respond(client, "400 Bad Request", "Bad Request");
                    break;
            }
        }

        /** POST /setup: the form again with its problem, or the save and what follows it. */
        private void save(Socket client, InputStream in, long length, long deadline) throws IOException {
            if (length == -2) {
                respond(client, "400 Bad Request", "Bad Request");
                return;
            }
            if (length < 0) {
                respond(client, "411 Length Required", "Length Required");
                return;
            }
            if (length > PhoneSetup.MAX_BODY) {
                respond(client, "413 Payload Too Large", "Payload Too Large");
                return;
            }
            byte[] raw = readBody(in, (int) length, deadline);
            String body = new String(raw, "ISO-8859-1"); // %-escaped ASCII
            Arrays.fill(raw, (byte) 0);
            PhoneSetup.Submission s = PhoneSetup.check(body, prefs.hasApiKey(), prefs.getSpotifyClientId());
            if (!s.ok()) {
                page(client, "200 OK", PhoneSetupPage.form(nonce, prefs.hasApiKey(), prefs.getSpotifyClientId(),
                        s.typedClientId, s.login, PhoneSetupPage.problem(s.problem)));
                return;
            }
            if (!storeOnMain(s)) {
                page(client, "500 Internal Server Error", PhoneSetupPage.message(PhoneSetupPage.SAVE_FAILED));
                return;
            }
            spent = true;
            if (s.login) {
                clientId = s.effectiveClientId;
                loginStarted = true;
                redirect(client, Pkce.authorizeUrl(clientId, Spotify.REDIRECT_URI, Spotify.SCOPES, challenge, state));
            } else {
                page(client, "200 OK", PhoneSetupPage.message(PhoneSetupPage.SAVED));
                stopped = true;
            }
        }

        /**
         * store() on the main thread, waiting for it; false if it failed or the
         * screen is gone or timed out by then (nothing saved).
         */
        private boolean storeOnMain(final PhoneSetup.Submission s) {
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] ok = new boolean[1]; // written before countDown(), read after await()
            main.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (destroyed || expired) return;
                        ok[0] = store(s);
                        if (ok[0]) onSaved(s.login);
                        else message.setText(R.string.login_save_failed);
                    } finally {
                        done.countDown();
                    }
                }
            });
            try {
                if (!done.await(SAVE_WAIT_MS, TimeUnit.MILLISECONDS)) return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return ok[0];
        }

        /** The relay's /callback for the login a save started. */
        private void callback(Socket client, OAuthCallback cb) throws IOException {
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
            show(R.string.login_exchanging);
            final String clientId = this.clientId;
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
     * reset the reply; at most MAX_HEADER_BYTES, within the deadline. Returns
     * the Content-Length: -1 without one, -2 if malformed or given twice
     * differently.
     */
    private static long readHeaders(InputStream in, long deadline) throws IOException {
        int left = MAX_HEADER_BYTES;
        long length = -1;
        while (left > 0) {
            String h = readLine(in, left, deadline);
            if (h == null || h.isEmpty()) return length;
            left -= h.length() + 2;
            long n = PhoneSetup.contentLength(h);
            if (n == -1) continue;
            length = n == -2 || length == -2 || (length >= 0 && length != n) ? -2 : n;
        }
        throw new IOException("headers too long");
    }

    /** Exactly length bytes of body, within the request's deadline. */
    private static byte[] readBody(InputStream in, int length, long deadline) throws IOException {
        byte[] b = new byte[length];
        for (int n = 0; n < length; ) {
            if (SystemClock.elapsedRealtime() > deadline) throw new SocketTimeoutException("request too slow");
            int r = in.read(b, n, length - n);
            if (r < 0) throw new IOException("body cut short");
            n += r;
        }
        return b;
    }

    /** A 303 to location (an https URL of ours or Spotify's), with a link in case the browser stays. */
    private static void redirect(Socket client, String location) throws IOException {
        byte[] body = ("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Overhear</title></head><body>"
                + "<a href=\"" + PhoneSetupPage.escape(location) + "\">Overhear</a></body></html>").getBytes("UTF-8");
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

    /** A page with one line of text (escaped). */
    private static void respond(Socket client, String status, String text) throws IOException {
        page(client, status, PhoneSetupPage.message(text));
    }

    private static void page(Socket client, String status, String html) throws IOException {
        byte[] body = html.getBytes("UTF-8");
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Frame-Options: DENY\r\n"
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
