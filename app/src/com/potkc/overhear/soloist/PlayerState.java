package com.potkc.overhear.soloist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What Soloist reports over its WebSocket API, folded from its events by
 * apply(). Pure Java (org.json only, the subset Android 4.3 has) so it runs
 * in the host tests.
 *
 * Mutable and not thread-safe: one thread applies events and hands copy()
 * snapshots to others. Lists are immutable, so copies share them.
 *
 * Shapes follow events captured from Soloist 1.3.10 (test/fixtures); every
 * metadata field may be missing. options.repeat is "off", "context" or
 * "track". Positions are {position_ms, timestamp_ms, speed} where
 * timestamp_ms is the device's wall clock. Soloist sends
 * spotify:meta:node_rules_placeholder rows right after play; they are
 * dropped from the queue.
 */
public final class PlayerState {
    public enum Status {
        IDLE, PLAYING, PAUSED, BUFFERING;

        static Status parse(String s) {
            if ("playing".equals(s)) return PLAYING;
            if ("paused".equals(s)) return PAUSED;
            if ("buffering".equals(s)) return BUFFERING;
            return IDLE;
        }
    }

    public enum Repeat {
        OFF("off"), CONTEXT("context"), TRACK("track");

        private final String wire;

        Repeat(String wire) {
            this.wire = wire;
        }

        public String wireName() {
            return wire;
        }

        /** Unknown or missing values read as OFF. */
        public static Repeat parse(String s) {
            for (Repeat r : values()) {
                if (r.wire.equals(s)) return r;
            }
            return OFF;
        }
    }

    /** Metadata of one track (or episode), from an event's item. */
    public static class Track {
        public final String uri;
        public final String title;
        public final List<String> artists;
        public final String album;
        /** The largest useful cover (640 px): large, else default, else any. */
        public final String coverUrl;
        /** About 300 px: default, else large, else any. */
        public final String mediumCoverUrl;
        /** About 64 px: small, else default, else large, else any. */
        public final String smallCoverUrl;
        public final long durationMs;

        Track(JSONObject item) {
            JSONObject d = item.optJSONObject("decorations");
            uri = str(item, "uri");
            title = name(d);
            List<String> a = new ArrayList<String>();
            JSONArray creators = d == null ? null : d.optJSONArray("creators");
            if (creators != null) {
                for (int i = 0; i < creators.length(); i++) {
                    JSONObject c = creators.optJSONObject(i);
                    String n = name(entityDecorations(c));
                    if (n != null) a.add(n);
                }
            }
            artists = Collections.unmodifiableList(a);
            JSONObject parent = d == null ? null : d.optJSONObject("parent");
            album = name(entityDecorations(parent));
            coverUrl = cover(d);
            mediumCoverUrl = cover(d, "default", "large");
            smallCoverUrl = cover(d, "small", "default", "large");
            JSONObject playback = d == null ? null : d.optJSONObject("playback");
            durationMs = playback == null ? 0 : Math.max(0, playback.optLong("duration_ms", 0));
        }
    }

    /** One upcoming entry of queue_changed. */
    public static final class QueueItem extends Track {
        public final String uid;
        /** "context", "queue", "autoplay", ... */
        public final String source;

        QueueItem(JSONObject entry, JSONObject item) {
            super(item);
            uid = str(entry, "uid");
            source = str(entry, "source");
        }
    }

    private static final Track NO_TRACK = new Track(new JSONObject());

    private boolean loggedIn;
    private boolean active;
    private String deviceName;
    private Status status = Status.IDLE;
    private Track track = NO_TRACK;
    private String contextUri;
    private String contextName;
    private long positionMs;
    private long positionTimestampMs;
    private double speed;
    private int volume = -1;
    private boolean shuffle;
    private Repeat repeat = Repeat.OFF;
    private List<QueueItem> queue = Collections.emptyList();
    /** playback_state's available_actions (immutable), or null until one has been seen. */
    private Set<String> actions;
    /** track_changed events folded so far (not playback_state's item). */
    private int trackChanges;
    /** trackChanges when the queue last arrived. */
    private int queueTrackChanges;

    public PlayerState copy() {
        PlayerState c = new PlayerState();
        c.loggedIn = loggedIn;
        c.active = active;
        c.deviceName = deviceName;
        c.status = status;
        c.track = track;
        c.contextUri = contextUri;
        c.contextName = contextName;
        c.positionMs = positionMs;
        c.positionTimestampMs = positionTimestampMs;
        c.speed = speed;
        c.volume = volume;
        c.shuffle = shuffle;
        c.repeat = repeat;
        c.queue = queue;
        c.actions = actions;
        c.trackChanges = trackChanges;
        c.queueTrackChanges = queueTrackChanges;
        return c;
    }

