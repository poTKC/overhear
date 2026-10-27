package com.potkc.overhear.spotify;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.potkc.overhear.runtime.Dns;
import com.potkc.overhear.runtime.HelperProcess;
import com.potkc.overhear.runtime.RuntimeInstaller;

/**
 * The device's ApiTransport: each request runs overhear-fetch token or api
 * (Android 4.3's own TLS cannot reach Spotify reliably), killed after
 * TIMEOUT_MS. The access token goes in $OVERHEAR_TOKEN and forms and JSON
 * bodies on stdin, never on the command line; none of them is ever logged
 * or put in an exception. Blocking; thread-safe (no state of its own).
 */
public final class HelperTransport implements ApiTransport {
    static final long TIMEOUT_MS = 40000;

    private final RuntimeInstaller installer;

    public HelperTransport(RuntimeInstaller installer) {
        this.installer = installer;
    }

    @Override public HelperReply token(String form) throws IOException {
        List<String> args = baseArgs();
        args.add("token");
        return call(args, null, form);
    }

    @Override public HelperReply api(String method, String path, String accessToken, String body)
            throws IOException {
        List<String> args = baseArgs();
        args.add("api");
        args.add(method);
        args.add(path);
        Map<String, String> env = new HashMap<String, String>();
        env.put("OVERHEAR_TOKEN", accessToken);
        return call(args, env, body);
    }

    private List<String> baseArgs() {
        List<String> args = new ArrayList<String>();
        args.add("--ca");
        args.add(installer.caFile().getPath());
        args.add("--dns");
        args.add(Dns.helperArg(RuntimeInstaller.systemDns()));
        return args;
    }

    private HelperReply call(List<String> args, Map<String, String> env, String stdin) throws IOException {
        HelperProcess.Result r = HelperProcess.run(installer, args, env, utf8(stdin), TIMEOUT_MS);
        if (r.timedOut) throw new IOException("overhear-fetch timed out after " + TIMEOUT_MS / 1000 + " s");
        if (r.exitCode != 0) {
            throw new IOException(r.lastErrorLine.isEmpty()
                    ? "overhear-fetch exited with " + r.exitCode : r.lastErrorLine);
        }
        return HelperReply.parse(r.stdout);
    }

    private static byte[] utf8(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
