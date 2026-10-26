package com.potkc.overhear.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class StreamSessionTest {
    final List<String> events = new ArrayList<String>();
    final List<String> logs = new ArrayList<String>();
    boolean focusGranted = true;
    boolean rejectTracks;
    int writeLimit = Integer.MAX_VALUE;
    boolean writeReturnsZero;
    int writeResult; // returned instead of writing when non-zero
    long slept;

    final class FakeTrack implements StreamSession.Track {
        final String name;
        FakeTrack(String name) { this.name = name; }
        @Override public void play() { events.add("play " + name); }
        @Override public void pause() { events.add("pause " + name); }
        @Override public void flush() { events.add("flush " + name); }
        @Override public int write(byte[] b, int off, int len) {
            if (writeReturnsZero) return 0;
            if (writeResult != 0) return writeResult;
            int n = Math.min(len, writeLimit);
            events.add("write " + name + " " + n);
            return n;
        }
        @Override public long head() { return 0; }
        @Override public void release() { events.add("release " + name); }
    }

    final StreamSession.Focus focus = new StreamSession.Focus() {
        @Override public boolean request() { events.add("focus"); return focusGranted; }
        @Override public void abandon() { events.add("abandon"); }
    };

    final StreamSession.Host host = new StreamSession.Host() {
        @Override public StreamSession.Track createTrack(int rate, int channels) {
            events.add("create " + rate + " " + channels);
            return rejectTracks ? null : new FakeTrack(rate + "/" + channels);
        }
        @Override public void onPlaying(boolean playing) { events.add("playing " + playing); }
        @Override public void log(String msg) { logs.add(msg); }
        @Override public void debug(String msg) { logs.add(msg); }
        @Override public long uptimeMs() { return 0; }
        @Override public void sleep(long ms) { slept += ms; }
    };

    final StreamSession session = new StreamSession(host, focus);
    final byte[] pcm = new byte[3528];

    void p() { session.pcm(pcm, 0, pcm.length); }

    List<String> take() {
        List<String> copy = new ArrayList<String>(events);
        events.clear();
        return copy;
    }

    @Test public void headerThenPcmTakesFocusThenPlays() {
        session.header(44100, 2);
        assertEquals(Arrays.asList("create 44100 2"), take());
        p();
        assertEquals(Arrays.asList("focus", "play 44100/2", "write 44100/2 3528", "playing true"), take());
        p();
        assertEquals(Arrays.asList("write 44100/2 3528"), take());
    }

    @Test public void reconnectWhileCorkedNeitherTakesFocusNorPlays() {
        session.header(44100, 2);
        session.cork(true);
        assertEquals(Arrays.asList("create 44100 2"), take());
        session.cork(false);
        assertEquals(Arrays.<String>asList(), take());
        p();
        assertEquals(Arrays.asList("focus", "play 44100/2", "write 44100/2 3528", "playing true"), take());
    }

    @Test public void corkPausesAndAbandonsFocus() {
        session.header(44100, 2);
        p();
        take();
        session.cork(true);
        assertEquals(Arrays.asList("pause 44100/2", "abandon", "playing false"), take());
        session.cork(false);
        p();
        assertEquals(Arrays.asList("focus", "play 44100/2", "write 44100/2 3528", "playing true"), take());
    }

    @Test public void flushWhilePlayingResumes() {
        session.header(44100, 2);
        p();
        take();
        session.flush();
        assertEquals(Arrays.asList("pause 44100/2", "flush 44100/2", "play 44100/2"), take());
    }

    @Test public void flushWhileCorkedStaysPaused() {
        session.header(44100, 2);
        p();
        session.cork(true);
        take();
        session.flush();
        assertEquals(Arrays.asList("pause 44100/2", "flush 44100/2"), take());
    }

    @Test public void flushBeforeFirstPcmDoesNotPlay() {
        session.header(44100, 2);
        take();
        session.flush();
        assertEquals(Arrays.asList("pause 44100/2", "flush 44100/2"), take());
    }

    @Test public void unsupportedChannelsDiscardPcmAndLogOnce() {
        session.header(44100, 6);
        p();
        p();
        session.cork(false);
        session.flush();
        assertEquals(Arrays.<String>asList(), take());
        assertEquals(1, countLogs("discard"));
    }

    @Test public void rejectedTrackDiscardsPcmAndLogsOnce() {
        rejectTracks = true;
        session.header(12345, 2);
        p();
        p();
        assertEquals(Arrays.asList("create 12345 2"), take());
        assertEquals(1, countLogs("discard"));
    }

    @Test public void supportedHeaderAfterUnsupportedRecovers() {
        session.header(44100, 6);
        p();
        session.header(44100, 2);
        p();
        assertEquals(Arrays.asList("create 44100 2", "focus", "play 44100/2", "write 44100/2 3528", "playing true"),
                take());
    }

    @Test public void pcmBeforeHeaderIsDiscarded() {
        p();
        p();
        assertEquals(Arrays.<String>asList(), take());
        assertEquals(1, countLogs("discard"));
    }

    @Test public void sameHeaderKeepsTrack() {
        session.header(44100, 2);
        p();
        take();
        session.header(44100, 2);
        p();
        assertEquals(Arrays.asList("write 44100/2 3528"), take());
    }

    @Test public void newHeaderReplacesTrackAndKeepsFocus() {
        session.header(44100, 2);
        p();
        take();
        session.header(48000, 1);
        assertEquals(Arrays.asList("release 44100/2", "create 48000 1"), take());
        p();
        assertEquals(Arrays.asList("play 48000/1", "write 48000/1 3528"), take());
    }

    @Test public void partialWritesAreCompleted() {
        writeLimit = 1000;
        session.header(44100, 2);
        p();
        assertEquals(Arrays.asList("focus", "play 44100/2", "write 44100/2 1000", "write 44100/2 1000",
                "write 44100/2 1000", "write 44100/2 528", "playing true"), take().subList(1, 8));
    }

    @Test public void focusDeniedStillPlays() {
        focusGranted = false;
        session.header(44100, 2);
        p();
        assertEquals(Arrays.asList("create 44100 2", "focus", "play 44100/2", "write 44100/2 3528", "playing true"),
                take());
    }

    @Test public void closeStopsWritingAndFinishReleases() {
        session.header(44100, 2);
        p();
        take();
        session.close();
        p();
        assertEquals(Arrays.<String>asList(), take());
        session.finish();
        assertEquals(Arrays.asList("release 44100/2", "abandon", "playing false"), take());
        session.finish();
        assertEquals(Arrays.<String>asList(), take());
    }

    @Test public void writeReturningZeroDoesNotSpin() {
        session.header(44100, 2);
        writeReturnsZero = true;
        p();
        assertTrue(take().contains("play 44100/2"));
    }

    @Test public void writeErrorReleasesTrackAndDropsClient() {
        session.header(44100, 2);
        p();
        take();
        writeResult = -6;
        try {
            p();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
        }
        assertEquals(Arrays.asList("release 44100/2"), take());
        assertEquals(1, countLogs("write failed"));
        session.finish();
        assertEquals(Arrays.asList("abandon", "playing false"), take());
    }

    @Test public void unsupportedFormatDiscardsAtRealTimePace() {
        session.header(44100, 6); // 529200 bytes/s
        for (int i = 0; i < 150; i++) p(); // 150 * 3528 = 529200 bytes
        assertEquals(1000, slept);
        assertEquals(Arrays.<String>asList(), take());
    }

    @Test public void rejectedTrackDiscardsAtRealTimePace() {
        rejectTracks = true;
        session.header(12345, 2); // 49380 bytes/s
        byte[] b = new byte[1000];
        for (int i = 0; i < 49; i++) session.pcm(b, 0, b.length);
        session.pcm(b, 0, 380);
        assertEquals(1000, slept);
    }

    @Test public void otherDiscardsAreNotPaced() {
        p(); // before header
        session.header(44100, 2);
        session.cork(true);
        p();
        session.close();
        p();
        assertEquals(0, slept);
    }

    @Test public void unusableHeaderDropsClient() {
        try {
            session.header(0, 2);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test public void finishWithoutTrackIsQuiet() {
        session.finish();
        assertEquals(Arrays.<String>asList(), take());
    }

    private int countLogs(String word) {
        int n = 0;
        for (String l : logs) if (l.contains(word)) n++;
        return n;
    }
}