    /**
     * Folds one server event into the state. Returns false for messages that
     * carry no state (command_result, error, unknown types).
     */
    public boolean apply(JSONObject e) {
        String type = str(e, "type");
        if ("auth_state".equals(type)) {
            loggedIn = e.optBoolean("logged_in", loggedIn);
            active = e.optBoolean("is_active", active);
            if (e.has("device_name")) deviceName = str(e, "device_name");
        } else if ("playback_state".equals(type)) {
            status = Status.parse(str(e, "status"));
            setTrack(e.optJSONObject("item"));
            setContext(e.optJSONObject("context"));
            setPosition(e.optJSONObject("position"));
            volume = e.optInt("volume", volume);
            active = e.optBoolean("is_active", active);
            setOptions(e.optJSONObject("options"));
            setActions(e.optJSONObject("available_actions"));
        } else if ("track_changed".equals(type)) {
            setTrack(e.optJSONObject("item"));
            trackChanges++;
        } else if ("playback_changed".equals(type)) {
            if (e.has("status")) status = Status.parse(str(e, "status"));
        } else if ("volume_changed".equals(type)) {
            volume = e.optInt("volume", volume);
        } else if ("device_changed".equals(type)) {
            active = e.optBoolean("is_active", active);
            if (e.has("device_name")) deviceName = str(e, "device_name");
        } else if ("context_changed".equals(type)) {
            setContext(e.optJSONObject("context"));
        } else if ("options_changed".equals(type)) {
            setOptions(e.optJSONObject("options"));
        } else if ("position_sync".equals(type)) {
            setPosition(e.optJSONObject("position"));
        } else if ("queue_changed".equals(type)) {
            setQueue(e.optJSONArray("upcoming"));
            queueTrackChanges = trackChanges;
        } else {
            return false;
        }
        return true;
    }

    private void setTrack(JSONObject item) {
        Track t = item == null ? NO_TRACK : new Track(item);
        // The same URI again (e.g. playback_state after track_changed, or a
        // metadata refresh) keeps the position; skipping back to the start
        // of the same track comes with its own position_sync.
        if (t.uri == null || !t.uri.equals(track.uri)) {
            // A new track: the old position means nothing until the next sync.
            positionMs = 0;
            positionTimestampMs = 0;
            speed = 0;
        }
        track = t;
    }

    private void setContext(JSONObject c) {
        contextUri = c == null ? null : str(c, "uri");
        contextName = c == null ? null : name(c.optJSONObject("decorations"));
    }

    private void setPosition(JSONObject p) {
        if (p == null) return;
        positionMs = Math.max(0, p.optLong("position_ms", 0));
        positionTimestampMs = p.optLong("timestamp_ms", 0);
        speed = p.optDouble("speed", 0);
        if (Double.isNaN(speed) || speed < 0) speed = 0;
    }

    /**
     * Only playback_state carries available_actions, as the whole list (e.g.
     * "pause", "seek", "skip_next", "shuffle", "set_repeat"); an event without
     * it keeps the last one.
     */
    private void setActions(JSONObject a) {
        if (a == null) return;
        Set<String> set = new HashSet<String>();
        Iterator<?> it = a.keys();
        while (it.hasNext()) set.add(String.valueOf(it.next()));
        actions = Collections.unmodifiableSet(set);
    }

    private void setOptions(JSONObject o) {
        if (o == null) return;
        shuffle = o.optBoolean("shuffle", shuffle);
        if (o.has("repeat")) repeat = Repeat.parse(str(o, "repeat"));
    }

    private void setQueue(JSONArray upcoming) {
        List<QueueItem> q = new ArrayList<QueueItem>();
        if (upcoming != null) {
            for (int i = 0; i < upcoming.length(); i++) {
                JSONObject entry = upcoming.optJSONObject(i);
                JSONObject item = entry == null ? null : entry.optJSONObject("item");
                if (item == null) continue;
                String uri = str(item, "uri");
                if (uri != null && uri.startsWith("spotify:meta:")) continue;
                q.add(new QueueItem(entry, item));
            }
        }
        queue = Collections.unmodifiableList(q);
    }

