package jp.tkc.st15iplayer.soloist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.SocketTimeoutException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jp.tkc.st15iplayer.ws.TestServer;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SoloistClientTest {
    final BlockingQueue<Object> events = new LinkedBlockingQueue<Object>();
    final SoloistClient.Listener listener = new SoloistClient.Listener() {
        @Override public void onState(PlayerState state) { events.add(state); }
        @Override public void onConnected(boolean connected) { events.add(connected); }
        @Override public void onError(String message) { events.add("error " + message); }
    };
    final Executor direct = new Executor() {
        @Override public void execute(Runnable r) { r.run(); }
    };

    TestServer ts;
    SoloistClient client;

    @Before public void setUp() throws Exception {
        ts = new TestServer();
        client = new SoloistClient("127.0.0.1", ts.port(), direct);
        client.setReconnectDelayMs(100);
        client.addListener(listener);
        client.start();
        ts.accept(true);
        assertEquals(Boolean.TRUE, next());
        assertCommand("get_state");
        JSONObject q = readCommand();
        assertEquals("get_queue", q.getString("command"));
        assertEquals(10, q.getInt("limit"));
    }

    @After public void tearDown() throws Exception {
        client.stop();
        ts.close();
    }

    Object next() throws InterruptedException {
        Object e = events.poll(5, TimeUnit.SECONDS);
        assertNotNull("no event", e);
        return e;
    }

    PlayerState nextState() throws InterruptedException {
        Object e;
        while (!((e = next()) instanceof PlayerState)) {
            // skip connection events
        }
        return (PlayerState) e;
    }

    JSONObject readCommand() throws Exception {
        JSONObject o = new JSONObject(ts.readText());
        assertEquals("command", o.getString("type"));
        return o;
    }

    JSONObject assertCommand(String name) throws Exception {
        JSONObject o = readCommand();
        assertEquals(name, o.getString("command"));
        return o;
    }

    @Test public void appliesEventsAndNotifies() throws Exception {
        ts.sendText(PlayerStateTest.fixture("auth_state").toString());
        assertTrue(nextState().isLoggedIn());
        ts.sendText(PlayerStateTest.fixture("playback_state").toString());
        PlayerState s = nextState();
        assertEquals("Next Chapter", s.getTitle());
        assertTrue(s.isLoggedIn());
        assertEquals("Next Chapter", client.getState().getTitle());
        assertTrue(client.isConnected());

        ts.sendText(PlayerStateTest.fixture("volume_changed").toString());
        assertEquals(60, nextState().getVolume());
        assertEquals(70, s.getVolume()); // earlier snapshots do not change
    }

    @Test public void errorsAndResultsAreNotStateChanges() throws Exception {
        ts.sendText(PlayerStateTest.fixture("command_result").toString());
        ts.sendText(PlayerStateTest.fixture("error").toString());
        assertEquals("error unknown command: bogus", next());
        ts.sendText("not json");
        ts.sendText(PlayerStateTest.fixture("volume_changed").toString());
        assertEquals(60, nextState().getVolume());
        assertTrue(events.isEmpty());
    }

    @Test public void trackAndContextChangesRefreshTheQueue() throws Exception {
        ts.sendText(PlayerStateTest.fixture("track_changed").toString());
        assertEquals(10, assertCommand("get_queue").getInt("limit"));
        ts.sendText(PlayerStateTest.fixture("context_changed").toString());
        assertEquals(10, assertCommand("get_queue").getInt("limit"));
    }

    @Test public void controls() throws Exception {
        client.play();
        assertFalse(assertCommand("play").has("uri"));
        client.play("spotify:album:x");
        assertEquals("spotify:album:x", assertCommand("play").getString("uri"));
        client.pause();
        assertCommand("pause");
        client.next();
        assertCommand("skip_next");
        client.prev();
        assertCommand("skip_prev");
        client.seek(12345);
        assertEquals(12345, assertCommand("seek").getLong("position_ms"));
        client.seek(-5);
        assertEquals(0, assertCommand("seek").getLong("position_ms"));
        client.setVolume(42);
        assertEquals(42, assertCommand("set_volume").getInt("volume"));
        client.setVolume(140);
        assertEquals(100, assertCommand("set_volume").getInt("volume"));
        client.setShuffle(true);
        assertTrue(assertCommand("set_shuffle").getBoolean("enabled"));
        client.refreshQueue();
        assertEquals(10, assertCommand("get_queue").getInt("limit"));
        client.refreshState();
        assertCommand("get_state");
        client.activate();
        assertCommand("activate");
    }

    @Test public void repeatUsesTwoCommands() throws Exception {
        client.setRepeat(PlayerState.Repeat.OFF);
        assertFalse(assertCommand("set_repeat_track").getBoolean("enabled"));
        assertFalse(assertCommand("set_repeat_context").getBoolean("enabled"));
        client.setRepeat(PlayerState.Repeat.CONTEXT);
        assertFalse(assertCommand("set_repeat_track").getBoolean("enabled"));
        assertTrue(assertCommand("set_repeat_context").getBoolean("enabled"));
        client.setRepeat(PlayerState.Repeat.TRACK);
        assertFalse(assertCommand("set_repeat_context").getBoolean("enabled"));
        assertTrue(assertCommand("set_repeat_track").getBoolean("enabled"));
    }

    @Test public void reconnectsAfterDrop() throws Exception {
        ts.sendText(PlayerStateTest.fixture("playback_state").toString());
        nextState();
        ts.dropPeer();
        assertEquals(Boolean.FALSE, next());
        assertFalse(client.isConnected());
        client.pause(); // dropped while disconnected, must not break anything

        ts.accept(true);
        assertEquals(Boolean.TRUE, next());
        assertCommand("get_state");
        assertCommand("get_queue");
        // The previous connection's state is gone until the server resends it.
        ts.sendText(PlayerStateTest.fixture("volume_changed").toString());
        PlayerState s = nextState();
        assertEquals(60, s.getVolume());
        assertEquals(null, s.getTitle());
    }

    @Test public void stopDoesNotReconnect() throws Exception {
        client.stop();
        assertEquals(8, ts.readFrame().opcode); // close handshake
        assertEquals(Boolean.FALSE, next());
        ts.server.setSoTimeout(500);
        try {
            ts.server.accept();
            throw new AssertionError("reconnected after stop");
        } catch (SocketTimeoutException expected) {
        }
    }
}
