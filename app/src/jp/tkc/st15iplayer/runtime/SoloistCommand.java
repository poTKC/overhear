package jp.tkc.st15iplayer.runtime;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The command line, environment and working directory for one Soloist run,
 * plus helpers for its log lines. Pure Java so it runs in the host tests.
 *
 * The API key never goes on the command line: on Android 4.3 every app can
 * read /proc/<pid>/cmdline. It goes into ST15I_SOLOIST_KEY, which
 * libargkey.so (native/argkey.c, preloaded through ld.so) turns into
 * "-k <key>" in a heap argv and scrubs from the environment before Soloist's
 * main runs. Soloist itself has no environment variable for the key.
 *
 * The working directory must be the runtime dir: its libc reads
 * "./resolv.conf" (see runtime/build.sh).
 */
public final class SoloistCommand {
    public static final String DEFAULT_NAME = "ST15i";
    public static final String WS_ADDRESS = "127.0.0.1:8765";
    public static final int CACHE_MB = 200;
    /** Soloist's exit status when its build has expired (builds live 90 days). */
    public static final int EXIT_EXPIRED = 10;
    static final String KEY_ENV = "ST15I_SOLOIST_KEY";
    static final String AUDIO_OUT = "unix:@st15i-player-audio";
    static final String LOADER = "ld-linux-armhf.so.3";
    static final String ARGKEY = "libargkey.so";
    static final String CACERT = "cacert.pem";
    private static final String REDACTED = "<redacted>";
    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;?]*[ -/]*[@-~]");
    /** Soloist's log format: "HH:MM:SS.mmm E [..." for errors. */
    private static final Pattern ERROR_LEVEL = Pattern.compile("^\\S+ E \\[.*");
    private static final Pattern ERROR_WORDS =
            Pattern.compile(".*(?i:\\berror\\b|\\bfailed\\b|\\bfatal\\b).*");

    private final File runtimeDir;
    private final File soloistBin;
    private final File dataDir;
    private final File cacheDir;
    private final File homeDir;
    private final String deviceName;
    private final String apiKey;
    private final boolean verbose;

    public SoloistCommand(File runtimeDir, File soloistBin, File dataDir, File cacheDir, File homeDir,
            String deviceName, String apiKey, boolean verbose) {
        if (apiKey == null || apiKey.isEmpty()) throw new IllegalArgumentException("no API key");
        for (int i = 0; i < apiKey.length(); i++) {
            char ch = apiKey.charAt(i);
            if (ch <= ' ' || ch == 0x7f) throw new IllegalArgumentException("API key has invalid characters");
        }
        this.runtimeDir = runtimeDir;
        this.soloistBin = soloistBin;
        this.dataDir = dataDir;
        this.cacheDir = cacheDir;
        this.homeDir = homeDir;
        this.deviceName = cleanName(deviceName);
        this.apiKey = apiKey;
        this.verbose = verbose;
    }

    /** Blank names, and names Soloist would parse as an option, become DEFAULT_NAME. */
    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        return n.isEmpty() || n.startsWith("-") ? DEFAULT_NAME : n;
    }

    public String deviceName() {
        return deviceName;
    }

    public File workingDir() {
        return runtimeDir;
    }

    public List<String> command() {
        List<String> c = new ArrayList<String>();
        c.add(new File(runtimeDir, LOADER).getPath());
        c.add("--preload");
        c.add(new File(runtimeDir, ARGKEY).getPath());
        c.add("--library-path");
        c.add(runtimeDir.getPath());
        c.add(soloistBin.getPath());
        c.add("-n");
        c.add(deviceName);
        c.add("-D");
        c.add(dataDir.getPath());
        c.add("-C");
        c.add(cacheDir.getPath());
        c.add("-w");
        c.add(WS_ADDRESS);
        c.add("-z");
        c.add(Integer.toString(CACHE_MB));
        if (verbose) c.add("-v");
        return c;
    }

    /**
     * Edits a ProcessBuilder environment: sets HOME, SSL_CERT_FILE,
     * FAKEPULSE_OUT and the key; drops Android's loader variables, which
     * would leak into the glibc loader.
     */
    public void applyEnvironment(Map<String, String> env) {
        env.remove("LD_LIBRARY_PATH");
        env.remove("LD_PRELOAD");
        env.remove("SOLOIST_API_KEY");
        env.put("HOME", homeDir.getPath());
        env.put("SSL_CERT_FILE", new File(runtimeDir, CACERT).getPath());
        env.put("FAKEPULSE_OUT", AUDIO_OUT);
        env.put(KEY_ENV, apiKey);
    }

    /**
     * True when a /proc/<pid>/cmdline (NUL-separated) is a run of this
     * runtime's loader, i.e. a Soloist this app started, possibly orphaned by
     * a force-stop.
     */
    public boolean isRunOf(String cmdline) {
        int end = cmdline.indexOf('\0');
        String argv0 = end < 0 ? cmdline : cmdline.substring(0, end);
        return argv0.equals(new File(runtimeDir, LOADER).getPath());
    }

    /** Strips terminal colour codes and replaces every occurrence of secret. */
    public static String sanitize(String line, String secret) {
        String out = ANSI.matcher(line).replaceAll("");
        if (secret != null && !secret.isEmpty() && out.contains(secret)) {
            out = out.replace(secret, REDACTED);
        }
        return out;
    }

    /** Lines worth sending to logcat: error-level lines and failure messages. */
    public static boolean isErrorLine(String line) {
        return ERROR_LEVEL.matcher(line).matches() || ERROR_WORDS.matcher(line).matches();
    }

    public static boolean isExpired(int exitCode) {
        return exitCode == EXIT_EXPIRED;
    }
}
