package com.potkc.overhear;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import com.potkc.overhear.runtime.RuntimeInstaller;
import com.potkc.overhear.soloist.MainThreadExecutor;
import com.potkc.overhear.spotify.HelperTransport;
import com.potkc.overhear.spotify.SpotifyApi;
import com.potkc.overhear.spotify.SpotifyAuth;

/**
 * The app's Spotify Web API access: one SpotifyApi over HelperTransport,
 * the login in Prefs, and a single background thread for calls, whose
 * results come back on the main thread. Calls run one at a time in the
 * order given. get() and run() may be called from any thread.
 */
final class Spotify {
    static final String CLIENT_ID = "efc26a16958343d0babe0e67afaa1a18";
    static final String REDIRECT_URI = "https://potkc.github.io/overhear-auth/callback/";
    static final String SCOPES = "user-read-playback-state user-modify-playback-state user-library-read "
            + "playlist-read-private user-follow-read user-read-recently-played";

    /** Runs on the main thread. */
    interface Callback<T> {
        void done(T result);

        void failed(IOException e);
    }

    /** Runs on the Spotify thread; may block. */
    interface Call<T> {
        T run(SpotifyApi api) throws IOException;
    }

    private static final String TAG = "OverhearSpotify";
    private static Spotify instance;

    private final SpotifyAuth auth;
    private final SpotifyApi api;
    private final ExecutorService thread;
    private final MainThreadExecutor main = new MainThreadExecutor();

    private Spotify(Context app) {
        final Prefs prefs = new Prefs(app);
        // SharedPreferences is thread-safe, as SpotifyAuth needs.
        SpotifyAuth.TokenStore store = new SpotifyAuth.TokenStore() {
            @Override public String refreshToken() {
                return prefs.getSpotifyRefresh();
            }

            @Override public void saveRefreshToken(String token) {
                prefs.setSpotifyRefresh(token);
            }

            @Override public void clear() {
                prefs.clearSpotifyRefresh();
            }
        };
        SpotifyAuth.Clock clock = new SpotifyAuth.Clock() {
            @Override public long nowMs() {
                return System.currentTimeMillis();
            }
        };
        HelperTransport transport = new HelperTransport(new RuntimeInstaller(app));
        auth = new SpotifyAuth(transport, store, clock, CLIENT_ID);
        api = new SpotifyApi(transport, auth);
        thread = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "spotify");
                t.setDaemon(true);
                return t;
            }
        });
    }

    static synchronized Spotify get(Context c) {
        if (instance == null) instance = new Spotify(c.getApplicationContext());
        return instance;
    }

    /** Blocking calls; use them on the Spotify thread (through run) only. */
    SpotifyApi api() {
        return api;
    }

    /** Safe on the main thread. */
    boolean isLoggedIn() {
        return auth.isLoggedIn();
    }

    /** Runs call on the Spotify thread; the callback runs on the main thread. */
    <T> void run(final Call<T> call, final Callback<T> callback) {
        thread.execute(new Runnable() {
            @Override public void run() {
                T result;
                try {
                    result = call.run(api);
                } catch (IOException e) {
                    fail(callback, e);
                    return;
                } catch (RuntimeException e) {
                    // A bug, but the caller is still waiting for an answer.
                    Log.w(TAG, "Spotify call failed", e);
                    fail(callback, new IOException(e.toString()));
                    return;
                }
                final T r = result;
                main.execute(new Runnable() {
                    @Override public void run() {
                        callback.done(r);
                    }
                });
            }
        });
    }

    private <T> void fail(final Callback<T> callback, final IOException e) {
        main.execute(new Runnable() {
            @Override public void run() {
                callback.failed(e);
            }
        });
    }
}
