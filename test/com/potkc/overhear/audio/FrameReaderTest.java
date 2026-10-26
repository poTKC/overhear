package com.potkc.overhear.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FrameReaderTest {
    /** Records listener calls as strings; keeps PCM copies and the arrays passed. */
    static final class Recorder implements FrameReader.Listener {
        final List<String> events = new ArrayList<String>();
        final List<byte[]> pcm = new ArrayList<byte[]>();
        final List<byte[]> arrays = new ArrayList<byte[]>();

        @Override public void header(int rate, int channels) { events.add("H " + rate + " " + channels); }
        @Override public void pcm(byte[] b, int off, int len) {
            events.add("P " + len);
            pcm.add(Arrays.copyOfRange(b, off, off + len));
            arrays.add(b);
        }
        @Override public void flush() { events.add("F"); }
        @Override public void cork(boolean corked) { events.add("C " + corked); }
    }

    /** Returns at most one byte per read, to split every frame across reads. */
    static final class TrickleInputStream extends InputStream {
        private final InputStream in;
        TrickleInputStream(byte[] data) { in = new ByteArrayInputStream(data); }
        @Override public int read() throws IOException { return in.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, Math.min(len, 1));
        }
    }

    static final class Frames {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Frames frame(char type, byte[] payload) { return raw(type, payload.length, payload); }
        Frames raw(char type, long len, byte[] payload) {
            out.write(type);
            writeU32(len);
            out.write(payload, 0, payload.length);
            return this;
        }
        Frames header(long rate, long ch) {
            Frames f = new Frames();
            f.writeU32(rate);
            f.writeU32(ch);
            return frame('H', f.bytes());
        }
        Frames pcm(byte[] b) { return frame('P', b); }
        Frames flush() { return frame('F', new byte[0]); }
        Frames cork(boolean c) { return frame('C', new byte[] {(byte) (c ? 1 : 0)}); }
        void writeU32(long v) {
            for (int i = 0; i < 4; i++) out.write((int) (v >>> (8 * i)) & 0xff);
        }
        byte[] bytes() { return out.toByteArray(); }
    }

    static byte[] pattern(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + seed);
        return b;
    }

    static Recorder readAll(InputStream in) throws IOException {
        Recorder r = new Recorder();
        FrameReader reader = new FrameReader(in);
        while (reader.readNext(r)) { }
        return r;
    }

    @Test public void parsesEveryFrameType() throws IOException {
        byte[] a = pattern(3528, 1);
        byte[] data = new Frames().header(44100, 2).pcm(a).cork(true).cork(false).flush().bytes();
        Recorder r = readAll(new ByteArrayInputStream(data));
        assertEquals(Arrays.asList("H 44100 2", "P 3528", "C true", "C false", "F"), r.events);
        assertArrayEquals(a, r.pcm.get(0));
    }

    @Test public void handlesFramesSplitAcrossReads() throws IOException {
        byte[] a = pattern(17, 2), b = pattern(4000, 3);
        byte[] data = new Frames().header(48000, 1).pcm(a).flush().pcm(b).cork(true).bytes();
        Recorder r = readAll(new TrickleInputStream(data));
        assertEquals(Arrays.asList("H 48000 1", "P 17", "F", "P 4000", "C true"), r.events);
        assertArrayEquals(a, r.pcm.get(0));
        assertArrayEquals(b, r.pcm.get(1));
    }

    @Test public void zeroLengthPcmIsSkipped() throws IOException {
        byte[] data = new Frames().pcm(new byte[0]).flush().pcm(new byte[0]).bytes();
        Recorder r = readAll(new TrickleInputStream(data));
        assertEquals(Arrays.asList("F"), r.events);
    }

    @Test public void unsignedFieldsAreDecodedLittleEndian() throws IOException {
        byte[] data = new Frames().header(0x00012345, 2).bytes();
        Recorder r = readAll(new ByteArrayInputStream(data));
        assertEquals(Arrays.asList("H 74565 2"), r.events);
    }

    @Test public void reusesBufferAndGrowsOnlyWhenNeeded() throws IOException {
        byte[] small1 = pattern(3528, 4), small2 = pattern(64 * 1024, 5);
        byte[] big = pattern(300 * 1024, 6), small3 = pattern(100, 7);
        byte[] data = new Frames().pcm(small1).pcm(small2).pcm(big).pcm(small3).bytes();
        Recorder r = readAll(new ByteArrayInputStream(data));
        assertEquals(4, r.pcm.size());
        assertArrayEquals(small1, r.pcm.get(0));
        assertArrayEquals(small2, r.pcm.get(1));
        assertArrayEquals(big, r.pcm.get(2));
        assertArrayEquals(small3, r.pcm.get(3));
        assertSame(r.arrays.get(0), r.arrays.get(1));
        assertEquals(64 * 1024, r.arrays.get(0).length);
        assertTrue(r.arrays.get(2).length >= 300 * 1024);
        assertSame(r.arrays.get(2), r.arrays.get(3));
    }

    @Test public void cleanEofBetweenFramesReturnsFalse() throws IOException {
        FrameReader reader = new FrameReader(new ByteArrayInputStream(new Frames().flush().bytes()));
        Recorder r = new Recorder();
        assertTrue(reader.readNext(r));
        assertFalse(reader.readNext(r));
        assertFalse(reader.readNext(r));
        assertEquals(Arrays.asList("F"), r.events);
    }

    @Test public void eofInsideFrameHeaderThrowsEof() throws IOException {
        byte[] data = Arrays.copyOf(new Frames().pcm(pattern(10, 8)).bytes(), 3);
        expectEof(data);
    }

    @Test public void eofInsidePayloadThrowsEof() throws IOException {
        byte[] full = new Frames().pcm(pattern(10, 8)).bytes();
        expectEof(Arrays.copyOf(full, full.length - 1));
    }

    @Test public void unknownTypeIsProtocolError() throws IOException {
        expectProtocolError(new Frames().header(44100, 2).frame('X', new byte[0]).bytes(), 1);
    }

    @Test public void oversizedPayloadIsProtocolError() throws IOException {
        expectProtocolError(new Frames().raw('P', FrameReader.MAX_PAYLOAD + 1, new byte[0]).bytes(), 0);
        expectProtocolError(new Frames().raw('P', 0xffffffffL, new byte[0]).bytes(), 0);
    }

    @Test public void maxPayloadIsAccepted() throws IOException {
        byte[] b = pattern(FrameReader.MAX_PAYLOAD, 9);
        Recorder r = readAll(new ByteArrayInputStream(new Frames().pcm(b).bytes()));
        assertArrayEquals(b, r.pcm.get(0));
    }

    @Test public void malformedControlFramesAreProtocolErrors() throws IOException {
        expectProtocolError(new Frames().frame('H', new byte[4]).bytes(), 0);
        expectProtocolError(new Frames().frame('F', new byte[1]).bytes(), 0);
        expectProtocolError(new Frames().frame('C', new byte[0]).bytes(), 0);
        expectProtocolError(new Frames().frame('C', new byte[] {2}).bytes(), 0);
    }

    @Test public void badHeaderValuesAreProtocolErrors() throws IOException {
        expectProtocolError(new Frames().header(0, 2).bytes(), 0);
        expectProtocolError(new Frames().header(44100, 0).bytes(), 0);
        expectProtocolError(new Frames().header(0x80000000, 2).bytes(), 0);
        expectProtocolError(new Frames().header(44100, 0xffffffff).bytes(), 0);
    }

    @Test public void runStopsAtCleanEof() throws IOException {
        byte[] data = new Frames().header(44100, 2).pcm(pattern(8, 1)).bytes();
        Recorder r = new Recorder();
        new FrameReader(new TrickleInputStream(data)).run(r);
        assertEquals(Arrays.asList("H 44100 2", "P 8"), r.events);
    }

    private static void expectEof(byte[] data) throws IOException {
        FrameReader reader = new FrameReader(new TrickleInputStream(data));
        try {
            while (reader.readNext(new Recorder())) { }
            fail("expected EOFException");
        } catch (EOFException expected) {
            assertEquals(EOFException.class, expected.getClass());
        }
    }

    /** Expects goodFrames frames to parse and the next to fail as a protocol error. */
    private static void expectProtocolError(byte[] data, int goodFrames) throws IOException {
        FrameReader reader = new FrameReader(new ByteArrayInputStream(data));
        Recorder r = new Recorder();
        try {
            while (reader.readNext(r)) { }
            fail("expected ProtocolException");
        } catch (FrameReader.ProtocolException expected) {
            assertEquals(goodFrames, r.events.size());
        }
    }
}