    /**
     * The position at wall-clock time nowMs, advanced from the last sync by
     * speed and clamped to [0, duration] (only below when the duration is
     * unknown). It does not advance while paused or idle, even if the
     * speed-0 position_sync has not arrived yet.
     */
    public long positionAt(long nowMs) {
        long p = positionMs;
        boolean advancing = speed > 0 && status != Status.PAUSED && status != Status.IDLE;
        if (positionTimestampMs > 0 && advancing) {
            p += (long) ((nowMs - positionTimestampMs) * speed);
        }
        if (p < 0) p = 0;
        if (track.durationMs > 0 && p > track.durationMs) p = track.durationMs;
        return p;
    }

    public boolean isLoggedIn() { return loggedIn; }

    /** Whether this device is the active Spotify Connect device. */
    public boolean isActive() { return active; }

    public String getDeviceName() { return deviceName; }

    public Status getStatus() { return status; }

    public String getTrackUri() { return track.uri; }

    public String getTitle() { return track.title; }

    public List<String> getArtists() { return track.artists; }

    public String getAlbum() { return track.album; }

    public String getCoverUrl() { return track.coverUrl; }

    /** A cover of about 300 px (what a 240 px view needs), or null. */
    public String getMediumCoverUrl() { return track.mediumCoverUrl; }

    /** A cover of about 64 px (for a list row), or null. */
    public String getSmallCoverUrl() { return track.smallCoverUrl; }

    /** 0 if unknown. */
    public long getDurationMs() { return track.durationMs; }

    public String getContextUri() { return contextUri; }

    public String getContextName() { return contextName; }

    public long getPositionMs() { return positionMs; }

    /** Device wall clock of getPositionMs(); 0 if never synced. */
    public long getPositionTimestampMs() { return positionTimestampMs; }

    /** 0 while not advancing. */
    public double getSpeed() { return speed; }

    /** 0..100, or -1 if unknown. */
    public int getVolume() { return volume; }

    /**
     * Soloist lists "shuffle" in available_actions where set_shuffle works
     * (not on radio and some other contexts, where it is ignored). True if no
     * list has been seen.
     */
    public boolean canShuffle() {
        return actions == null || actions.contains("shuffle");
    }

    /**
     * Likewise "set_repeat": Soloist 1.3.x has one action for both repeat
     * modes, so there is no context-only or track-only case to cycle around.
     */
    public boolean canRepeat() {
        return actions == null || actions.contains("set_repeat");
    }

    public boolean isShuffle() { return shuffle; }

    public Repeat getRepeat() { return repeat; }

    /**
     * How many track_changed events this state has folded. Counts every
     * change, also to the same URI (a duplicate in the queue), which the URI
     * alone cannot show; a new connection starts again at 0.
     */
    public int getTrackChanges() { return trackChanges; }

    /** Upcoming tracks, unmodifiable. A new list on every queue_changed. */
    public List<QueueItem> getQueue() { return queue; }

    /**
     * The track changed after the queue last arrived: until the queue asked
     * for on track_changed comes, it still lists the now current track first.
     */
    public boolean isQueueStale() { return trackChanges != queueTrackChanges; }

    // --- JSON helpers (Android's optString turns JSON null into "null") ---

    static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        Object v = o.opt(key);
        return v == null ? null : v.toString();
    }

    /** decorations.identity.name */
    static String name(JSONObject decorations) {
        JSONObject id = decorations == null ? null : decorations.optJSONObject("identity");
        return str(id, "name");
    }

    /** x.entity, for creators[] and parent. */
    static JSONObject entity(JSONObject x) {
        return x == null ? null : x.optJSONObject("entity");
    }

    /** x.entity.decorations, for creators[] and parent. */
    static JSONObject entityDecorations(JSONObject x) {
        JSONObject entity = entity(x);
        return entity == null ? null : entity.optJSONObject("decorations");
    }

    /** visual_identity.cover: large, else default, else the first one. */
    static String cover(JSONObject decorations) {
        return cover(decorations, "large", "default");
    }

    /** visual_identity.cover: the first of the sizes found, in order, else the first cover. */
    static String cover(JSONObject decorations, String... sizes) {
        JSONObject vi = decorations == null ? null : decorations.optJSONObject("visual_identity");
        JSONArray covers = vi == null ? null : vi.optJSONArray("cover");
        if (covers == null) return null;
        String[] found = new String[sizes.length];
        String any = null;
        for (int i = 0; i < covers.length(); i++) {
            JSONObject c = covers.optJSONObject(i);
            String url = str(c, "url");
            if (url == null) continue;
            String size = str(c, "size");
            for (int k = 0; k < sizes.length; k++) {
                if (sizes[k].equals(size) && found[k] == null) found[k] = url;
            }
            if (any == null) any = url;
        }
        for (String f : found) {
            if (f != null) return f;
        }
        return any;
    }
}
