package jp.tkc.st15iplayer.runtime;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class LogRingTest {
    @Test public void keepsLinesInOrderUntilFull() {
        LogRing ring = new LogRing(3);
        assertEquals(Collections.<String>emptyList(), ring.lines());
        ring.add("a");
        ring.add("b");
        assertEquals(Arrays.asList("a", "b"), ring.lines());
    }

    @Test public void dropsOldestWhenFull() {
        LogRing ring = new LogRing(3);
        for (String s : new String[] {"1", "2", "3", "4", "5"}) ring.add(s);
        assertEquals(Arrays.asList("3", "4", "5"), ring.lines());
        for (int i = 6; i <= 10; i++) ring.add(Integer.toString(i));
        assertEquals(Arrays.asList("8", "9", "10"), ring.lines());
    }

    @Test public void snapshotIsIndependent() {
        LogRing ring = new LogRing(2);
        ring.add("a");
        List<String> snap = ring.lines();
        ring.add("b");
        ring.add("c");
        assertEquals(Arrays.asList("a"), snap);
    }

    @Test public void clearEmpties() {
        LogRing ring = new LogRing(2);
        ring.add("a");
        ring.clear();
        assertEquals(Collections.<String>emptyList(), ring.lines());
        ring.add("b");
        assertEquals(Arrays.asList("b"), ring.lines());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroCapacity() {
        new LogRing(0);
    }

    @Test public void concurrentAddsKeepCapacity() throws Exception {
        final LogRing ring = new LogRing(200);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(new Runnable() {
                @Override public void run() {
                    for (int i = 0; i < 1000; i++) ring.add("x" + i);
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        assertEquals(200, ring.lines().size());
    }
}
