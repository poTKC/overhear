package com.potkc.overhear;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.potkc.overhear.soloist.PlayerState;
import com.potkc.overhear.soloist.SoloistClient;
import com.potkc.overhear.spotify.Item;
import com.potkc.overhear.spotify.LibraryPath;
import com.potkc.overhear.spotify.LibraryPath.Node;
import com.potkc.overhear.spotify.Page;
import com.potkc.overhear.spotify.OthersTracker;
import com.potkc.overhear.spotify.SpotifyApi;
import com.potkc.overhear.spotify.SpotifyException;
import com.potkc.overhear.spotify.SpotifyJson;

/**
 * Browses the user's Spotify library and searches it, one page on a stack
 * (LibraryPath, saved to Prefs on every change and reopened from there).
 * The player opens it with Soloist's context in the EXTRA_CONTEXT_* extras:
 * then it starts at the page of what plays now (LibraryPath.atContext), and
 * Back leads to its category and TOP; a page opened before Soloist named
 * its context takes the name once it does (nameUntitled). A playlist is someone else's when the
 * cached PLAYLISTS list says so or the service follows it (OthersTracker);
 * otherwise it opens as the user's own, and if its tracks are refused (HTTP
 * 403 or 404: someone else's, one of Spotify's own, or a deleted one) the
 * page turns into the OTHERS_PLAYLIST one, in the saved stack too.
 * Lists load through Spotify.run: the first page when shown, each further
 * page when the last row comes into view. The CACHE_MAX most recently used
 * lists stay cached for the process lifetime (dropped when the login
 * changes); ↻ reloads the page shown, or retries the page that failed.
 * Tapping the track marked as playing now only returns to the player.
 * Tapping anything else playable asks the Web API to play it on this
 * device and returns to the player. A failed call may still have been applied, so
 * unless it certainly sent nothing (no login, device not listed) Soloist's
 * track (or context, when playing from the top) is checked, again after
 * PLAY_RECHECK_MS; only if it is not what was asked does Soloist play the
 * context from the top instead. The ▶ and ⇄ rows of a list of tracks
 * play it with shuffle off from the first track, or with shuffle on from a
 * random one (PlayPlan: shuffle set through the Web API first, checked again
 * PlayPlan.CHECK_MS after the play); the artist's ▶ and a tapped track leave
 * shuffle as it is.
 *
 * A long-press on a track or album row (in any list) offers the track's
 * album and the artists (LibraryPath.linksOf) in a dialog; the choice is
 * pushed like a tapped row. On rows with nothing to offer it does nothing.
 *
 * Another user's playlist (OTHERS_PLAYLIST), whose tracks the Web API
 * refuses, opens with its ▶ and ⇄ rows and a note only. ▶ or ⇄ has
 * PlayerService start an OthersTracker, which rebuilds the list from
 * Soloist's queue, sends the play and shows the player at once. The page
 * lists the tracker's tracks (never cached here) while its playlist is
 * Soloist's context, and redraws on the service's UI updates; a tapped track
 * plays as on any other list.
 *
 * What plays now is marked in every list, paused or not: the track whose
 * URI is Soloist's track with its title in green after a bar chart icon, the
 * album, playlist or artist (or the liked songs' menu row) that is Soloist's
 * context with its title in green. The rows redraw when either changes.
 *
 * Full screen follows the player (Prefs.isFullscreen, TopStrip): the status
 * bar hidden and the same top strip (time, battery) above the bar.
 *
 * Main thread only. Spotify callbacks can arrive after the Activity that
 * started them is gone: they still fill the cache and redraw the resumed
 * instance, if any (active), so a reopened screen sees the result too.
 */
public class LibraryActivity extends Activity {
    /** One list as loaded so far; main thread only. */
    private static final class Loaded {
        final List<Item> items = new ArrayList<Item>();
        /** What the following page needs (Page.next); null for the first page. */
        String next;
        /** Another page to load (the first one included). */
        boolean more = true;
        boolean loading;
        IOException error;
        /** The user's ID as of the last page ("" if unknown, null before any). */
        String me;
        /** SEARCH only: the query (null until one is entered) and its results. */
        String query;
        SpotifyJson.SearchResult search;
    }

    /** What one Spotify call brings back for a page. */
    private static final class Fetched {
        final String me;
        final Page page;
        final SpotifyJson.SearchResult search;

        Fetched(String me, Page page, SpotifyJson.SearchResult search) {
            this.me = me;
            this.page = page;
            this.search = search;
        }
    }

    /** Soloist's context as the player saw it: its uri, name and (for an album) a small cover; all optional. */
    static final String EXTRA_CONTEXT_URI = "context_uri";
    static final String EXTRA_CONTEXT_NAME = "context_name";
    static final String EXTRA_CONTEXT_COVER = "context_cover";

