package com.potkc.overhear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.potkc.overhear.soloist.PlayerState;
import com.potkc.overhear.soloist.PlayerStateTest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class QueueJumpTest {
    private static final long TIMEOUT = 5000;

    private long now = 1000;
    private final List<String> fx = new ArrayList<String>();
    private Boolean ok;
    private String why;
    private PlayerState state;
    /** Soloist's upcoming queue as "uid=uri" pairs; uid "" means none. */
    private final List<String[]> upcoming = new ArrayList<String[]>();
    /** Skips soloistSkips has carried out. */
    private int simulated;

    private final QueueJump.Clock clock = new QueueJump.Clock() {
        @Override public long nowMs() {
            return now;
        }
    };

    private final QueueJump.Effects effects = new QueueJump.Effects() {
        @Override public void skip() { fx.add("skip"); }
        @Override public void mute() { fx.add("mute"); }
        @Override public void unmute() { fx.add("unmute"); }
        @Override public void play() { fx.add("play"); }
        @Override public void finished(boolean success, String reason) {
            if (ok != null) throw new AssertionError("finished twice");
            fx.add("finished");
            ok = success;
            why = reason;
        }
    };

    @Before public void setUp() throws Exception {
        state = new PlayerState();
        apply("{\"type\":\"auth_state\",\"logged_in\":true,\"is_active\":true}");
        apply("{\"type\":\"playback_state\",\"status\":\"playing\",\"is_active\":true,"
                + "\"item\":{\"uri\":\"spotify:track:cur\"},"
                + "\"position\":{\"position_ms\":1000,\"timestamp_ms\":1,\"speed\":1}}");
        setQueue("u0=a", "u1=b", "u2=c", "u3=d", "u4=e");
    }

    // --- a simulated Soloist ---

    private PlayerState apply(String json) throws Exception {
        state.apply(new JSONObject(json));
        return state.copy();
    }

    private PlayerState setQueue(String... entries) throws Exception {
        upcoming.clear();
        JSONArray a = new JSONArray();
        for (String e : entries) {
            String[] p = e.split("=");
            upcoming.add(p);
            JSONObject entry = new JSONObject().put("source", "context")
                    .put("item", new JSONObject().put("uri", "spotify:track:" + p[1]));
            if (!p[0].isEmpty()) entry.put("uid", p[0]);
            a.put(entry);
        }
        return apply(new JSONObject().put("type", "queue_changed").put("upcoming", a).toString());
    }

    private PlayerState trackChanged(String uri) throws Exception {
        return apply("{\"type\":\"track_changed\",\"item\":{\"uri\":\"spotify:track:" + uri + "\"}}");
    }

    private PlayerState status(String s) throws Exception {
        return apply("{\"type\":\"playback_changed\",\"status\":\"" + s + "\"}");
    }

    private PlayerState sync(int speed) throws Exception {
        return apply("{\"type\":\"position_sync\",\"position\":{\"position_ms\":0,\"timestamp_ms\":"
                + now + ",\"speed\":" + speed + "}}");
    }

    /**
     * One skip as Soloist 1.3.10 does it (test/fixtures, and traced on the
     * device): track_changed, buffering, queue_changed, speed 0, then playing
     * at speed 1 (it plays even when it was paused). The states go to j.
     */
    private void soloistSkips(QueueJump j) throws Exception {
        simulated++;
        String[] next = upcoming.remove(0);
        j.onState(trackChanged(next[1]));
        j.onState(status("buffering"));
        List<String> rest = new ArrayList<String>();
        for (String[] e : upcoming) rest.add(e[0] + "=" + e[1]);
        j.onState(setQueue(rest.toArray(new String[0])));
        j.onState(sync(0));
        j.onState(status("playing"));
        j.onState(sync(1));
    }

    private QueueJump start(int index) {
        PlayerState.QueueItem item = state.getQueue().get(index);
        QueueJump j = new QueueJump(index, item.uid, item.uri, clock, TIMEOUT, effects);
        assertTrue(j.start(state.copy()));
        return j;
    }

    /** Runs Soloist for each skip the jump sends. */
    private void drive(QueueJump j) throws Exception {
        while (j.isRunning() && count("skip") > simulated) soloistSkips(j);
    }

    private int count(String effect) {
        int n = 0;
        for (String f : fx) if (f.equals(effect)) n++;
        return n;
    }

    // --- tests ---

    @Test public void reachesTheThirdEntryInThreeSkipsWhilePlaying() throws Exception {
        QueueJump j = start(2);
        assertEquals(Arrays.asList("mute", "skip"), fx);
        drive(j);
        assertEquals(Arrays.asList("mute", "skip", "skip", "skip", "unmute", "finished"), fx);
        assertTrue(ok);
        assertEquals("spotify:track:c", state.getTrackUri());
        assertFalse(j.isRunning());
        assertEquals(3, j.getSteps());
    }

    @Test public void theNextEntryNeedsOneSkip() throws Exception {
        QueueJump j = start(0);
        drive(j);
        assertEquals(1, count("skip"));
        assertTrue(ok);
        assertEquals("spotify:track:a", state.getTrackUri());
    }

    @Test public void stepsOneSkipAtATime() throws Exception {
        QueueJump j = start(2);
        j.onState(status("buffering"));
        j.onState(sync(0));
        assertEquals("no second skip before the track changed", 1, count("skip"));
        j.onState(trackChanged("a"));
        assertEquals("nor before the queue moved", 1, count("skip"));
        j.onState(setQueue("u1=b", "u2=c", "u3=d"));
        assertEquals(2, count("skip"));
    }

    @Test public void staysMutedUntilTheTargetPlays() throws Exception {
        QueueJump j = start(0);
        j.onState(trackChanged("a")); // still "playing" from the old track
        assertEquals(0, count("unmute"));
        j.onState(status("buffering"));
        j.onState(sync(0));
        j.onState(status("playing"));
        assertEquals("speed 0 still", 0, count("unmute"));
        j.onState(sync(1));
        assertEquals(1, count("unmute"));
        assertTrue(ok);
    }

    @Test public void startedPausedEndsPlayingWithPlaySentAndUnmuted() throws Exception {
        j0Paused();
        QueueJump j = start(2);
        assertFalse(j.wasPlaying());
        drive(j); // Soloist plays on each skip
        assertEquals(Arrays.asList("mute", "skip", "skip", "skip", "play", "unmute", "finished"), fx);
        assertTrue(ok);
        assertEquals("spotify:track:c", state.getTrackUri());
        assertEquals(PlayerState.Status.PLAYING, state.getStatus());
        assertFalse(j.isRunning());
    }

    @Test public void startedPausedSendsPlayOnArrivalAndUnmutesOnceItPlays() throws Exception {
        j0Paused();
        QueueJump j = start(0);
        j.onState(trackChanged("a")); // still "paused" in the snapshot
        assertEquals(Arrays.asList("mute", "skip", "play"), fx);
        j.onState(status("buffering"));
        j.onState(sync(0));
        assertEquals("muted until the target plays", 0, count("unmute"));
        j.onState(status("playing"));
        j.onState(sync(1));
        assertEquals(Arrays.asList("mute", "skip", "play", "unmute", "finished"), fx);
        assertTrue(ok);
    }

    @Test public void startedPausedUnmutesAfterTheTimeoutIfNoPlayingIsSeen() throws Exception {
        j0Paused();
        QueueJump j = start(0);
        j.onState(trackChanged("a"));
        assertEquals(1, count("play"));
        now += TIMEOUT - 1;
        j.onTimer();
        assertTrue(j.isRunning());
        now += 1;
        j.onTimer();
        assertEquals(Arrays.asList("mute", "skip", "play", "unmute", "finished"), fx);
        assertTrue("the target was reached", ok);
    }

    @Test public void startedPlayingSendsNoPlay() throws Exception {
        QueueJump j = start(1);
        drive(j);
        assertEquals(0, count("play"));
        assertTrue(ok);
    }

    @Test public void playsIfTheTargetArrivesPaused() throws Exception {
        QueueJump j = start(0);
        state.apply(new JSONObject("{\"type\":\"playback_changed\",\"status\":\"paused\"}"));
        j.onState(trackChanged("a")); // both in one state
        assertEquals(Arrays.asList("mute", "skip", "play"), fx);
        j.onState(status("buffering"));
        j.onState(status("playing"));
        j.onState(sync(1));
        assertTrue(ok);
    }

    @Test public void abortsWhenPausedBeforeTheFirstSkipLands() throws Exception {
        QueueJump j = start(0);
        j.onState(status("paused")); // paused elsewhere before the skip landed
        assertFalse(j.isRunning());
        assertFalse(ok);
        assertEquals("paused elsewhere", why);
        assertEquals(0, count("play"));
    }

    @Test public void duplicatesAreCountedNotMatchedEarly() throws Exception {
        setQueue("u0=x", "u1=y", "u2=x", "u3=z");
        QueueJump j = start(2); // the second x
        drive(j);
        assertEquals(3, count("skip"));
        assertTrue(ok);
        assertEquals("spotify:track:x", state.getTrackUri());
    }

    @Test public void theSameTrackAgainCountsAsAStep() throws Exception {
        // The current track repeated next in the queue: same URI, still a change.
        setQueue("u0=cur", "u1=b");
        QueueJump j = start(1);
        drive(j);
        assertEquals(2, count("skip"));
        assertTrue(ok);
        assertEquals("spotify:track:b", state.getTrackUri());
    }

    @Test public void duplicatesWithoutUidsAreFoundByPlace() throws Exception {
        setQueue("=x", "=x", "=x");
        PlayerState.QueueItem item = state.getQueue().get(2);
        QueueJump j = new QueueJump(2, null, item.uri, clock, TIMEOUT, effects);
        assertTrue(j.start(state.copy()));
        drive(j);
        assertEquals(3, count("skip"));
        assertTrue(ok);
    }

    @Test public void anEntryAddedInFrontMeansOneMoreSkip() throws Exception {
        QueueJump j = start(2);
        j.onState(trackChanged("a"));
        j.onState(setQueue("q=added", "u1=b", "u2=c"));
        assertEquals(2, count("skip"));
        soloistSkips(j);
        soloistSkips(j);
        soloistSkips(j);
        assertEquals(4, count("skip"));
        assertTrue(ok);
        assertEquals("spotify:track:c", state.getTrackUri());
    }

    @Test public void abortsWhenTheTargetLeavesTheQueue() throws Exception {
        QueueJump j = start(2);
        j.onState(trackChanged("a"));
        j.onState(setQueue("v1=other", "v2=list"));
        assertEquals(Arrays.asList("mute", "skip", "unmute", "finished"), fx);
        assertFalse(ok);
        assertEquals("the target left the queue", why);
    }

    @Test public void abortsWhenLandingOnAnotherTrack() throws Exception {
        QueueJump j = start(0);
        j.onState(trackChanged("surprise"));
        assertFalse(j.isRunning());
        assertFalse(ok);
        assertEquals(1, count("unmute"));
    }

    @Test public void abortsOnTimeoutAndUnmutes() throws Exception {
        QueueJump j = start(2);
        soloistSkips(j);
        assertEquals(2, count("skip"));
        assertEquals(TIMEOUT, j.msUntilDeadline());
        now += TIMEOUT - 1;
        j.onTimer();
        assertTrue(j.isRunning());
        now += 1;
        assertEquals(0, j.msUntilDeadline());
        j.onTimer();
        assertFalse(j.isRunning());
        assertFalse(ok);
        assertEquals(Arrays.asList("mute", "skip", "skip", "unmute", "finished"), fx);
        assertTrue(why, why.contains("timed out after 1 of 3"));
        assertEquals(-1, j.msUntilDeadline());
    }

    @Test public void eachStepGetsItsOwnTimeout() throws Exception {
        QueueJump j = start(2);
        now += TIMEOUT - 10;
        soloistSkips(j);
        now += TIMEOUT - 10;
        j.onTimer();
        assertTrue(j.isRunning());
    }

    @Test public void timeoutWhenStartedPausedOnlyUnmutes() throws Exception {
        j0Paused();
        QueueJump j = start(2);
        soloistSkips(j); // now playing, muted
        now += TIMEOUT;
        j.onTimer();
        assertEquals("no play forced on abort", Arrays.asList("mute", "skip", "skip", "unmute", "finished"), fx);
        assertFalse(ok);
        assertTrue(why, why.contains("timed out"));
    }

    @Test public void abortsOnDisconnect() throws Exception {
        QueueJump j = start(2);
        soloistSkips(j);
        j.onDisconnected();
        assertEquals(Arrays.asList("mute", "skip", "skip", "unmute", "finished"), fx);
        assertFalse(ok);
        assertEquals("disconnected", why);
        j.onDisconnected();
        j.onTimer();
        j.onState(state.copy());
        assertEquals("nothing after the end", 5, fx.size());
    }

    @Test public void abortsOnUserInputWithoutPlaying() throws Exception {
        j0Paused();
        QueueJump j = start(2);
        soloistSkips(j);
        j.onUserInput();
        assertEquals("the user's command wins", Arrays.asList("mute", "skip", "skip", "unmute", "finished"), fx);
        assertFalse(ok);
        assertEquals("user input", why);
    }

    @Test public void userInputWhileSettlingKeepsTheResult() throws Exception {
        j0Paused();
        QueueJump j = start(0);
        j.onState(trackChanged("a")); // on the target, not playing yet
        assertTrue(j.isRunning());
        j.onUserInput();
        assertTrue(ok);
        assertEquals(1, count("unmute"));
    }

    @Test public void abortsWhenSomeoneElseSkips() throws Exception {
        QueueJump j = start(2);
        soloistSkips(j); // second skip out
        j.onState(trackChanged("b"));
        j.onState(trackChanged("c")); // a change no skip asked for
        assertFalse(j.isRunning());
        assertFalse(ok);
    }

    @Test public void abortsWhenPausedElsewhere() throws Exception {
        QueueJump j = start(2);
        soloistSkips(j);
        j.onState(status("paused"));
        assertFalse(j.isRunning());
        assertEquals(0, count("play"));
        assertEquals(1, count("unmute"));
    }

    @Test public void abortsWhenNoLongerActive() throws Exception {
        QueueJump j = start(2);
        j.onState(apply("{\"type\":\"device_changed\",\"is_active\":false}"));
        assertFalse(j.isRunning());
        assertFalse(ok);
        assertEquals(1, count("unmute"));
    }

    @Test public void aNewConnectionsStateAborts() throws Exception {
        QueueJump j = start(2);
        PlayerState fresh = new PlayerState();
        fresh.apply(new JSONObject("{\"type\":\"auth_state\",\"logged_in\":true,\"is_active\":true}"));
        soloistSkips(j);
        j.onState(fresh); // track changes back at 0
        assertFalse(j.isRunning());
    }

    @Test public void refusesAStaleEntry() throws Exception {
        PlayerState.QueueItem item = state.getQueue().get(2);
        QueueJump j = new QueueJump(3, item.uid, item.uri, clock, TIMEOUT, effects);
        assertFalse(j.start(state.copy()));
        assertTrue(fx.isEmpty());
        j = new QueueJump(9, item.uid, item.uri, clock, TIMEOUT, effects);
        assertFalse(j.start(state.copy()));
        assertTrue(fx.isEmpty());
    }

    @Test public void refusesWhenNotActive() throws Exception {
        apply("{\"type\":\"device_changed\",\"is_active\":false}");
        PlayerState.QueueItem item = state.getQueue().get(0);
        QueueJump j = new QueueJump(0, item.uid, item.uri, clock, TIMEOUT, effects);
        assertFalse(j.start(state.copy()));
        assertTrue(fx.isEmpty());
    }

    @Test public void realFixturesFeedIt() throws Exception {
        // The captured queue_changed and track_changed of Soloist 1.3.10.
        state = new PlayerState();
        apply("{\"type\":\"auth_state\",\"logged_in\":true,\"is_active\":true}");
        state.apply(PlayerStateTest.fixture("queue_changed"));
        PlayerState.QueueItem first = state.getQueue().get(0);
        assertEquals("6bbcc1f1e0d78d1c", first.uid);
        QueueJump j = new QueueJump(0, first.uid, first.uri, clock, TIMEOUT, effects);
        assertTrue(j.start(state.copy()));
        state.apply(PlayerStateTest.fixture("track_changed")); // a different track
        j.onState(state.copy());
        assertEquals(Arrays.asList("mute", "skip", "unmute", "finished"), fx);
        assertFalse(ok);
        assertTrue(why, why.startsWith("landed on spotify:track:2nX1nKma4JpfOfh3kbWrlC"));
    }

    @Test public void trackChangesCountEveryTrackChanged() throws Exception {
        int before = state.getTrackChanges();
        trackChanged("a");
        trackChanged("a");
        apply("{\"type\":\"playback_state\",\"status\":\"playing\",\"item\":{\"uri\":\"spotify:track:b\"}}");
        assertEquals(before + 2, state.copy().getTrackChanges());
    }

    private void j0Paused() throws Exception {
        status("paused");
        sync(0);
    }
}
