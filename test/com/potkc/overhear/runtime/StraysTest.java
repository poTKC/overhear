package com.potkc.overhear.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StraysTest {
    private static final String LOADER = "/f/runtime/ld-linux-armhf.so.3";
    private static final String OURS = LOADER + "\0--preload\0/f/runtime/libargkey.so\0-n\0ST15i\0";

    @Test public void parsesPpidFromStat() {
        assertEquals(1, Strays.parsePpid("1955 (ld-linux-armhf.) S 1 1955 1955 0 -1 4194560 ..."));
        assertEquals(2043, Strays.parsePpid("2073 (ld-linux-armhf.) S 2043 2073 0 0"));
        // comm may contain spaces and parentheses: ppid follows the last ')'.
        assertEquals(77, Strays.parsePpid("123 (a) b (c)) R 77 1 1"));
        assertEquals(-1, Strays.parsePpid(""));
        assertEquals(-1, Strays.parsePpid("123 (x S 1"));
        assertEquals(-1, Strays.parsePpid("123 (x) S"));
        assertEquals(-1, Strays.parsePpid("123 (x) S abc"));
    }

    @Test public void orphanOfOurRuntimeIsAStray() {
        assertTrue(Strays.isStray(OURS, LOADER, 1955, 1, 2043, -1));
    }

    @Test public void childOfAnotherAppProcessIsAStray() {
        // Left by an older app process that is still dying (not reparented yet).
        assertTrue(Strays.isStray(OURS, LOADER, 1955, 1700, 2043, -1));
    }

    @Test public void ourOwnChildrenAreNever() {
        assertFalse(Strays.isStray(OURS, LOADER, 2073, 2043, 2043, -1));
        assertFalse(Strays.isStray(OURS, LOADER, 2073, 1, 2043, 2073)); // the current run
    }

    @Test public void otherProgramsAreNever() {
        assertFalse(Strays.isStray("./lib/ld-linux-armhf.so.3\0./soloist\0", LOADER, 11713, 1, 2043, -1));
        assertFalse(Strays.isStray("/f/runtime/overhear-fetch\0extract\0", LOADER, 99, 1, 2043, -1));
        assertFalse(Strays.isStray("", LOADER, 99, 1, 2043, -1));
        assertFalse(Strays.isStray(OURS, LOADER, 99, -1, 2043, -1)); // unreadable stat
    }
}