    private static final String TAG = "OverhearLibrary";
    /** Spotify's green: the title of what plays now. */
    private static final int NOW_GREEN = 0xff1ed760;
    private static final int CACHE_MAX = 30;
    /** After a failed play call, how long to wait for a play that may have gone through anyway. */
    private static final long PLAY_RECHECK_MS = 1500;
    /** Outlives the Activity: a recheck still runs after it is gone. */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The loaded pages, least recently used first; main thread only. */
    private static final Map<Node, Loaded> cache = new LinkedHashMap<Node, Loaded>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Node, Loaded> eldest) {
            return size() > CACHE_MAX;
        }
    };
    /** The login (SpotifyAuth.generation) the cache was filled under. */
    private static int cacheGeneration = -1;
    /** Counts the plays sent (main thread): a shuffle check gives way to a later play. */
    private static int playGeneration;
    /** The resumed instance, which load results redraw; null while none is. */
    private static LibraryActivity active;

    private Spotify spotify;
    private Prefs prefs;
    private TopStrip topStrip;
    private ArtworkLoader<Artwork.Cover> artwork;
    private int thumbPx;
    private float thumbRadius;
    private TextView pageTitle;
    private View reload;
    private EditText query;
    private ListView list;
    private TextView empty;
    private final RowAdapter adapter = new RowAdapter();
    private List<Node> stack;
    /** The login (Spotify.generation) stack belongs to; another one starts again from the top. */
    private int stackGeneration;
    /** The first visible row of the pages below the top one, by stack size, to return to. */
    private final Map<Integer, Integer> scroll = new HashMap<Integer, Integer>();
    private boolean destroyed;
    /** A play request is in flight: further taps are ignored. */
    private boolean playing;
    /** The library_playing toast was shown for the play in flight. */
    private boolean playingNoted;
    private PlayerService service;
    private boolean bound;
    /** The long-press choices on screen, or null. */
    private AlertDialog links;
    /** What the OTHERS_PLAYLIST rows were last built from (othersKey), to skip renders that change nothing. */
    private String othersShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        TopStrip.apply(this, null, prefs); // before the first layout: no jump
        setContentView(R.layout.activity_library);
        topStrip = new TopStrip(this);
        spotify = Spotify.get(this);
        artwork = Artwork.loader(this);
        float density = getResources().getDisplayMetrics().density;
        thumbPx = Math.round(40 * density);
        thumbRadius = 3 * density;

        pageTitle = (TextView) findViewById(R.id.page_title);
        reload = findViewById(R.id.reload);
        query = (EditText) findViewById(R.id.query);
        list = (ListView) findViewById(R.id.list);
        empty = (TextView) findViewById(R.id.empty);
        list.setEmptyView(empty);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Row row = adapter.getItem(position);
                if (row.action == null) return;
                if (!playing) {
                    row.action.run();
                } else if (!playingNoted) {
                    // Taps wait for the play in flight; say so once.
                    playingNoted = true;
                    Toast.makeText(LibraryActivity.this, R.string.library_playing, Toast.LENGTH_SHORT).show();
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Row row = adapter.getItem(position);
                if (row.item != null && !playing) showLinks(row.item);
                return true; // consumed: never a tap, with a dialog or without
            }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {}

            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total > 0 && first + visible >= total) loadMoreLater();
            }
        });
        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onBackPressed();
            }
        });
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                reloadPage();
            }
        });
        findViewById(R.id.to_player).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toPlayer();
            }
        });
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId != EditorInfo.IME_ACTION_SEARCH && !enter) return false;
                search(v.getText().toString().trim());
                return true;
            }
        });

        // Logged out, only TOP has something to show (its login row).
        stack = spotify.isLoggedIn() ? LibraryPath.decode(prefs.getLibraryPath()) : LibraryPath.decode(null);
        stackGeneration = spotify.generation();
        // Not when recreated: the user may have moved on from that page.
        if (spotify.isLoggedIn() && savedInstanceState == null) openAtContext(getIntent());
        Loaded l = cache.get(Node.of(Node.Type.SEARCH));
        if (l != null && l.query != null) query.setText(l.query);
        // Flags 0: connects when the service runs, never creates or keeps it.
        bound = bindService(new Intent(this, PlayerService.class), connection, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TopStrip.apply(this, topStrip, prefs); // setup may have changed it
        topStrip.resume();
        active = this;
        if (spotify.generation() != stackGeneration) {
            // Logged out or in again (LoginActivity) meanwhile: the pages may be another account's.
            stackGeneration = spotify.generation();
            if (stack.size() > 1) {
                stack = LibraryPath.decode(null);
                scroll.clear();
                saveStack();
            }
        }
        if (service != null) service.addUiListener(uiListener);
        render(); // the login may have changed in LoginActivity
    }

    @Override
    protected void onPause() {
        if (active == this) active = null;
        if (service != null) service.removeUiListener(uiListener);
        topStrip.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (active == this) active = null;
        if (service != null) service.removeUiListener(uiListener);
        if (artwork != null) artwork.cancelAll(Artwork.viewsOf(this));
        if (links != null) links.dismiss();
        links = null;
        if (bound) unbindService(connection);
        bound = false;
        service = null;
        super.onDestroy();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).service();
            if (active != LibraryActivity.this) return;
            service.addUiListener(uiListener);
            if (current().type == Node.Type.OTHERS_PLAYLIST) render();
            else showNow();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null; // a restarted service has no tracker
            if (active != LibraryActivity.this) return;
            if (current().type == Node.Type.OTHERS_PLAYLIST) render();
            else showNow();
        }
    };

    /**
     * Redraws an open OTHERS_PLAYLIST page when what it shows changes, an
     * untitled page Soloist names, and the rows when what plays now changes.
     */
    private final Runnable uiListener = new Runnable() {
        @Override public void run() {
            if (active != LibraryActivity.this) return;
            Node node = current();
            if (untitled(node)) {
                render(); // nameUntitled
            } else if (node.type == Node.Type.OTHERS_PLAYLIST && !othersKey(node).equals(othersShown)) {
                render();
            } else {
                showNow();
            }
        }
    };

    /**
     * Marks what plays now (Soloist's track and context) in the rows shown, if
     * that changed; nothing without a track, as the player shows (an idle
     * Soloist may still report the last context).
     */
    private void showNow() {
        SoloistClient client = client();
        PlayerState s = client != null && client.isConnected() ? client.getState() : null;
        if (s != null && s.getTitle() == null) s = null;
        adapter.setNow(s == null ? null : s.getTrackUri(), s == null ? null : s.getContextUri());
    }

    // --- the stack ---

    private Node current() {
        return stack.get(stack.size() - 1);
    }

    /** Starts at the page of the context in intent's extras, if it names one (saved as the stack). */
    private void openAtContext(Intent intent) {
        String uri = intent.getStringExtra(EXTRA_CONTEXT_URI);
        if (uri == null) return;
        String likedOwner = LibraryPath.likedOwner(uri);
        String me = cachedMe();
        if (likedOwner != null && me != null && !likedOwner.equals(me)) return; // someone else's liked songs
        List<Node> s = LibraryPath.atContext(stack, uri, intent.getStringExtra(EXTRA_CONTEXT_NAME),
                intent.getStringExtra(EXTRA_CONTEXT_COVER), othersPlaylist(uri));
        if (s == stack) return;
        Node old = current();
        if (s.size() == stack.size() && s.get(s.size() - 1).samePage(old)) {
            // Saved there without a title; its cache entry (tracks without a cover, maybe) is dropped.
            if (cacheGeneration == spotify.generation()) cache.remove(old);
        } else {
            Log.i(TAG, "opening at " + s.get(s.size() - 1));
        }
        stack = s;
        saveStack();
    }

    /** The user's ID as a cached page of this login knows it, or null. */
    private String cachedMe() {
        if (cacheGeneration != spotify.generation()) return null;
        for (Loaded l : cache.values()) {
            if (l.me != null && !l.me.isEmpty()) return l.me;
        }
        return null;
    }

    private static boolean untitled(Node node) {
        return node.uri != null && (node.title == null || node.title.isEmpty());
    }

    /**
     * Names the page shown when it has no title (opened from the player
     * before Soloist named its context) and Soloist names it now: the
     * context name, and for an album the playing track's cover if it is from
     * that album. Its cache entry moves along; saved as the stack.
     */
    private void nameUntitled() {
        Node node = current();
        if (!untitled(node)) return;
        SoloistClient client = client();
        if (client == null) return;
        PlayerState s = client.getState();
        String name = s.getContextName();
        if (!node.uri.equals(s.getContextUri()) || name == null || name.isEmpty()) return;
        String cover = node.coverUrl;
        if (cover == null && node.uri.equals(s.getAlbumUri())) cover = s.getSmallCoverUrl();
        Node named = node.named(name, cover);
        Loaded l = cacheGeneration == spotify.generation() ? cache.remove(node) : null;
        // An album's tracks carry its cover: with a new one they load again.
        if (l != null && (cover == null || cover.equals(node.coverUrl))) cache.put(named, l);
        LibraryPath.replace(stack, node, named);
        saveStack();
    }

    /**
     * Whether playlist uri is someone else's: TRUE when the service follows it
     * (only an OTHERS_PLAYLIST page starts that), else from the cached
     * PLAYLISTS list as itemRow tells; null when neither knows.
     */
    private Boolean othersPlaylist(String uri) {
        PlayerService p = PlayerService.current();
        OthersTracker o = p == null ? null : p.getOthers();
        if (o != null && uri.equals(o.playlistUri())) return Boolean.TRUE;
        if (cacheGeneration != spotify.generation()) return null; // another login's
        Loaded l = cache.get(Node.of(Node.Type.PLAYLISTS));
        if (l == null) return null;
        for (Item item : l.items) {
            if (uri.equals(item.uri)) {
                return l.me == null || l.me.isEmpty() || !l.me.equals(item.ownerId);
            }
        }
        return null;
    }

    /**
     * Puts to where from's page was, in the saved stack, this one and the
     * resumed instance's (another instance may have saved since this one
     * started from's load). This stack is saved only while it is still the
     * live one: a finishing instance would save a stale stack over a newer.
     */
    private void replaceNode(Node from, Node to) {
        List<Node> saved = LibraryPath.decode(prefs.getLibraryPath());
        if (LibraryPath.replace(saved, from, to)) prefs.setLibraryPath(LibraryPath.encode(saved));
        if (!destroyed && LibraryPath.replace(stack, from, to) && !isFinishing()) {
            saveStack();
            if (active == this) render();
        }
        if (active != null && active != this && LibraryPath.replace(active.stack, from, to)) {
            active.saveStack();
            active.render();
        }
    }

    private void push(Node node) {
        scroll.put(stack.size(), list.getFirstVisiblePosition());
        stack.add(node);
        saveStack();
        hideKeyboard();
        render();
        list.setSelection(0);
        if (node.type == Node.Type.SEARCH && query.getText().length() == 0) showKeyboard();
    }

    @Override
    public void onBackPressed() {
        if (stack.size() <= 1) {
            finish();
            return;
        }
        stack.remove(stack.size() - 1);
        saveStack();
        hideKeyboard();
        render();
        Integer at = scroll.remove(stack.size());
        list.setSelection(at == null ? 0 : at);
    }

    /** ♫: back to the player screen, keeping the saved path for the next visit. */
    private void toPlayer() {
        startActivity(new Intent(this, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void saveStack() {
        prefs.setLibraryPath(LibraryPath.encode(stack));
    }

    // --- loading ---

    /** The current login's cache entry for node, created on first use. */
    private Loaded loaded(Node node) {
        int generation = spotify.generation();
        if (generation != cacheGeneration) {
            cache.clear();
            cacheGeneration = generation;
        }
        Loaded l = cache.get(node);
        if (l == null) {
            l = new Loaded();
            if (node.type == Node.Type.SEARCH) l.more = false; // until a query
            cache.put(node, l);
        }
        return l;
    }

    private static boolean loads(Node node) {
        return node.type != Node.Type.TOP && node.type != Node.Type.OTHERS_PLAYLIST;
    }

    /** Retries the page that failed after others loaded, else reloads from the start. */
    private void reloadPage() {
        Node node = current();
        if (!loads(node)) return;
        Loaded old = loaded(node);
        if (old.error != null && !old.items.isEmpty()) {
            retry(node, old);
            return;
        }
        String q = node.type == Node.Type.SEARCH ? loaded(node).query : null;
        cache.remove(node);
        if (q != null) {
            search(q);
            return;
        }
        render();
    }

    private void search(String q) {
        hideKeyboard();
        if (q.isEmpty()) return;
        Loaded l = new Loaded();
        l.query = q;
        loaded(current()); // drops a stale cache first
        cache.put(current(), l);
        render();
        list.setSelection(0);
    }

    /** From onScroll, which runs during layout: render afterwards. */
    private void loadMoreLater() {
        list.post(new Runnable() {
            @Override public void run() {
                if (destroyed || !loads(current())) return;
                Loaded l = loaded(current());
                if (l.items.isEmpty() && l.search == null) return; // the first page is render's
                if (startLoad(current(), l)) render();
            }
        });
    }

    /** Starts loading l's next page unless one is loading, failed or there is none. */
    private boolean startLoad(final Node node, final Loaded l) {
        if (l.loading || !l.more || l.error != null) return false;
        l.loading = true;
        final String next = l.next;
        final String q = l.query;
        spotify.run(new Spotify.Call<Fetched>() {
            @Override public Fetched run(SpotifyApi api) throws IOException {
                String me = api.me();
                if (node.type == Node.Type.SEARCH) return new Fetched(me, null, api.search(q));
                try {
                    return new Fetched(me, fetch(api, node, next), null);
                } catch (SpotifyException e) {
                    if (node.type == Node.Type.PLAYLIST && next == null && e.reason == SpotifyException.Reason.HTTP
                            && (e.status == 403 || e.status == 404)) {
                        throw new Refused(e);
                    }
                    throw e;
                }
            }
        }, new Spotify.Callback<Fetched>() {
            @Override public void done(Fetched f) {
                l.loading = false;
                l.me = f.me;
                if (f.search != null) {
                    l.search = f.search;
                    l.more = false;
                } else {
                    l.items.addAll(f.page.items);
                    l.next = f.page.next;
                    l.more = f.page.next != null;
                }
                renderActive();
            }

            @Override public void failed(IOException e) {
                l.loading = false;
                if (e instanceof Refused && dropCached(l)) {
                    // Someone else's after all: its page lists Soloist's queue instead.
                    Log.i(TAG, node + " refused (" + e.getMessage() + "): another user's playlist");
                    replaceNode(node, Node.playlist(node.uri, titleOf(node), true));
                    return;
                }
                l.error = e;
                renderActive();
            }
        });
        return true;
    }

    /**
     * The first page of a PLAYLIST's tracks failed with HTTP 403 or 404: the
     * Web API refuses the tracks of others' playlists (Spotify's own 37i9…
     * ones included), which a playlist opened from the player's context may
     * be. A deleted playlist of the user's own (404) turns up so too.
     */
    private static final class Refused extends IOException {
        Refused(SpotifyException e) {
            super(e.getMessage());
        }
    }

    /** Drops l from the cache; whether it was there (not reloaded or dropped with a login meanwhile). */
    private static boolean dropCached(Loaded l) {
        for (Iterator<Loaded> i = cache.values().iterator(); i.hasNext(); ) {
            if (i.next() == l) {
                i.remove();
                return true;
            }
        }
        return false;
    }

    /** node's title as the stack shown has it now (nameUntitled may have named it since). */
    private String titleOf(Node node) {
        for (Node n : stack) {
            if (n.samePage(node) && n.title != null) return n.title;
        }
        return node.title;
    }

    private void retry(Node node, Loaded l) {
        l.error = null;
        startLoad(node, l);
        render();
    }

    private static void renderActive() {
        if (active != null) active.render();
    }

    /** One page of node's list; on the Spotify thread. */
    private static Page fetch(SpotifyApi api, Node node, String next) throws IOException {
        if (node.type == Node.Type.ARTISTS) return api.followedArtists(next); // next is a cursor
        int offset = next == null ? 0 : Integer.parseInt(next);
        switch (node.type) {
            case LIKED: return api.savedTracks(offset);
            case PLAYLISTS: return api.playlists(offset);
            case ALBUMS: return api.savedAlbums(offset);
            case RECENT: return api.recent();
            case ALBUM: return api.albumTracks(node.uri, node.coverUrl, offset);
            case PLAYLIST: return api.playlistTracks(node.uri, offset);
            case ARTIST: return api.artistAlbums(node.uri, offset);
            default: throw new IllegalStateException("nothing to load for " + node);
        }
    }

    // --- rendering ---

    private void render() {
        nameUntitled();
        Node node = current();
        boolean search = node.type == Node.Type.SEARCH;
        pageTitle.setText(title(node));
        reload.setVisibility(loads(node) ? View.VISIBLE : View.INVISIBLE);
        query.setVisibility(search ? View.VISIBLE : View.GONE);
        List<Row> rows = new ArrayList<Row>();
        if (!loads(node)) {
            if (node.type == Node.Type.TOP) topRows(rows);
            else othersRows(rows, node);
            empty.setText("");
            showNow();
            adapter.setRows(rows);
            return;
        }
        Loaded l = loaded(node);
        if (l.items.isEmpty() && l.search == null && l.next == null) startLoad(node, l); // the first page only
        if (search) searchRows(rows, l);
        else listRows(rows, node, l);
        statusRow(rows, node, l);
        empty.setText(search && l.query == null ? ""
                : getString(search ? R.string.library_no_results : R.string.library_empty));
        showNow();
        adapter.setRows(rows);
    }

    private String title(Node node) {
        switch (node.type) {
            case TOP: return getString(R.string.library_title);
            case SEARCH: return getString(R.string.library_search);
            case LIKED: return getString(R.string.library_liked);
            case PLAYLISTS: return getString(R.string.library_playlists);
            case ALBUMS: return getString(R.string.library_albums);
            case ARTISTS: return getString(R.string.library_artists);
            case RECENT: return getString(R.string.library_recent);
            default: return node.title == null ? "" : node.title;
        }
    }

    private void topRows(List<Row> rows) {
        if (!spotify.isLoggedIn()) {
            rows.add(Row.menu(R.drawable.ic_lib_login, getString(R.string.library_login), true, new Runnable() {
                @Override public void run() {
                    startActivity(new Intent(LibraryActivity.this, LoginActivity.class));
                }
            }));
            return;
        }
        rows.add(menuRow(R.drawable.ic_lib_search, R.string.library_search, Node.Type.SEARCH));
        rows.add(menuRow(R.drawable.ic_lib_liked, R.string.library_liked, Node.Type.LIKED));
        rows.add(menuRow(R.drawable.ic_lib_playlists, R.string.library_playlists, Node.Type.PLAYLISTS));
        rows.add(menuRow(R.drawable.ic_lib_albums, R.string.library_albums, Node.Type.ALBUMS));
        rows.add(menuRow(R.drawable.ic_lib_artists, R.string.library_artists, Node.Type.ARTISTS));
        rows.add(menuRow(R.drawable.ic_lib_recent, R.string.library_recent, Node.Type.RECENT));
    }

    private Row menuRow(int symbol, int title, Node.Type type) {
        Row row = Row.menu(symbol, getString(title), true, pushing(Node.of(type)));
        return type == Node.Type.LIKED ? row.likedSongs() : row;
    }

    /** Rows for the pages that list one kind of item, with their fixed first row. */
    private void listRows(List<Row> rows, final Node node, Loaded l) {
        switch (node.type) {
            case PLAYLISTS:
                rows.add(menuRow(R.drawable.ic_lib_liked, R.string.library_liked, Node.Type.LIKED));
                break;
            case LIKED:
                if (l.me != null && !l.me.isEmpty() && !l.items.isEmpty()) {
                    playRows(rows, "spotify:user:" + l.me + ":collection");
                }
                break;
            case ALBUM:
            case PLAYLIST:
                playRows(rows, node.uri);
                break;
            case ARTIST:
                rows.add(Row.menu(R.drawable.ic_lib_play, getString(R.string.library_play_artist), false, new Runnable() {
                    @Override public void run() {
                        play(node.uri, null);
                    }
                }));
                break;
            default:
                break;
        }
        for (Item item : l.items) rows.add(itemRow(item, l.me));
    }

    private void searchRows(List<Row> rows, Loaded l) {
        SpotifyJson.SearchResult r = l.search;
        if (r == null) return;
        group(rows, R.string.library_tracks, r.tracks, l.me);
        group(rows, R.string.library_albums, r.albums, l.me);
        group(rows, R.string.library_artists, r.artists, l.me);
        group(rows, R.string.library_playlists, r.playlists, l.me);
    }

    private void group(List<Row> rows, int header, List<Item> items, String me) {
        if (items.isEmpty()) return;
        rows.add(Row.header(getString(header)));
        for (Item item : items) rows.add(itemRow(item, me));
    }

    /** Loading, or why the last page did not load (with what a tap does about it). */
    private void statusRow(List<Row> rows, final Node node, final Loaded l) {
        if (l.loading) {
            rows.add(Row.note(getString(R.string.library_loading), null));
            return;
        }
        if (l.error == null) return;
        SpotifyException.Reason reason = l.error instanceof SpotifyException
                ? ((SpotifyException) l.error).reason : null;
        if (reason == SpotifyException.Reason.LOGIN_REQUIRED) {
            rows.add(Row.note(getString(R.string.library_login_expired), new Runnable() {
                @Override public void run() {
                    l.error = null; // loads again once back from the login
                    startActivity(new Intent(LibraryActivity.this, LoginActivity.class));
                }
            }));
        } else if (reason == SpotifyException.Reason.RATE_LIMITED) {
            int wait = ((SpotifyException) l.error).retryAfterSeconds;
            rows.add(Row.note(wait > 0 ? getString(R.string.library_rate_limited_wait, wait)
                    : getString(R.string.library_rate_limited), null)); // ↻ retries
        } else {
            rows.add(Row.note(getString(R.string.library_load_failed), new Runnable() {
                @Override public void run() {
                    retry(node, l);
                }
            }));
        }
    }

    /** ▶ plays contextUri in order from its first track, ⇄ shuffled from a random one. */
    private void playRows(List<Row> rows, final String contextUri) {
        rows.add(Row.menu(R.drawable.ic_lib_play, getString(R.string.library_play), false, new Runnable() {
            @Override public void run() {
                play(contextUri, null, Boolean.FALSE, null);
            }
        }));
        rows.add(Row.menu(R.drawable.ic_lib_shuffle, getString(R.string.shuffle), false, new Runnable() {
            @Override public void run() {
                play(contextUri, null, Boolean.TRUE, null);
            }
        }));
    }

    /** me: the user's ID ("" if unknown), to tell their own playlists from others'. */
    private Row itemRow(final Item item, String me) {
        switch (item.kind) {
            case TRACK:
                return Row.item(item, false, new Runnable() {
                    @Override public void run() {
                        playTrack(item.contextUri, item.uri);
                    }
                });
            case ALBUM:
                return Row.item(item, true, pushing(Node.album(item.uri, item.title, item.coverUrl)));
            case ARTIST:
                return Row.item(item, true, pushing(Node.artist(item.uri, item.title)));
            default: // PLAYLIST
                // Unless it is surely the user's own (an unknown ID or owner included): the Web API
                // refuses others' tracks, while Soloist's queue lists anyone's.
                boolean others = me == null || me.isEmpty() || !me.equals(item.ownerId);
                return Row.item(item, true, pushing(Node.playlist(item.uri, item.title, others)));
        }
    }

    /** The pages item links to, as a dialog of choices; nothing when there are none. */
    private void showLinks(Item item) {
        final List<Node> nodes = LibraryPath.linksOf(item, current());
        if (nodes.isEmpty()) return;
        String[] labels = new String[nodes.size()];
        for (int i = 0; i < labels.length; i++) {
            Node n = nodes.get(i);
            if (n.type == Node.Type.ALBUM) {
                labels[i] = getString(R.string.library_open_album);
            } else {
                // The name, even for a sole artist: it says whose page opens.
                labels[i] = n.title == null || n.title.isEmpty() ? getString(R.string.library_open_artist)
                        : getString(R.string.library_open_named, n.title);
            }
        }
        if (links != null) links.dismiss();
        links = new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        links = null;
                        if (!destroyed) push(nodes.get(which));
                    }
                })
                .show();
        Dialogs.neutral(links);
    }

    private Runnable pushing(final Node node) {
        return new Runnable() {
            @Override public void run() {
                push(node);
            }
        };
    }

    // --- playing ---

    /**
     * Plays contextUri from trackUri (null: from the top), or trackUri alone
     * when contextUri is null, leaving shuffle as it is, and returns to the
     * player.
     */
    private void play(String contextUri, String trackUri) {
        play(contextUri, trackUri, null, null);
    }

    /** One play the library sent, as its callbacks and the shuffle check need it. */
    private static final class Request {
        final String contextUri;
        final String trackUri;
        final PlayPlan plan;
        /** playGeneration when it was sent. */
        final int generation;
        /** When it was asked for (SystemClock.elapsedRealtime). */
        final long playMs;
        final OthersTracker others;

        Request(String contextUri, String trackUri, PlayPlan plan, int generation, long playMs,
                OthersTracker others) {
            this.contextUri = contextUri;
            this.trackUri = trackUri;
            this.plan = plan;
            this.generation = generation;
            this.playMs = playMs;
            this.others = others;
        }
    }

    /**
     * As play(contextUri, trackUri) with shuffle (null: left as it is) as
     * PlayPlan decides: ⇄ sets it through the Web API first, ▶ too unless
     * Soloist has it off already, and plays from the first track (offset 0)
     * since the shuffle call does not always take. A failed shuffle call
     * only gets logged, unless it certainly sent nothing (no login, device
     * not listed): then the play fails at once without trying again.
     * PlayPlan.CHECK_MS after the play Soloist sets the shuffle if the
     * context plays without it, unless another play, a control or a queue
     * jump came first; that only shuffles what follows, so a ⇄ whose shuffle
     * did not take keeps the first track. others (or null) is told when the
     * request returned.
     */
    private void play(String contextUri, String trackUri, Boolean shuffle, OthersTracker others) {
        playing = true;
        playingNoted = false;
        final String device = prefs.getDeviceName();
        SoloistClient client = client();
        Boolean soloistShuffle = client != null && client.isConnected() ? client.getState().isShuffle() : null;
        final PlayPlan plan = PlayPlan.of(contextUri, trackUri, shuffle, soloistShuffle);
        final Request r = new Request(contextUri, trackUri, plan, ++playGeneration, SystemClock.elapsedRealtime(),
                others);
        if (shuffle != null) Log.i(TAG, "play " + contextUri + " with shuffle " + shuffle
                + (plan.callShuffle ? "" : " (Soloist has it)"));
        spotify.run(new Spotify.Call<Void>() {
            @Override public Void run(SpotifyApi api) throws IOException {
                if (plan.callShuffle) {
                    try {
                        api.shuffle(device, plan.shuffle);
                    } catch (IOException e) {
                        if (sentNothing(e)) throw e; // the play would fail the same way
                        Log.w(TAG, "shuffle " + plan.shuffle + " failed (" + e.getMessage() + "), playing anyway");
                    }
                }
                if (plan.fromFirst) api.playFirst(device, r.contextUri);
                else api.play(device, r.contextUri, r.trackUri);
                return null;
            }
        }, new Spotify.Callback<Void>() {
            @Override public void done(Void result) {
                afterPlay(r);
            }

            @Override public void failed(final IOException e) {
                if (sentNothing(e) || playsNow(r.contextUri, r.trackUri)) {
                    // Login or device trouble sent nothing; otherwise the play went through after all.
                    afterFailedPlay(e, r);
                    return;
                }
                // A failed call (timeout, 5xx) may still have been applied: look again shortly.
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        afterFailedPlay(e, r);
                    }
                }, PLAY_RECHECK_MS);
            }
        });
    }

    /** After a failed play: done if Soloist plays what was asked, else Soloist plays it from the top. */
    private void afterFailedPlay(IOException e, Request r) {
        if (!sentNothing(e) && playsNow(r.contextUri, r.trackUri)) {
            Log.i(TAG, "play failed (" + e.getMessage() + "), but it plays");
        } else {
            Log.w(TAG, "play failed (" + e.getMessage() + "): from the top through Soloist");
            // Still wanted after leaving the screen: the user asked for it.
            playFromTop(r.contextUri != null ? r.contextUri : r.trackUri);
        }
        afterPlay(r);
    }

    /** The play went out (or its fallback): checks, the tracker, and back to the player. */
    private void afterPlay(Request r) {
        playing = false;
        if (r.plan.checks) checkShuffleLater(r);
        PlayerService p = player();
        if (r.others != null && p != null) p.othersPlayReturned(r.others);
        if (!destroyed) finish();
    }

    /** PlayPlan.CHECK_MS from now: Soloist sets the wanted shuffle if PlayPlan.fixNow says so. Outlives this screen. */
    private void checkShuffleLater(final Request r) {
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                PlayerService p = player();
                if (p == null) return;
                SoloistClient client = p.client();
                PlayerState s = client.getState();
                if (!r.plan.fixNow(r.contextUri, r.generation, playGeneration, r.playMs, p.lastControlMs(),
                        p.isJumping(), client.isConnected(), s.getContextUri(), s.isShuffle())) {
                    return;
                }
                Log.i(TAG, "shuffle is not " + r.plan.shuffle + " after the play: setting it through Soloist");
                client.setShuffle(r.plan.shuffle);
            }
        }, PlayPlan.CHECK_MS);
    }

    /** A failure that certainly sent no play: no login, or this device is not in the user's list. */
    /**
     * A track row's tap: the track marked as playing now only returns to the
     * player (it keeps playing, not restarted); any other plays from it.
     */
    private void playTrack(String contextUri, String trackUri) {
        if (trackUri != null && trackUri.equals(adapter.nowTrack)) {
            toPlayer();
            return;
        }
        play(contextUri, trackUri);
    }

    private static boolean sentNothing(IOException e) {
        return (e instanceof SpotifyException
                && ((SpotifyException) e).reason == SpotifyException.Reason.LOGIN_REQUIRED)
                || SpotifyApi.isDeviceNotListed(e);
    }

    /** Soloist's current track is trackUri, or (when none was asked for) its context is contextUri. */
    private boolean playsNow(String contextUri, String trackUri) {
        SoloistClient client = client();
        if (client == null) return false;
        PlayerState s = client.getState();
        return trackUri != null ? trackUri.equals(s.getTrackUri()) : contextUri.equals(s.getContextUri());
    }

    /** The fallback without the Web API: Soloist plays uri from its start. */
    private void playFromTop(String uri) {
        SoloistClient client = client();
        Context app = getApplicationContext();
        if (client == null) {
            Toast.makeText(app, R.string.library_play_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        client.play(uri);
        Toast.makeText(app, R.string.library_play_from_top, Toast.LENGTH_SHORT).show();
    }

    /** The running service, or null. */
    private PlayerService player() {
        return service != null ? service : PlayerService.current(); // unbound once destroyed
    }

    /** The service's Soloist client, or null while the service is not running. */
    private SoloistClient client() {
        PlayerService s = player();
        return s == null ? null : s.client();
    }

    // --- another user's playlist ---

    /**
     * ▶ or ⇄ on an OTHERS_PLAYLIST page: the service follows the play from
     * now on, and the player shows at once (the call takes seconds).
     */
    private void playOthers(String playlistUri, Boolean shuffle) {
        PlayerService p = player();
        OthersTracker t = p == null ? null : p.startOthers(playlistUri);
        play(playlistUri, null, shuffle, t);
        finish();
    }

    /**
     * The service's tracker for node's playlist while that is Soloist's
     * context and has not left it since (a later play of it, from the phone
     * say, may be in another order); else null.
     */
    private OthersTracker liveOthers(Node node) {
        PlayerService p = player();
        OthersTracker o = p == null ? null : p.getOthers();
        if (o == null || o.hasLeft() || !node.uri.equals(o.playlistUri())) return null;
        return node.uri.equals(p.client().getState().getContextUri()) ? o : null;
    }

    /** What othersRows shows for node depends on. */
    private String othersKey(Node node) {
        OthersTracker o = liveOthers(node);
        return (o == null ? "-" : System.identityHashCode(o) + ":" + o.version()) + "/" + noPlayer();
    }

    private boolean noPlayer() {
        SoloistClient client = client();
        return client == null || !client.isConnected();
    }

    private void othersRows(List<Row> rows, final Node node) {
        othersShown = othersKey(node);
        if (noPlayer()) rows.add(Row.note(getString(R.string.library_others_no_player), null));
        rows.add(Row.menu(R.drawable.ic_lib_play, getString(R.string.library_play), false, new Runnable() {
            @Override public void run() {
                playOthers(node.uri, Boolean.FALSE);
            }
        }));
        rows.add(Row.menu(R.drawable.ic_lib_shuffle, getString(R.string.shuffle), false, new Runnable() {
            @Override public void run() {
                playOthers(node.uri, Boolean.TRUE);
            }
        }));
        final OthersTracker o = liveOthers(node);
        if (o == null) {
            rows.add(Row.note(getString(R.string.library_others_before_play), null));
            return;
        }
        switch (o.status()) {
            case STARTING:
                rows.add(Row.note(getString(R.string.library_others_starting), null));
                return;
            case FAILED:
                rows.add(Row.note(getString(R.string.library_others_failed), null));
                return;
            default:
                break;
        }
        for (final Item item : o.items()) {
            rows.add(Row.item(item, false, new Runnable() {
                @Override public void run() {
                    playTrack(o.playlistUri(), item.uri);
                }
            }));
        }
    }

    // --- keyboard ---

    private void showKeyboard() {
        query.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
        query.clearFocus();
    }

    // --- rows ---

    /** One list row: an item, a menu or play row (a symbol), a group header or a note. Immutable. */
    private static final class Row {
        final boolean header;
        /** Menu and play rows: the icon's drawable; 0 otherwise. */
        final int symbol;
        /** Item rows; null otherwise (the cover may still be null). */
        final Item item;
        final String title;
        /** Opens a page below (a chevron at the right edge). */
        final boolean more;
        /** A note: grey, without the cover column. */
        final boolean note;
        /** Null: not tappable. */
        final Runnable action;
        /** The liked songs' menu row, marked while they are the context. */
        final boolean liked;

        private Row(boolean header, int symbol, Item item, String title, boolean more, boolean note,
                Runnable action, boolean liked) {
            this.header = header;
            this.symbol = symbol;
            this.item = item;
            this.title = title;
            this.more = more;
            this.note = note;
            this.action = action;
            this.liked = liked;
        }

        Row likedSongs() {
            return new Row(header, symbol, item, title, more, note, action, true);
        }

        /** Whether the row is what plays now (Soloist's trackUri, contextUri; either may be null). */
        boolean playsNow(String trackUri, String contextUri) {
            if (item != null) return item.playsNow(trackUri, contextUri);
            return liked && LibraryPath.likedOwner(contextUri) != null;
        }

        /** more: true for a menu row, false for a play row. */
        static Row menu(int symbol, String title, boolean more, Runnable action) {
            return new Row(false, symbol, null, title, more, false, action, false);
        }

        static Row item(Item item, boolean more, Runnable action) {
            return new Row(false, 0, item, item.title, more, false, action, false);
        }

        static Row header(String title) {
            return new Row(true, 0, null, title, false, false, null, false);
        }

        static Row note(String title, Runnable action) {
            return new Row(false, 0, null, title, false, true, action, false);
        }
    }

    private final class RowAdapter extends BaseAdapter {
        private List<Row> rows = Collections.emptyList();
        /** Soloist's track and context as the rows show them; null if none. */
        private String nowTrack;
        private String nowContext;

        void setRows(List<Row> list) {
            rows = list;
            notifyDataSetChanged();
        }

        /** Redraws the rows (scrolled as they are) only if the track or the context changed. */
        void setNow(String trackUri, String contextUri) {
            if (same(trackUri, nowTrack) && same(contextUri, nowContext)) return;
            nowTrack = trackUri;
            nowContext = contextUri;
            notifyDataSetChanged();
        }

        private boolean same(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        @Override public int getCount() {
            return rows.size();
        }

        @Override public Row getItem(int position) {
            return rows.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public int getViewTypeCount() {
            return 2;
        }

        @Override public int getItemViewType(int position) {
            return rows.get(position).header ? 1 : 0;
        }

        @Override public boolean areAllItemsEnabled() {
            return false;
        }

        @Override public boolean isEnabled(int position) {
            return rows.get(position).action != null;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rows.get(position);
            if (row.header) return headerView(row, (TextView) convertView);
            View v = convertView != null ? convertView
                    : LayoutInflater.from(LibraryActivity.this).inflate(R.layout.item_library, parent, false);
            TextView title = (TextView) v.findViewById(R.id.item_title);
            TextView subtitle = (TextView) v.findViewById(R.id.item_artists);
            ImageView symbol = (ImageView) v.findViewById(R.id.symbol);
            View marker = v.findViewById(R.id.marker);
            ImageView thumb = (ImageView) v.findViewById(R.id.thumb);
            title.setText(row.title == null ? "" : row.title);
            boolean now = !row.note && row.playsNow(nowTrack, nowContext);
            title.setTextColor(row.note ? 0xffaaaaaa : now ? NOW_GREEN : 0xffffffff);
            // A playing track's bar chart sits before its title; a playing context only turns green.
            boolean nowTrackRow = now && row.item != null && row.item.kind == Item.Kind.TRACK;
            title.setCompoundDrawablesWithIntrinsicBounds(nowTrackRow ? R.drawable.ic_lib_now : 0, 0, 0, 0);
            title.setSingleLine(!row.note);
            String sub = row.item == null ? "" : row.item.subtitle;
            subtitle.setText(sub);
            subtitle.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            marker.setVisibility(row.more ? View.VISIBLE : View.GONE);
            v.findViewById(R.id.thumb_frame).setVisibility(row.note ? View.GONE : View.VISIBLE);
            symbol.setVisibility(row.symbol != 0 ? View.VISIBLE : View.GONE);
            if (row.symbol != 0) symbol.setImageResource(row.symbol);
            thumb.setVisibility(row.item != null ? View.VISIBLE : View.GONE);
            if (row.item != null) {
                showThumb(thumb, row.item.coverUrl);
            } else {
                artwork.cancel(thumb);
            }
            return v;
        }

        private View headerView(Row row, TextView convertView) {
            TextView v = convertView;
            if (v == null) {
                float density = getResources().getDisplayMetrics().density;
                v = new TextView(LibraryActivity.this);
                int h = Math.round(12 * density);
                v.setPadding(h, Math.round(10 * density), h, Math.round(4 * density));
                v.setTextColor(0xffaaaaaa);
                v.setTextSize(13);
            }
            v.setText(row.title);
            return v;
        }
    }

    /** As the player's queue rows: from the memory cache, else loaded in the background. */
    private void showThumb(final ImageView thumb, final String url) {
        thumb.setTag(url);
        Artwork.Cover hit = url == null ? null : artwork.get(url, thumbPx);
        if (hit != null) {
            artwork.cancel(thumb);
            thumb.setImageDrawable(new RoundedArt(hit.bitmap, thumbRadius));
            return;
        }
        thumb.setImageDrawable(RoundedArt.placeholder(getResources(), thumbRadius));
        if (url == null) {
            artwork.cancel(thumb);
            return;
        }
        artwork.load(thumb, url, thumbPx, false, new ArtworkLoader.Callback<Artwork.Cover>() {
            @Override public void onArtwork(String loaded, Artwork.Cover image) {
                if (image != null && loaded.equals(thumb.getTag())) {
                    thumb.setImageDrawable(new RoundedArt(image.bitmap, thumbRadius));
                }
            }
        });
    }
}
