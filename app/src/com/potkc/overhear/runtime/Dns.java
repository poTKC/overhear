package com.potkc.overhear.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * DNS server selection for the bundled glibc (resolv.conf) and overhear-fetch
 * (--dns). Both need literal IPs: resolving a server's name would need DNS.
 * Pure Java so it runs in the host tests.
 */
public final class Dns {
    /** Used when the system reports no usable server. */
    public static final List<String> FALLBACK =
            Collections.unmodifiableList(Arrays.asList("8.8.8.8", "1.1.1.1"));
    /** glibc reads at most MAXNS (3) nameserver lines. */
    static final int MAX_SERVERS = 3;
    static final String OPTIONS = "options timeout:2 attempts:2 rotate";

    private Dns() {}

    /**
     * Returns the valid literal IPs among candidates, trimmed, de-duplicated,
     * in order and at most three; FALLBACK when none is valid.
     */
    public static List<String> choose(String... candidates) {
        List<String> out = new ArrayList<String>();
        for (String c : candidates) {
            if (c == null) continue;
            String s = c.trim();
            if (isIpLiteral(s) && !out.contains(s) && out.size() < MAX_SERVERS) out.add(s);
        }
        return out.isEmpty() ? new ArrayList<String>(FALLBACK) : out;
    }

    public static String resolvConf(List<String> servers) {
        StringBuilder sb = new StringBuilder();
        for (String s : servers) sb.append("nameserver ").append(s).append('\n');
        // Fail over quickly (default 5 s x 2) and spread queries across servers.
        sb.append(OPTIONS).append('\n');
        return sb.toString();
    }

    /** The overhear-fetch --dns value: comma-separated, port 53 implied. */
    public static String helperArg(List<String> servers) {
        StringBuilder sb = new StringBuilder();
        for (String s : servers) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * True for a dotted-quad IPv4 address (no leading zeros) or an IPv6
     * address, optionally ending in an IPv4 part. No brackets, ports or zone
     * ids. Purely syntactic: never touches the network.
     */
    public static boolean isIpLiteral(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.indexOf(':') >= 0 ? isIpv6(s) : isIpv4(s);
    }

    static boolean isIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            if (p.length() > 1 && p.charAt(0) == '0') return false;
            for (int i = 0; i < p.length(); i++) {
                char ch = p.charAt(i);
                if (ch < '0' || ch > '9') return false;
            }
            if (Integer.parseInt(p) > 255) return false;
        }
        return true;
    }

    static boolean isIpv6(String s) {
        int dc = s.indexOf("::");
        if (dc >= 0 && s.indexOf("::", dc + 1) >= 0) return false;
        String head = dc >= 0 ? s.substring(0, dc) : s;
        String tail = dc >= 0 ? s.substring(dc + 2) : "";
        int groups = 0;
        String[] sides = dc >= 0 ? new String[] {head, tail} : new String[] {head};
        for (int side = 0; side < sides.length; side++) {
            String part = sides[side];
            if (part.isEmpty()) continue;
            String[] items = part.split(":", -1);
            for (int i = 0; i < items.length; i++) {
                String it = items[i];
                boolean last = (side == sides.length - 1) && i == items.length - 1;
                if (last && it.indexOf('.') >= 0) {
                    if (!isIpv4(it)) return false;
                    groups += 2;
                    continue;
                }
                if (it.isEmpty() || it.length() > 4) return false;
                for (int k = 0; k < it.length(); k++) {
                    if (Character.digit(it.charAt(k), 16) < 0) return false;
                }
                groups++;
            }
        }
        return dc >= 0 ? groups <= 7 : groups == 8;
    }
}
