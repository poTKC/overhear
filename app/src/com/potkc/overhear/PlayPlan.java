package com.potkc.overhear;

/**
 * What a library play (LibraryActivity) does about shuffle: whether to set
 * it through the Web API before the play, whether to play from the first
 * track, and, CHECK_MS after the play, whether Soloist should still set it.
 *
 * ⇄ always makes the shuffle call: Soloist's own flag can be stale (it was
 * seen off while Spotify's was on after a restart of Soloist, and the other
 * way round would start the context in order). ▶ skips the call when
 * Soloist already has shuffle off, since it plays from the first track
 * (offset.position 0) whatever the shuffle. The check only fixes what plays
 * next: a play that started in order keeps its first track, so ⇄ does not
 * always start at a random track.
 *
 * Pure Java.
 */
final class PlayPlan {
    /** After the play, when the check runs. */
    static final long CHECK_MS = 2000;

    /** The wanted shuffle; null leaves it as it is. */
    final Boolean shuffle;
    /** Set shuffle through the Web API before the play. */
    final boolean callShuffle;
    /** Play the context from its first track (offset.position 0). */
    final boolean fromFirst;
    /** Check CHECK_MS after the play. */
    final boolean checks;

    private PlayPlan(Boolean shuffle, boolean callShuffle, boolean fromFirst, boolean checks) {
        this.shuffle = shuffle;
        this.callShuffle = callShuffle;
        this.fromFirst = fromFirst;
        this.checks = checks;
    }

    /**
     * A play of contextUri from trackUri (null: from the top), or trackUri
     * alone; shuffle as wanted (null: left as it is). soloistShuffle is
     * Soloist's shuffle, null while it is not connected.
     */
    static PlayPlan of(String contextUri, String trackUri, Boolean shuffle, Boolean soloistShuffle) {
        boolean fromFirst = Boolean.FALSE.equals(shuffle) && contextUri != null && trackUri == null;
        boolean call = shuffle != null && (shuffle || !fromFirst || !Boolean.FALSE.equals(soloistShuffle));
        return new PlayPlan(shuffle, call, fromFirst, shuffle != null && contextUri != null);
    }

    /**
     * At the check: whether Soloist should set the wanted shuffle. Not if a
     * later library play started (generation is this play's, latest the
     * newest), a control was pressed or a queue jump started after the play
     * (lastControlMs after playMs, same clock), a jump still runs, Soloist is
     * not connected or plays another context, or it already has the shuffle.
     */
    boolean fixNow(String contextUri, int generation, int latest, long playMs, long lastControlMs,
            boolean jumping, boolean connected, String soloistContext, boolean soloistShuffle) {
        if (!checks || generation != latest || lastControlMs > playMs || jumping || !connected) return false;
        return contextUri.equals(soloistContext) && soloistShuffle != shuffle;
    }
}
