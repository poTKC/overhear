package jp.tkc.st15iplayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps Soloist's volume (0..100, what the phone's Spotify slider shows) and
 * Android's STREAM_MUSIC index (0..max) in step. fakepulse plays at unity
 * gain, so the media volume is the only attenuation.
 *
 *   percent -> index: round(percent / 100 * max)
 *   index -> percent: round(index / max * 100)
 *
 * Soloist -> device: a new Soloist volume sets the media index (no system UI).
 * If Android keeps a lower index (safe headphone volume), Soloist is told
 * the index in effect, so the phone's slider shows what is really playing.
 * Device -> Soloist: a media index we did not set is sent as indexToPercent.
 *
 * Loop guard: synced is the index both sides last agreed on; a device index
 * equal to it is our own change coming back (or nothing new) and is not sent.
 * Our set_volume calls are echoed as volume_changed, possibly quantized (47
 * may come back as 46), so echoes are matched by index against the sends
 * still in flight and dropped; a stale echo can then never pull the device
 * back while the user is still pressing keys. A phone change that maps to
 * the index already set is not answered: the phone keeps its 45, the device
 * plays index 7 (47).
 *
 * Startup: the first logged-in state after a connect adopts the device:
 * Soloist gets indexToPercent(device index) (nothing when its volume already
 * maps to it). The media volume is what the user last chose on this phone,
 * while Soloist starts at its own default after every restart. A later
 * transfer or slider change from the phone wins as usual.
 *
 * Pure Java (host tests); main thread only. Times are monotonic milliseconds.
 */
final class VolumeSync {
    /** Applies the decisions; the service talks to AudioManager and SoloistClient. */
    interface Target {
        /**
         * Sets STREAM_MUSIC to index, without the system volume panel, and
         * returns the index now in effect: Android may refuse a raise (the
         * headphone safe-volume limit).
         */
        int setDeviceIndex(int index);

        /** Sends set_volume to Soloist. */
        void sendPercent(int percent);
    }

    /** A send whose echo has not arrived after this long is forgotten. */
    static final long ECHO_MS = 3000;

    private final int max;
    private final Target target;
    /** Logged in and the device volume adopted: syncing both ways. */
    private boolean ready;
    /** The index both sides agree on (-1 unknown). */
    private int synced = -1;
    /** The last Soloist volume seen (-1 unknown): only changes are acted on. */
    private int soloist = -1;
    /** Indices sent to Soloist whose echo is still due, oldest first. */
    private final List<Sent> inFlight = new ArrayList<Sent>();

    private static final class Sent {
        final int index;
        final long at;

        Sent(int index, long at) {
            this.index = index;
            this.at = at;
        }
    }

    VolumeSync(int max, Target target) {
        this.max = Math.max(1, max);
        this.target = target;
    }

    static int percentToIndex(int percent, int max) {
        if (max <= 0) return 0;
        int p = Math.max(0, Math.min(100, percent));
        return (int) Math.round(p * (double) max / 100.0);
    }

    static int indexToPercent(int index, int max) {
        if (max <= 0) return 0;
        int i = Math.max(0, Math.min(max, index));
        return (int) Math.round(i * 100.0 / max);
    }

    int max() {
        return max;
    }

    boolean isReady() {
        return ready;
    }

    /** Soloist disconnected (or the service stops): adopt again on the next login. */
    void reset() {
        ready = false;
        synced = -1;
        soloist = -1;
        inFlight.clear();
    }

    /**
     * A state snapshot from SoloistClient.
     *
     * @param loggedIn PlayerState.isLoggedIn()
     * @param percent  PlayerState.getVolume(), -1 if unknown
     * @param device   the current STREAM_MUSIC index
     */
    void onSoloist(boolean loggedIn, int percent, int device, long now) {
        if (!loggedIn) {
            if (ready) reset();
            return;
        }
        if (!ready) {
            ready = true;
            soloist = percent;
            synced = device;
            if (percent < 0 || percentToIndex(percent, max) != device) send(device, now);
            return;
        }
        // Every event brings a snapshot: act on changes only. (An unchanged
        // value is not taken as an echo: it may be an older one still in
        // effect, and consuming it could let a newer echo through as a change.)
        if (percent < 0 || percent == soloist) return;
        if (soloist < 0) {
            // Adopted before Soloist reported a volume (auth_state comes
            // before playback_state): this first value predates our send,
            // whose echo is still to come. Not a change from the phone.
            soloist = percent;
            return;
        }
        soloist = percent;
        int index = percentToIndex(percent, max);
        if (consumeEcho(index, now)) return;
        synced = index;
        if (device == index) return;
        int actual = target.setDeviceIndex(index);
        if (actual != index) {
            synced = actual;
            send(actual, now);
        }
    }

    /** The STREAM_MUSIC index changed (or may have): read it back with getStreamVolume. */
    void onDevice(int device, long now) {
        if (!ready || device == synced) return;
        synced = device;
        send(device, now);
    }

    private void send(int index, long now) {
        expire(now);
        inFlight.add(new Sent(index, now));
        target.sendPercent(indexToPercent(index, max));
    }

    /** True if index is the echo of a send in flight; drops it and every older one. */
    private boolean consumeEcho(int index, long now) {
        expire(now);
        for (int i = 0; i < inFlight.size(); i++) {
            if (inFlight.get(i).index == index) {
                inFlight.subList(0, i + 1).clear();
                return true;
            }
        }
        return false;
    }

    private void expire(long now) {
        while (!inFlight.isEmpty() && now - inFlight.get(0).at > ECHO_MS) inFlight.remove(0);
    }
}
