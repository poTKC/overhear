package com.potkc.overhear.spotify;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * The stack of library pages (TOP first) as one string, to reopen the
 * library where it was left. Each node is a line "type|uri|title|cover",
 * every value URL-encoded and a missing one written as "~" (which the
 * encoder never emits). The Node.Type names are part of this persisted
 * format: renaming one makes saved stacks stop at that page. decode() never
 * throws: it keeps what it can read up to the first unreadable line, always
 * starts with TOP and keeps at most MAX_DEPTH nodes.
 *
 * atContext() picks the stack the player's library button (and Back) opens:
 * the page of Soloist's context, under its category, unless the saved stack
 * already ends there. Pure Java.
 */
public final class LibraryPath {
    /** The deepest stack decode() restores, TOP included. */
    public static final int MAX_DEPTH = 32;

    private static final String NONE = "~";

    /** One library page: its type and, for a single album, playlist or artist, which one. Immutable. */
    public static final class Node {
        public enum Type {
            TOP(false), SEARCH(false), LIKED(false), PLAYLISTS(false), ALBUMS(false), ARTISTS(false),
            RECENT(false),
            /** uri, title, coverUrl */
            ALBUM(true),
            /** uri, title: one of the user's own playlists. */
            PLAYLIST(true),
            /** uri, title: someone else's playlist, listed from Soloist's queue. */
            OTHERS_PLAYLIST(true),
            /** uri, title */
            ARTIST(true);

            private final boolean arguments;

            Type(boolean arguments) {
                this.arguments = arguments;
            }

            boolean hasArguments() {
                return arguments;
            }
        }

        public final Type type;
        /** Null for the pages without arguments. */
        public final String uri;
        public final String title;
        /** ALBUM only; null otherwise. */
        public final String coverUrl;

        private Node(Type type, String uri, String title, String coverUrl) {
            this.type = type;
            this.uri = uri;
            this.title = title;
            this.coverUrl = coverUrl;
        }

        public static Node top() {
            return new Node(Type.TOP, null, null, null);
        }

        /** A page without arguments (TOP through RECENT). */
        public static Node of(Type type) {
            if (type.hasArguments()) throw new IllegalArgumentException(type + " needs arguments");
            return new Node(type, null, null, null);
        }

        public static Node album(String uri, String title, String coverUrl) {
            return new Node(Type.ALBUM, checkUri(uri), title, coverUrl);
        }

        /** others: owned by someone else (OTHERS_PLAYLIST), else the user's own (PLAYLIST). */
        public static Node playlist(String uri, String title, boolean others) {
            return new Node(others ? Type.OTHERS_PLAYLIST : Type.PLAYLIST, checkUri(uri), title, null);
        }

        public static Node artist(String uri, String title) {
            return new Node(Type.ARTIST, checkUri(uri), title, null);
        }

        /** This page with another title and (ALBUM only) cover. */
        public Node named(String title, String coverUrl) {
            return new Node(type, uri, title, type == Type.ALBUM ? coverUrl : null);
        }

        /** Whether this is the page n is, by type and uri (titles and covers aside). */
        public boolean samePage(Node n) {
            return type == n.type && same(uri, n.uri);
        }

