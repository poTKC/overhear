package com.potkc.overhear;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

import com.potkc.overhear.spotify.Item;
import com.potkc.overhear.spotify.LikedTracks;
import com.potkc.overhear.spotify.SpotifyApi;
import com.potkc.overhear.spotify.SpotifyException;

/**
 * Liked Songs for the player's ⋮ menu and the library's long-press: what the
 * session knows (LikedTracks, shared by both screens), the background check
 * of a track, and saving or removing one with a toast. A login without
 * user-library-modify (from before it was asked for) gets Reason.SCOPE: a
 * dialog then offers to log in again. A check is shared by whoever asks
 * meanwhile (LikedTracks.ask), and backs off after a failure; one save or
 * remove per track at a time. Main thread only.
 */
final class Likes {
    private static final String TAG = "OverhearLikes";
    private static final LikedTracks known = new LikedTracks();

    private Likes() {}

    /** Whether uri is liked, or null while unknown (or not a track). */
    static Boolean get(Spotify spotify, String uri) {
        known.login(spotify.generation());
        return known.get(uri);
    }

    /** Whether uri is being checked, saved or removed: its menu item waits (disabled). */
    static boolean busy(Spotify spotify, String uri) {
        known.login(spotify.generation());
        return known.busy(uri);
    }

    /** The tracks of a page of the liked songs fetched under login generation: all liked. */
    static void putLiked(Spotify spotify, int generation, List<Item> items) {
        if (generation != spotify.generation()) return;
        known.login(generation);
        known.putAll(items, true);
    }

    /**
     * Checks uri in the background unless it is known, not a track, nobody
     * is logged in, or its last check failed within LikedTracks.RETRY_MS;
     * when a check runs (this one or one already running), whenDone (or
     * null) runs once it answers or fails (main thread, even after the
     * caller is gone: guard it).
     */
    static void check(final Spotify spotify, final String uri, Runnable whenDone) {
        if (!spotify.isLoggedIn()) return;
        final int generation = spotify.generation();
        known.login(generation);
        if (!known.ask(uri, SystemClock.elapsedRealtime(), whenDone)) return;
        spotify.run(new Spotify.Call<Boolean>() {
            @Override public Boolean run(SpotifyApi api) throws IOException {
                return api.isTrackSaved(uri);
            }
        }, new Spotify.Callback<Boolean>() {
            @Override public void done(Boolean liked) {
                if (spotify.generation() != generation) return; // forgotten with the waiters
                Log.i(TAG, uri + (liked ? " is" : " is not") + " liked");
                runAll(known.answered(uri, liked));
            }

            @Override public void failed(IOException e) {
                Log.w(TAG, "contains failed: " + e.getMessage());
                // The rows re-enable as 「お気に入りに追加」: a tap saves, and says if that fails.
                if (spotify.generation() == generation) runAll(known.failed(uri, SystemClock.elapsedRealtime()));
            }
        });
    }

    private static void runAll(List<Runnable> rs) {
        for (Runnable r : rs) r.run();
    }

    /** The menu label for a track liked (or not, or unknown: null). */
    static int label(Boolean liked) {
        return Boolean.TRUE.equals(liked) ? R.string.like_remove : R.string.like_add;
    }

    /**
     * Saves uri to the liked songs (like) or removes it, unless that is
     * already under way, then says so in a toast and drops the library's
     * cached liked songs; after runs (main thread) once it is done or failed.
     * A missing scope or login offers to log in again (from activity, if
     * still there).
     */
    static void set(final Activity activity, final Spotify spotify, final String uri, final boolean like,
            final Runnable after) {
        final int generation = spotify.generation();
        known.login(generation);
        if (!known.startChange(uri)) return;
        spotify.run(new Spotify.Call<Void>() {
            @Override public Void run(SpotifyApi api) throws IOException {
                if (like) api.saveTrack(uri);
                else api.removeTrack(uri);
                return null;
            }
        }, new Spotify.Callback<Void>() {
            @Override public void done(Void result) {
                if (spotify.generation() == generation) {
                    known.changeEnded(uri);
                    known.put(uri, like);
                }
                LibraryActivity.forgetLiked();
                Toast.makeText(activity.getApplicationContext(), like ? R.string.like_added : R.string.like_removed,
                        Toast.LENGTH_SHORT).show();
                after.run();
            }

            @Override public void failed(IOException e) {
                Log.w(TAG, (like ? "save" : "remove") + " failed: " + e.getMessage());
                if (spotify.generation() == generation) known.changeEnded(uri);
                after.run();
                SpotifyException.Reason r = e instanceof SpotifyException ? ((SpotifyException) e).reason : null;
                if (r == SpotifyException.Reason.SCOPE || r == SpotifyException.Reason.LOGIN_REQUIRED) {
                    offerLogin(activity);
                } else {
                    Toast.makeText(activity.getApplicationContext(), R.string.like_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** "Log in again" with a button to スマホで認証 (LoginActivity); a toast if activity is gone. */
    private static void offerLogin(final Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            Toast.makeText(activity.getApplicationContext(), R.string.like_relogin, Toast.LENGTH_LONG).show();
            return;
        }
        Dialogs.neutral(new AlertDialog.Builder(activity)
                .setMessage(R.string.like_relogin)
                .setPositiveButton(R.string.like_login, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        activity.startActivity(new Intent(activity, LoginActivity.class));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }
}
