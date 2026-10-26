package com.potkc.overhear.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class HelperOutputTest {
    static final class Recorder implements ProgressListener {
        final List<String> events = new ArrayList<String>();
        @Override public void onProgress(int percent) { events.add("p" + percent); }
        @Override public void onExtracting() { events.add("x"); }
    }

    @Test public void parsesTheHelperProtocol() {
        Recorder r = new Recorder();
        HelperOutput out = new HelperOutput(r);
        for (String line : new String[] {"progress 0", "progress 42", "progress 100", "extracting", "done"}) {
            out.line(line);
        }
        assertEquals(Arrays.asList("p0", "p42", "p100", "x"), r.events);
        assertTrue(out.isDone());
    }

    @Test public void ignoresMalformedAndOutOfRangeLines() {
        Recorder r = new Recorder();
        HelperOutput out = new HelperOutput(r);
        for (String line : new String[] {"progress", "progress x", "progress -1", "progress 101",
                "progress 5 6", "PROGRESS 5", "hello", "", "doneish", " progress 5"}) {
            out.line(line);
        }
        assertEquals(new ArrayList<String>(), r.events);
        assertFalse(out.isDone());
    }

    @Test public void toleratesTrailingCarriageReturnAndNullListener() {
        Recorder r = new Recorder();
        HelperOutput out = new HelperOutput(r);
        out.line("progress 7\r");
        out.line("done\r");
        assertEquals(Arrays.asList("p7"), r.events);
        assertTrue(out.isDone());

        HelperOutput quiet = new HelperOutput(null);
        quiet.line("progress 1");
        quiet.line("extracting");
        quiet.line("done");
        assertTrue(quiet.isDone());
    }

    @Test public void lastPercentIsTracked() {
        HelperOutput out = new HelperOutput(null);
        assertEquals(-1, out.lastPercent());
        out.line("progress 12");
        assertEquals(12, out.lastPercent());
    }
}
