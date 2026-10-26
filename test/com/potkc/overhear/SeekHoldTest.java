package com.potkc.overhear;

import static org.junit.Assert.assertEquals;

import com.potkc.overhear.soloist.PlayerState;

import org.json.JSONObject;
import org.junit.Test;

public class SeekHoldTest {
    private final SeekHold hold = new SeekHold();

    private static PlayerState state(String status, long pos, long ts, double speed) throws Exception {
        PlayerState s = new PlayerState();
        s.apply(new JSONObject("{\"type\":\"playback_state\",\"status\":\"" + status + "\","
                + "\"item\":{\"uri\":\"spotify:track:x\",\"decorations\":{\"playback\":{\"duration_ms\":200000}}},"
                + "\"position\":{\"position_ms\":" + pos + ",\"timestamp_ms\":" + ts + ",\"speed\":" + speed + "}}"));
        return s;
    }

    @Test public void withoutASeekFollowsTheState() throws Exception {
        PlayerState s = state("playing", 10000, 1000, 1);
        assertEquals(15000, hold.position(s, 6000));
    }

    @Test public void showsTheTargetUntilANewerPositionArrives() throws Exception {
        PlayerState s = state("playing", 10000, 1000, 1);
        hold.seeked(120000, 2000, s.getPositionTimestampMs());
        // The old sync would say 11 s; the target advances while playing.
        assertEquals(120000, hold.position(s, 2000));
        assertEquals(121000, hold.position(s, 3000));
        // Soloist's position_sync after the seek wins.
        PlayerState synced = state("playing", 120500, 2500, 1);
        assertEquals(121000, hold.position(synced, 3000));
        // ... and the hold is over: later reads follow the state.
        assertEquals(121500, hold.position(synced, 3500));
    }

    @Test public void theTargetDoesNotAdvanceWhilePaused() throws Exception {
        PlayerState s = state("paused", 10000, 1000, 0);
        hold.seeked(50000, 2000, s.getPositionTimestampMs());
        assertEquals(50000, hold.position(s, 2500));
    }

    @Test public void theHoldExpires() throws Exception {
        PlayerState s = state("playing", 10000, 1000, 1);
        hold.seeked(120000, 2000, s.getPositionTimestampMs());
        assertEquals(10000 + SeekHold.HOLD_MS + 1000, hold.position(s, 2000 + SeekHold.HOLD_MS));
    }

    @Test public void theTargetIsClampedToTheDuration() throws Exception {
        PlayerState s = state("playing", 10000, 1000, 1);
        hold.seeked(199500, 2000, s.getPositionTimestampMs());
        assertEquals(200000, hold.position(s, 3000));
    }
}
