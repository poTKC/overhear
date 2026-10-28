package com.potkc.overhear;

/**
 * How to play on this device while Soloist is not the active Connect device
 * ("Standby"), where Soloist's own play does nothing useful:
 *
 * - resume (▶, the notification, a media key): the last track again, in its
 *   context from that track (offset) when Spotify takes an offset there
 *   (albums, playlists, liked songs), else the track alone, at the last
 *   position (from the start of the track at or past its end). Without a
 *   track, the context from the top (albums, playlists, liked songs,
 *   artists).
 * - queueEntry (a tapped queue row): the entry in the context when it is a
 *   context row of such a context, else the track alone; from its start.
 *
 * WEB plays through the Web API (logged in), its failure falling back to
 * Soloist's play of soloistUri from the top (the context if any, else the
 * track), which Soloist accepts while inactive; SOLOIST goes there directly
 * (no Web API login; resume only). NONE: as before (Soloist's play while
 * active, Soloist not logged in, nothing to start from, or a queue row
 * without the Web API).
 *
 * A WEB play that fails may still have gone through (afterFailure): the
 * fallback runs at once only when the call certainly sent nothing, else
 * once more after a recheck, unless Soloist has become active.
 * Immutable. Pure Java.
 */
final class ResumePlan {
    enum Kind { NONE, WEB, SOLOIST }

    /** After a failed WEB play: Soloist's play now, after a recheck (fallBackAfterRecheck), or not at all. */
    enum Fallback { NOW, LATER, NEVER }

    /** Within this of the end, a resume starts the track again rather than its last second. */
    static final long END_MARGIN_MS = 1000;

    private static final ResumePlan NONE = new ResumePlan(Kind.NONE, null, null, 0, null);

    final Kind kind;
    /** WEB: the context to play, or null for the track alone. */
    final String contextUri;
    /** WEB: the track (the offset in contextUri), or null for the context from the top. */
    final String trackUri;
    /** WEB: where in the track to start. */
    final long positionMs;
    /** WEB's fallback and SOLOIST: what Soloist plays from the top. */
    final String soloistUri;

    private ResumePlan(Kind kind, String contextUri, String trackUri, long positionMs, String soloistUri) {
        this.kind = kind;
        this.contextUri = contextUri;
        this.trackUri = trackUri;
        this.positionMs = positionMs;
        this.soloistUri = soloistUri;
    }

    /**
     * soloistLoggedIn, active: Soloist is logged in, and the active device;
     * loggedIn: the Web API is; positionMs, durationMs: the last known
     * (durationMs 0 if unknown).
     */
    static ResumePlan resume(boolean soloistLoggedIn, boolean active, boolean loggedIn, String trackUri,
            String contextUri, long positionMs, long durationMs) {
        if (!soloistLoggedIn || active) return NONE;
        if (trackUri == null) {
            if (!(takesOffset(contextUri) || (contextUri != null && contextUri.startsWith("spotify:artist:")))) {
                return NONE;
            }
            return new ResumePlan(loggedIn ? Kind.WEB : Kind.SOLOIST, contextUri, null, 0, contextUri);
        }
        long p = Math.max(0, positionMs);
        if (durationMs > 0 && p >= durationMs - END_MARGIN_MS) p = 0;
        String soloist = contextUri != null && contextUri.startsWith("spotify:") ? contextUri : trackUri;
        return new ResumePlan(loggedIn ? Kind.WEB : Kind.SOLOIST, takesOffset(contextUri) ? contextUri : null,
                trackUri, p, soloist);
    }

    /**
     * A WEB play failed: sentNothing (no login, device not listed: nothing
     * reached Spotify) falls back now; otherwise the play may have gone
     * through: none if Soloist is already active, else LATER.
     */
    static Fallback afterFailure(boolean sentNothing, boolean activeNow) {
        if (sentNothing) return Fallback.NOW;
        return activeNow ? Fallback.NEVER : Fallback.LATER;
    }

    /** The recheck after Fallback.LATER: fall back unless Soloist became active (the play went through). */
    static boolean fallBackAfterRecheck(boolean activeNow) {
        return !activeNow;
    }

    /** A tapped queue row (its uri and source: "context", "queue", "autoplay" ...) while inactive. */
    static ResumePlan queueEntry(boolean loggedIn, String uri, String source, String contextUri) {
        if (!loggedIn || uri == null) return NONE;
        String context = "context".equals(source) && takesOffset(contextUri) ? contextUri : null;
        return new ResumePlan(Kind.WEB, context, uri, 0, context != null ? context : uri);
    }

    /** Contexts the Web API plays from a given track: albums, playlists, the liked songs. */
    static boolean takesOffset(String contextUri) {
        return contextUri != null && (contextUri.startsWith("spotify:album:") || contextUri.startsWith("spotify:playlist:")
                || (contextUri.startsWith("spotify:user:") && contextUri.endsWith(":collection")));
    }
}
