package com.potkc.overhear.runtime;

import java.util.ArrayList;
import java.util.List;

/** Thread-safe ring of the last N log lines. Pure Java (host tests). */
public final class LogRing {
    private final String[] lines;
    private int start;
    private int size;

    public LogRing(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity " + capacity);
        lines = new String[capacity];
    }

    public synchronized void add(String line) {
        if (size < lines.length) {
            lines[(start + size++) % lines.length] = line;
        } else {
            lines[start] = line;
            start = (start + 1) % lines.length;
        }
    }

    /** Oldest first; a copy. */
    public synchronized List<String> lines() {
        List<String> out = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) out.add(lines[(start + i) % lines.length]);
        return out;
    }

    public synchronized void clear() {
        for (int i = 0; i < lines.length; i++) lines[i] = null;
        start = 0;
        size = 0;
    }
}
