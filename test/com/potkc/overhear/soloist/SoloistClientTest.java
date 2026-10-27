package com.potkc.overhear.soloist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.potkc.overhear.ws.TestServer;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SoloistClientTest {
    final BlockingQueue<Object> events = new LinkedBlockingQueue<Object>();
    final List<String> logs = new CopyOnWriteArrayList<String>();
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
        client.setBackoffMs(100);
        client.setLogger(new SoloistClient.Logger() {
            @Override public void log(String message) { logs.add(message); }
        });
        client.addListener(listener);
        client.start();
        connect();
    }

    @After public void tearDown() throws Exception {
        client.stop();
        ts.close();
    }

    /** Accepts the client's next connection and checks what it does first. */
    void connect() throws Exception {
        ts.accept(true);
        assertEquals(Boolean.TRUE, next());
        PlayerState empty = nextState();
        assertNull(empty.getTitle());
        assertFalse(empty.isLoggedIn());
        assertCommand("get_state");
        JSONObject q = readCommand();
        assertEquals("get_queue", q.getString("command"));
        assertEquals(10, q.getInt("limit"));
    }

    Object next() throws InterruptedException {
        Object e = events.poll(5, TimeUnit.SECONDS);
        assertNotNull("no event", e);
        return e;
    }

    PlayerState nextState() throws InterruptedException {
        Object e = next();
        assertTrue("expected a state, got " + e, e instanceof PlayerState);
        return (PlayerState) e;
    }

    void assertNoMoreEvents() throws InterruptedException {
        Object e = events.poll(300, TimeUnit.MILLISECONDS);
        assertNull("unexpected event " + e, e);
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

    void assertNoReconnect(int ms) throws Exception {
        ts.server.setSoTimeout(ms);
        try {
            ts.server.accept();
            throw new AssertionError("reconnected after stop");
        } catch (SocketTimeoutException expected) {
        }
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
        assertNoMoreEvents();
    }

    @Test public void throwingListenerIsReportedAndTheConnectionSurvives() throws Exception {
        SoloistClient.Listener bad = new SoloistClient.Listener() {
            @Override public void onState(PlayerState state) { throw new IllegalStateException("ui bug"); }
            @Override public void onConnected(boolean connected) {}
            @Override public void onError(String message) {}
        };
        client.addListener(bad);
        ts.sendText(PlayerStateTest.fixture("volume_changed").toString());
        assertEquals(60, nextState().getVolume());
        Object e = next();
        assertTrue(String.valueOf(e), String.valueOf(e).startsWith("error ") && e.toString().contains("ui bug"));
        client.removeListener(bad);
        ts.sendText(PlayerStateTest.fixture("auth_state").toString());
        assertTrue(nextState().isLoggedIn());
        assertTrue(client.isConnected());
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

    @Test public void requestQueueSendsTheLimit() throws Exception {
        client.requestQueue(SoloistClient.FULL_QUEUE_LIMIT);
        JSONObject q = assertCommand("get_queue");
        assertEquals(500, q.getInt("limit"));
        assertEquals(3, q.length()); // type, command, limit
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

    @Test public void reconnectsWithEmptyStateAndOneDisconnectEach() throws Exception {
        for (int i = 0; i < 3; i++) {
            ts.sendText(PlayerStateTest.fixture("playback_state").toString());
            assertEquals("Next Chapter", nextState().getTitle());
            ts.dropPeer();
            assertEquals(Boolean.FALSE, next());
            assertFalse(client.isConnected());
            client.pause(); // dropped while disconnected, must not break anything
            connect(); // TRUE, then an empty state before the server resends anything
            assertNull(client.getState().getTitle());
        }
        assertNoMoreEvents();
        int connected = 0, disconnected = 0;
        for (String l : logs) {
            if (l.startsWith("connected")) connected++;
            if (l.startsWith("disconnected")) disconnected++;
        }
        assertEquals(4, connected);
        assertEquals(3, disconnected);
    }

    @Test public void backsOffWhileConnectsFailAndResetsAfterOpen() throws Exception {
        client.setBackoffMs(100, 400);
        ts.dropPeer();
        assertEquals(Boolean.FALSE, next());
        long t0 = System.nanoTime();
        // Two failed attempts: TCP accepted, then dropped before the handshake reply.
        ts.acceptRaw().close();
        long t1 = System.nanoTime();
        ts.acceptRaw().close();
        long t2 = System.nanoTime();
        ts.acceptRaw().close();
        long t3 = System.nanoTime();
        assertTrue(t1 - t0 >= TimeUnit.MILLISECONDS.toNanos(90));
        assertTrue(t2 - t1 >= TimeUnit.MILLISECONDS.toNanos(390));
        assertTrue(t3 - t2 >= TimeUnit.MILLISECONDS.toNanos(390)); // capped
        connect();
        assertNoMoreEvents(); // failed attempts are not disconnects
        long t4 = System.nanoTime();
        ts.dropPeer();
        assertEquals(Boolean.FALSE, next());
        connect();
        assertTrue("backoff was not reset", System.nanoTime() - t4 < TimeUnit.MILLISECONDS.toNanos(390));
    }

    @Test public void stopDoesNotReconnect() throws Exception {
        client.stop();
        assertEquals(Boolean.FALSE, next()); // reported by stop() itself
        assertEquals(8, ts.readFrame().opcode); // close handshake
        assertFalse(client.isConnected());
        assertNoReconnect(500);
        assertNoMoreEvents();
        client.pause(); // ignored after stop
    }

    @Test public void stopWhileConnectedAlwaysClosesTheSocket() throws Exception {
        // The connect thread is parked waiting for the connection to end; stop()
        // must close the WebSocket regardless of when that thread wakes up.
        for (int i = 0; i < 30; i++) {
            ts.peer.setSoTimeout(2000);
            client.stop();
            assertEquals(Boolean.FALSE, next());
            assertEquals("cycle " + i, 8, ts.readFrame().opcode);
            ts.dropPeer();
            client.start();
            connect();
        }
        assertNoMoreEvents();
    }

    @Test public void stopDuringTheHandshakeClosesTheSocket() throws Exception {
        ts.dropPeer();
        assertEquals(Boolean.FALSE, next());
        Socket s = ts.acceptRaw(); // the client waits for a handshake reply that never comes
        s.setSoTimeout(2000);
        assertTrue(new String(new byte[] {(byte) ts.in.read()}, "US-ASCII").equals("G"));
        long start = System.nanoTime();
        client.stop();
        int c;
        while ((c = ts.in.read()) >= 0) {
            // drain the rest of the request
        }
        assertEquals(-1, c);
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        assertNoMoreEvents(); // that connection never opened
        assertNoReconnect(500);
    }

    @Test public void stopThenStartReachesTheNewConnection() throws Exception {
        Socket old = ts.peer;
        client.stop();
        client.start();
        assertEquals(Boolean.FALSE, next());
        assertEquals(8, ts.readFrame().opcode); // the old connection is closing
        old.close(); // its reader now ends; that must not report a disconnect
        connect();
        client.pause();
        assertCommand("pause");
        assertTrue(client.isConnected());
        assertNoMoreEvents();
        ts.sendText(PlayerStateTest.fixture("volume_changed").toString());
        assertEquals(60, nextState().getVolume());
    }
}
