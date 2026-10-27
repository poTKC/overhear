package com.potkc.overhear;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
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
import java.util.List;
import java.util.Map;

import com.potkc.overhear.soloist.SoloistClient;
import com.potkc.overhear.spotify.Item;
import com.potkc.overhear.spotify.LibraryPath;
import com.potkc.overhear.spotify.LibraryPath.Node;
import com.potkc.overhear.spotify.Page;
import com.potkc.overhear.spotify.SpotifyApi;
import com.potkc.overhear.spotify.SpotifyException;
import com.potkc.overhear.spotify.SpotifyJson;

/**
 * Browses the user's Spotify library and searches it, one page on a stack
 * (LibraryPath, saved to Prefs on every change and reopened from there).
 * Lists load through Spotify.run, page by page as the last row comes into
 * view, and stay cached for the process lifetime (dropped when the login
 * changes); ↻ reloads the page shown. Tapping something playable asks the
 * Web API to play it on this device and returns to the player; if that
 * fails, Soloist plays the context from the top instead.
 *
 * Main thread only. Spotify callbacks can arrive after onDestroy: they still
 * fill the cache, but touch no views then.
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

    /** The loaded pages, for the process lifetime; main thread only. */
    private static final Map<Node, Loaded> cache = new HashMap<Node, Loaded>();
    /** The login (SpotifyAuth.generation) the cache was filled under. */
    private static int cacheGeneration = -1;

    private Spotify spotify;
    private Prefs prefs;
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
    /** The first visible row of the pages below the top one, to return to. */
    private final Map<Node, Integer> scroll = new HashMap<Node, Integer>();
    private boolean destroyed;
    /** A play request is in flight: further taps are ignored. */
    private boolean playing;
    private PlayerService service;
    private boolean bound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        spotify = Spotify.get(this);
        prefs = new Prefs(this);
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
                if (row.action != null && !playing) row.action.run();
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
        Loaded l = cache.get(Node.of(Node.Type.SEARCH));
        if (l != null && l.query != null) query.setText(l.query);
        // Flags 0: connects when the service runs, never creates or keeps it.
        bound = bindService(new Intent(this, PlayerService.class), connection, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // the login may have changed in LoginActivity
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (artwork != null) artwork.cancelAll();
        if (bound) unbindService(connection);
        bound = false;
        service = null;
        super.onDestroy();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).service();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    // --- the stack ---

    private Node current() {
        return stack.get(stack.size() - 1);
    }

    private void push(Node node) {
        scroll.put(current(), list.getFirstVisiblePosition());
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
        Integer at = scroll.remove(current());
        list.setSelection(at == null ? 0 : at);
    }

    private void saveStack() {
        prefs.setLibraryPath(LibraryPath.encode(stack));
    }

    // --- loading ---

    /** The current login's cache entry for node, created on first use. */
    private Loaded loaded(Node node) {
        int generation = spotify.api().auth().generation();
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

    private void reloadPage() {
        Node node = current();
        if (!loads(node)) return;
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
                if (startLoad(current(), loaded(current()))) render();
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
                return new Fetched(me, fetch(api, node, next), null);
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
                if (!destroyed) render();
            }

            @Override public void failed(IOException e) {
                l.loading = false;
                l.error = e;
                if (!destroyed) render();
            }
        });
        return true;
    }

    /** One page of node's list; on the Spotify thread. */
    private static Page fetch(SpotifyApi api, Node node, String next) throws IOException {
        int offset = next == null ? 0 : Integer.parseInt(next); // not for ARTISTS: a cursor
        switch (node.type) {
            case LIKED: return api.savedTracks(offset);
            case PLAYLISTS: return api.playlists(offset);
            case ALBUMS: return api.savedAlbums(offset);
            case ARTISTS: return api.followedArtists(next);
            case RECENT: return api.recent();
            case ALBUM: return api.albumTracks(node.uri, node.coverUrl, offset);
            case PLAYLIST: return api.playlistTracks(node.uri, offset);
            case ARTIST: return api.artistAlbums(node.uri, offset);
            default: throw new IllegalStateException("nothing to load for " + node);
        }
    }

    // --- rendering ---

    private void render() {
        Node node = current();
        boolean search = node.type == Node.Type.SEARCH;
        pageTitle.setText(title(node));
        reload.setVisibility(loads(node) ? View.VISIBLE : View.INVISIBLE);
        query.setVisibility(search ? View.VISIBLE : View.GONE);
        List<Row> rows = new ArrayList<Row>();
        if (!loads(node)) {
            if (node.type == Node.Type.TOP) topRows(rows);
            else rows.add(Row.note(getString(R.string.library_others_pending), null)); // Task 19
            empty.setText("");
            adapter.setRows(rows);
            return;
        }
        Loaded l = loaded(node);
        startLoad(node, l);
        if (search) searchRows(rows, l);
        else listRows(rows, node, l);
        statusRow(rows, l);
        empty.setText(search && l.query == null ? ""
                : getString(search ? R.string.library_no_results : R.string.library_empty));
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
            rows.add(Row.menu("♪", getString(R.string.library_login), "›", new Runnable() {
                @Override public void run() {
                    startActivity(new Intent(LibraryActivity.this, LoginActivity.class));
                }
            }));
            return;
        }
        rows.add(menuRow("🔍", R.string.library_search, Node.Type.SEARCH));
        rows.add(menuRow("♥", R.string.library_liked, Node.Type.LIKED));
        rows.add(menuRow("≡", R.string.library_playlists, Node.Type.PLAYLISTS));
        rows.add(menuRow("◉", R.string.library_albums, Node.Type.ALBUMS));
        rows.add(menuRow("♪", R.string.library_artists, Node.Type.ARTISTS));
        rows.add(menuRow("↺", R.string.library_recent, Node.Type.RECENT));
    }

    private Row menuRow(String symbol, int title, Node.Type type) {
        return Row.menu(symbol, getString(title), "›", pushing(Node.of(type)));
    }

    /** Rows for the pages that list one kind of item, with their fixed first row. */
    private void listRows(List<Row> rows, Node node, Loaded l) {
        switch (node.type) {
            case PLAYLISTS:
                rows.add(menuRow("♥", R.string.library_liked, Node.Type.LIKED));
                break;
            case LIKED:
                if (l.me != null && !l.me.isEmpty() && !l.items.isEmpty()) {
                    rows.add(playRow(R.string.library_play, "spotify:user:" + l.me + ":collection"));
                }
                break;
            case ALBUM:
            case PLAYLIST:
                rows.add(playRow(R.string.library_play, node.uri));
                break;
            case ARTIST:
                rows.add(playRow(R.string.library_play_artist, node.uri));
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
    private void statusRow(List<Row> rows, final Loaded l) {
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
            rows.add(Row.note(getString(R.string.library_rate_limited), null)); // ↻ retries
        } else {
            rows.add(Row.note(getString(R.string.library_load_failed), new Runnable() {
                @Override public void run() {
                    l.error = null;
                    render();
                }
            }));
        }
    }

    private Row playRow(int title, final String contextUri) {
        return Row.menu("▶", getString(title), null, new Runnable() {
            @Override public void run() {
                play(contextUri, null);
            }
        });
    }

    /** me: the user's ID, to tell their own playlists from others'. */
    private Row itemRow(final Item item, String me) {
        switch (item.kind) {
            case TRACK:
                return Row.item(item, null, new Runnable() {
                    @Override public void run() {
                        play(item.contextUri, item.uri);
                    }
                });
            case ALBUM:
                return Row.item(item, "›", pushing(Node.album(item.uri, item.title, item.coverUrl)));
            case ARTIST:
                return Row.item(item, "›", pushing(Node.artist(item.uri, item.title)));
            default: // PLAYLIST
                boolean others = item.ownerId != null && me != null && !me.isEmpty() && !me.equals(item.ownerId);
                return Row.item(item, others ? "▶›" : "›", pushing(Node.playlist(item.uri, item.title, others)));
        }
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
     * when contextUri is null, and returns to the player.
     */
    private void play(final String contextUri, final String trackUri) {
        playing = true;
        final String device = prefs.getDeviceName();
        spotify.run(new Spotify.Call<Void>() {
            @Override public Void run(SpotifyApi api) throws IOException {
                api.play(device, contextUri, trackUri);
                return null;
            }
        }, new Spotify.Callback<Void>() {
            @Override public void done(Void result) {
                playing = false;
                if (!destroyed) finish();
            }

            @Override public void failed(IOException e) {
                playing = false;
                // Still wanted after leaving the screen: the user asked for it.
                playFromTop(contextUri != null ? contextUri : trackUri);
                if (!destroyed) finish();
            }
        });
    }

    /** The fallback without the Web API: Soloist plays uri from its start. */
    private void playFromTop(String uri) {
        PlayerService s = service != null ? service : PlayerService.current(); // unbound once destroyed
        SoloistClient client = s == null ? null : s.client();
        Context app = getApplicationContext();
        if (client == null) {
            Toast.makeText(app, R.string.library_play_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        client.play(uri);
        Toast.makeText(app, R.string.library_play_from_top, Toast.LENGTH_SHORT).show();
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
        /** Menu and play rows; null otherwise. */
        final String symbol;
        /** Item rows; null otherwise (the cover may still be null). */
        final Item item;
        final String title;
        final String marker;
        /** A note: grey, without the cover column. */
        final boolean note;
        /** Null: not tappable. */
        final Runnable action;

        private Row(boolean header, String symbol, Item item, String title, String marker, boolean note,
                Runnable action) {
            this.header = header;
            this.symbol = symbol;
            this.item = item;
            this.title = title;
            this.marker = marker;
            this.note = note;
            this.action = action;
        }

        /** marker: "›" for a menu row, null for a play row. */
        static Row menu(String symbol, String title, String marker, Runnable action) {
            return new Row(false, symbol, null, title, marker, false, action);
        }

        static Row item(Item item, String marker, Runnable action) {
            return new Row(false, null, item, item.title, marker, false, action);
        }

        static Row header(String title) {
            return new Row(true, null, null, title, null, false, null);
        }

        static Row note(String title, Runnable action) {
            return new Row(false, null, null, title, null, true, action);
        }
    }

    private final class RowAdapter extends BaseAdapter {
        private List<Row> rows = Collections.emptyList();

        void setRows(List<Row> list) {
            rows = list;
            notifyDataSetChanged();
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
            TextView symbol = (TextView) v.findViewById(R.id.symbol);
            TextView marker = (TextView) v.findViewById(R.id.marker);
            ImageView thumb = (ImageView) v.findViewById(R.id.thumb);
            title.setText(row.title == null ? "" : row.title);
            title.setTextColor(row.note ? 0xffaaaaaa : 0xffffffff);
            title.setSingleLine(!row.note);
            String sub = row.item == null ? "" : row.item.subtitle;
            subtitle.setText(sub);
            subtitle.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            marker.setText(row.marker == null ? "" : row.marker);
            v.findViewById(R.id.thumb_frame).setVisibility(row.note ? View.GONE : View.VISIBLE);
            symbol.setVisibility(row.symbol != null ? View.VISIBLE : View.GONE);
            symbol.setText(row.symbol == null ? "" : row.symbol);
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