        private static String checkUri(String uri) {
            if (uri == null || uri.isEmpty()) throw new IllegalArgumentException("no uri");
            return uri;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Node)) return false;
            Node n = (Node) o;
            return type == n.type && same(uri, n.uri) && same(title, n.title) && same(coverUrl, n.coverUrl);
        }

        @Override public int hashCode() {
            int h = type.hashCode();
            h = 31 * h + (uri == null ? 0 : uri.hashCode());
            h = 31 * h + (title == null ? 0 : title.hashCode());
            h = 31 * h + (coverUrl == null ? 0 : coverUrl.hashCode());
            return h;
        }

        @Override public String toString() {
            return type + (uri == null ? "" : "(" + uri + ")");
        }

        private static boolean same(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    private LibraryPath() {}

    /**
     * The stack that opens the library at the page of what plays now, from
     * Soloist's context: an album as TOP › ALBUMS › album (title name, cover
     * coverUrl), a playlist as TOP › PLAYLISTS › playlist, the liked songs
     * (spotify:user:<id>:collection) as TOP › LIKED, an artist as TOP ›
     * ARTISTS › artist. An empty name counts as none. othersPlaylist says whether a playlist is someone
     * else's (OTHERS_PLAYLIST); null when unknown, which opens it as the
     * user's own. Returns saved itself when the context names none of these
     * pages (null, radio, anything else), or when saved already ends at that
     * page (same type and uri, so the user keeps their place there; with
     * othersPlaylist unknown, either kind of playlist page counts), except
     * that a last page without a title then takes name (and an album's
     * cover) in a copy. A known title is never replaced by none.
     */
    public static List<Node> atContext(List<Node> saved, String contextUri, String name, String coverUrl,
            Boolean othersPlaylist) {
        Node page = contextPage(contextUri, name, coverUrl, othersPlaylist);
        if (page == null) return saved;
        Node last = saved.get(saved.size() - 1);
        if (last.samePage(page) || (othersPlaylist == null && isPlaylist(last.type) && isPlaylist(page.type)
                && page.uri.equals(last.uri))) {
            boolean untitled = last.uri != null && (last.title == null || last.title.isEmpty());
            if (!untitled || page.title == null) return saved;
            List<Node> named = new ArrayList<Node>(saved);
            named.set(named.size() - 1, last.named(page.title, last.coverUrl != null ? last.coverUrl : page.coverUrl));
            return named;
        }
        List<Node> stack = new ArrayList<Node>();
        stack.add(Node.top());
        switch (page.type) {
            case ALBUM: stack.add(Node.of(Node.Type.ALBUMS)); break;
            case PLAYLIST:
            case OTHERS_PLAYLIST: stack.add(Node.of(Node.Type.PLAYLISTS)); break;
            case ARTIST: stack.add(Node.of(Node.Type.ARTISTS)); break;
            default: break; // LIKED sits right under TOP
        }
        stack.add(page);
        return stack;
    }

    /** The page contextUri opens (see atContext), or null if none. */
    static Node contextPage(String contextUri, String name, String coverUrl, Boolean othersPlaylist) {
        if (contextUri == null) return null;
        if (likedOwner(contextUri) != null) return Node.of(Node.Type.LIKED);
        String[] f = contextUri.split(":", -1);
        if (f.length != 3 || !"spotify".equals(f[0]) || f[2].isEmpty()) return null;
        if (name != null && name.isEmpty()) name = null;
        if ("album".equals(f[1])) return Node.album(contextUri, name, coverUrl);
        if ("playlist".equals(f[1])) return Node.playlist(contextUri, name, Boolean.TRUE.equals(othersPlaylist));
        if ("artist".equals(f[1])) return Node.artist(contextUri, name);
        return null;
    }

    /** The user ID in a liked songs URI (spotify:user:<id>:collection), or null for any other. */
    public static String likedOwner(String uri) {
        if (uri == null) return null;
        String[] f = uri.split(":", -1);
        if (f.length != 4 || !"spotify".equals(f[0]) || !"user".equals(f[1]) || f[2].isEmpty()
                || !"collection".equals(f[3])) {
            return null;
        }
        return f[2];
    }

    private static boolean isPlaylist(Node.Type type) {
        return type == Node.Type.PLAYLIST || type == Node.Type.OTHERS_PLAYLIST;
    }

    /**
     * The pages a long-press on item offers, in order: a track's album, then
     * the artists of a track or an album (each once); none for other rows.
     * Pages that are not an album or artist URI, and the page shown (here),
     * are left out.
     */
    public static List<Node> linksOf(Item item, Node here) {
        List<Node> out = new ArrayList<Node>();
        if (item.kind != Item.Kind.TRACK && item.kind != Item.Kind.ALBUM) return out;
        if (item.kind == Item.Kind.TRACK && item.album != null && item.album.uri.startsWith("spotify:album:")) {
            link(out, Node.album(item.album.uri, item.album.name, item.album.coverUrl), here);
        }
        for (Item.Ref a : item.artists) {
            if (a.uri.startsWith("spotify:artist:")) link(out, Node.artist(a.uri, a.name), here);
        }
        return out;
    }

    private static void link(List<Node> out, Node n, Node here) {
        if (n.samePage(here)) return;
        for (Node o : out) {
            if (o.samePage(n)) return;
        }
        out.add(n);
    }

    /** Replaces every node in stack that is from's page (Node.samePage) with to; whether there was one. */
    public static boolean replace(List<Node> stack, Node from, Node to) {
        boolean found = false;
        for (int i = 0; i < stack.size(); i++) {
            if (stack.get(i).samePage(from)) {
                stack.set(i, to);
                found = true;
            }
        }
        return found;
    }

    public static String encode(List<Node> stack) {
        StringBuilder sb = new StringBuilder();
        for (Node n : stack) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(n.type.name()).append('|').append(enc(n.uri)).append('|').append(enc(n.title))
                    .append('|').append(enc(n.coverUrl));
        }
        return sb.toString();
    }

    /** The stack in s, or just [TOP] when nothing in it can be read (also for null). */
    public static List<Node> decode(String s) {
        List<Node> stack = new ArrayList<Node>();
        stack.add(Node.top());
        if (s == null) return stack;
        String[] lines = s.split("\n");
        for (int i = 0; i < lines.length && stack.size() < MAX_DEPTH; i++) {
            Node n = parse(lines[i]);
            if (n == null) break;
            if (n.type == Node.Type.TOP) {
                if (i == 0) continue; // already there
                break;
            }
            stack.add(n);
        }
        return stack;
    }

    /** One line, or null if it cannot be read. */
    private static Node parse(String line) {
        String[] f = line.split("\\|", -1);
        if (f.length != 4) return null;
        Node.Type type;
        try {
            type = Node.Type.valueOf(f[0]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!type.hasArguments()) return new Node(type, null, null, null);
        String uri;
        String title;
        String cover;
        try {
            uri = dec(f[1]);
            title = dec(f[2]);
            cover = dec(f[3]);
        } catch (IllegalArgumentException e) {
            return null; // a broken %-escape
        }
        if (uri == null || uri.isEmpty()) return null;
        return new Node(type, uri, title, type == Node.Type.ALBUM ? cover : null);
    }

    private static String enc(String s) {
        return s == null ? NONE : Pkce.enc(s);
    }

    private static String dec(String s) {
        if (NONE.equals(s)) return null;
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
