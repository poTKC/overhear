package jp.tkc.st15iplayer.soloist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 * timestamp_ms is the device's wall clock.
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
        public final String coverUrl;
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
            album = name(entityDecorations(d == null ? null : d.optJSONObject("parent")));
            coverUrl = cover(d);
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
        } else if ("track_changed".equals(type)) {
            setTrack(e.optJSONObject("item"));
        } else if ("playback_changed".equals(type)) {
            status = Status.parse(str(e, "status"));
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
        } else {
            return false;
        }
        return true;
    }

    private void setTrack(JSONObject item) {
        Track t = item == null ? NO_TRACK : new Track(item);
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
                if (item != null) q.add(new QueueItem(entry, item));
            }
        }
        queue = Collections.unmodifiableList(q);
    }

    /**
     * The position at wall-clock time nowMs, advanced from the last sync by
     * speed and clamped to [0, duration] (only below when the duration is
     * unknown).
     */
    public long positionAt(long nowMs) {
        long p = positionMs;
        if (positionTimestampMs > 0 && speed > 0) {
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

    public boolean isShuffle() { return shuffle; }

    public Repeat getRepeat() { return repeat; }

    /** Upcoming tracks, unmodifiable. */
    public List<QueueItem> getQueue() { return queue; }

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

    /** x.entity.decorations, for creators[] and parent. */
    static JSONObject entityDecorations(JSONObject x) {
        JSONObject entity = x == null ? null : x.optJSONObject("entity");
        return entity == null ? null : entity.optJSONObject("decorations");
    }

    /** visual_identity.cover: large, else default, else the first one. */
    static String cover(JSONObject decorations) {
        JSONObject vi = decorations == null ? null : decorations.optJSONObject("visual_identity");
        JSONArray covers = vi == null ? null : vi.optJSONArray("cover");
        if (covers == null) return null;
        String large = null, dflt = null, any = null;
        for (int i = 0; i < covers.length(); i++) {
            JSONObject c = covers.optJSONObject(i);
            String url = str(c, "url");
            if (url == null) continue;
            String size = str(c, "size");
            if ("large".equals(size) && large == null) large = url;
            else if ("default".equals(size) && dflt == null) dflt = url;
            if (any == null) any = url;
        }
        return large != null ? large : dflt != null ? dflt : any;
    }
}
