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
 * starts with TOP and keeps at most MAX_DEPTH nodes. Pure Java.
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
