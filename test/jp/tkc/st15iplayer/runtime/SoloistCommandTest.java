package jp.tkc.st15iplayer.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class SoloistCommandTest {
    private static final String KEY = "fake0123456789abcdef0123456789abcd";

    private static SoloistCommand cmd(String name, boolean verbose) {
        return new SoloistCommand(new File("/f/runtime"), new File("/f/soloist/soloist"),
                new File("/f/soloist-data"), new File("/c/soloist"), new File("/f"), name, KEY, verbose);
    }

    @Test public void commandPreloadsArgkeyAndNeverContainsTheKey() {
        List<String> c = cmd("ST15i", false).command();
        assertEquals(Arrays.asList(
                "/f/runtime/ld-linux-armhf.so.3",
                "--preload", "/f/runtime/libargkey.so",
                "--library-path", "/f/runtime",
                "/f/soloist/soloist",
                "-n", "ST15i",
                "-D", "/f/soloist-data",
                "-C", "/c/soloist",
                "-w", "127.0.0.1:8765",
                "-z", "200"), c);
        for (String arg : c) {
            assertFalse(arg.contains(KEY));
            assertFalse(arg.equals("-k"));
        }
    }

    @Test public void verboseAddsFlag() {
        List<String> c = cmd("ST15i", true).command();
        assertEquals("-v", c.get(c.size() - 1));
    }

    @Test public void environmentSetsOursAndDropsLoaderOverrides() {
        Map<String, String> env = new HashMap<String, String>();
        env.put("PATH", "/system/bin");
        env.put("LD_LIBRARY_PATH", "/vendor/lib:/system/lib");
        env.put("LD_PRELOAD", "libsigchain.so");
        env.put("SOLOIST_API_KEY", "stale");
        cmd("ST15i", false).applyEnvironment(env);
        assertEquals("/system/bin", env.get("PATH"));
        assertNull(env.get("LD_LIBRARY_PATH"));
        assertNull(env.get("LD_PRELOAD"));
        assertNull(env.get("SOLOIST_API_KEY"));
        assertEquals("/f", env.get("HOME"));
        assertEquals("/f/runtime/cacert.pem", env.get("SSL_CERT_FILE"));
        assertEquals("unix:@st15i-player-audio", env.get("FAKEPULSE_OUT"));
        assertEquals(KEY, env.get("ST15I_SOLOIST_KEY"));
    }

    @Test public void workingDirIsTheRuntimeDir() {
        assertEquals(new File("/f/runtime"), cmd("ST15i", false).workingDir());
    }

    @Test public void blankOrOptionLikeNamesFallBackToDefault() {
        assertEquals("ST15i", cmd("", false).deviceName());
        assertEquals("ST15i", cmd("   ", false).deviceName());
        assertEquals("ST15i", cmd(null, false).deviceName());
        assertEquals("ST15i", cmd("-k", false).deviceName());
        assertEquals("Kitchen", cmd(" Kitchen ", false).deviceName());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingKey() {
        new SoloistCommand(new File("/r"), new File("/s"), new File("/d"), new File("/c"), new File("/h"),
                "ST15i", "", false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsKeyWithControlCharacters() {
        new SoloistCommand(new File("/r"), new File("/s"), new File("/d"), new File("/c"), new File("/h"),
                "ST15i", "abc\ndef", false);
    }

    @Test public void sanitizeStripsAnsiAndRedactsKey() {
        String line = "\u001b[0m10:40:01.441 I [f:462] ready key=" + KEY + " again " + KEY + "\u001b[0m";
        String out = SoloistCommand.sanitize(line, KEY);
        assertEquals("10:40:01.441 I [f:462] ready key=<redacted> again <redacted>", out);
        assertEquals("plain", SoloistCommand.sanitize("plain", null));
        assertEquals("plain", SoloistCommand.sanitize("plain", ""));
        assertEquals("a b", SoloistCommand.sanitize("a\u001b[1;31m b", KEY));
    }

    @Test public void errorLinesAreRecognised() {
        assertTrue(SoloistCommand.isErrorLine("10:40:01.441 E [f:1] something broke"));
        assertTrue(SoloistCommand.isErrorLine("Error: --api-key is required"));
        assertTrue(SoloistCommand.isErrorLine("login failed: make sure --api-key is valid"));
        assertFalse(SoloistCommand.isErrorLine("10:40:01.441 I [f:462] ready, device_name=ST15i"));
        assertFalse(SoloistCommand.isErrorLine("10:40:01.441 W [f:462] Errata"));
    }

    @Test public void exitCodeTenMeansExpired() {
        assertTrue(SoloistCommand.isExpired(10));
        assertFalse(SoloistCommand.isExpired(0));
        assertFalse(SoloistCommand.isExpired(1));
        assertFalse(SoloistCommand.isExpired(137));
    }

    @Test public void recognisesItsOwnProcessesFromCmdline() {
        String ours = "/f/runtime/ld-linux-armhf.so.3\0--preload\0/f/runtime/libargkey.so\0-n\0ST15i\0";
        assertTrue(cmd("ST15i", false).isRunOf(ours));
        assertTrue(cmd("ST15i", false).isRunOf("/f/runtime/ld-linux-armhf.so.3"));
        assertFalse(cmd("ST15i", false).isRunOf("./lib/ld-linux-armhf.so.3\0--library-path\0./lib\0"));
        assertFalse(cmd("ST15i", false).isRunOf("/f/runtime/ld-linux-armhf.so.3x\0"));
        assertFalse(cmd("ST15i", false).isRunOf(""));
        assertFalse(cmd("ST15i", false).isRunOf("/f/runtime/st15i-fetch\0extract\0"));
    }
}
