package jp.tkc.st15iplayer.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class DnsTest {
    @Test public void acceptsIpv4Literals() {
        assertTrue(Dns.isIpLiteral("8.8.8.8"));
        assertTrue(Dns.isIpLiteral("192.168.40.1"));
        assertTrue(Dns.isIpLiteral("0.0.0.0"));
        assertTrue(Dns.isIpLiteral("255.255.255.255"));
    }

    @Test public void rejectsBadIpv4() {
        assertFalse(Dns.isIpLiteral("256.1.1.1"));
        assertFalse(Dns.isIpLiteral("1.2.3"));
        assertFalse(Dns.isIpLiteral("1.2.3.4.5"));
        assertFalse(Dns.isIpLiteral("1.2.3.04x"));
        assertFalse(Dns.isIpLiteral("01.2.3.4")); // leading zeros are octal to some parsers
        assertFalse(Dns.isIpLiteral("1..3.4"));
        assertFalse(Dns.isIpLiteral("1.2.3.4:53"));
        assertFalse(Dns.isIpLiteral("1234.1.1.1"));
    }

    @Test public void acceptsIpv6Literals() {
        assertTrue(Dns.isIpLiteral("2001:4860:4860::8888"));
        assertTrue(Dns.isIpLiteral("::1"));
        assertTrue(Dns.isIpLiteral("::"));
        assertTrue(Dns.isIpLiteral("fe80:0:0:0:0:0:0:1"));
        assertTrue(Dns.isIpLiteral("::ffff:192.168.1.1"));
        assertTrue(Dns.isIpLiteral("2001:DB8::"));
    }

    @Test public void rejectsBadIpv6() {
        assertFalse(Dns.isIpLiteral("2001:db8:::1"));
        assertFalse(Dns.isIpLiteral("2001::db8::1"));
        assertFalse(Dns.isIpLiteral("1:2:3:4:5:6:7:8:9"));
        assertFalse(Dns.isIpLiteral("1:2:3:4:5:6:7"));
        assertFalse(Dns.isIpLiteral("12345::1"));
        assertFalse(Dns.isIpLiteral("fe80::1%wlan0"));
        assertFalse(Dns.isIpLiteral("[::1]"));
        assertFalse(Dns.isIpLiteral("g::1"));
        assertFalse(Dns.isIpLiteral(":1"));
        assertFalse(Dns.isIpLiteral("1:"));
        assertFalse(Dns.isIpLiteral("::ffff:1.2.3"));
    }

    @Test public void rejectsJunk() {
        assertFalse(Dns.isIpLiteral(null));
        assertFalse(Dns.isIpLiteral(""));
        assertFalse(Dns.isIpLiteral("dns.google"));
        assertFalse(Dns.isIpLiteral(" 8.8.8.8"));
        assertFalse(Dns.isIpLiteral("8.8.8.8\nnameserver 1.2.3.4"));
    }

    @Test public void chooseKeepsValidServersInOrder() {
        assertEquals(Arrays.asList("192.168.40.1", "2001:db8::1"),
                Dns.choose("192.168.40.1", "2001:db8::1"));
        assertEquals(Arrays.asList("10.0.0.1"), Dns.choose(" 10.0.0.1 ", "", null, "10.0.0.1", "bogus"));
    }

    @Test public void chooseFallsBackWhenNothingValid() {
        assertEquals(Arrays.asList("8.8.8.8", "1.1.1.1"), Dns.choose("", null, "host.name"));
        assertEquals(Arrays.asList("8.8.8.8", "1.1.1.1"), Dns.choose());
    }

    @Test public void chooseKeepsAtMostThree() {
        assertEquals(Arrays.asList("1.1.1.1", "2.2.2.2", "3.3.3.3"),
                Dns.choose("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"));
    }

    @Test public void resolvConfAndHelperArg() {
        assertEquals("nameserver 192.168.40.1\nnameserver 8.8.8.8\noptions timeout:2 attempts:2 rotate\n",
                Dns.resolvConf(Arrays.asList("192.168.40.1", "8.8.8.8")));
        assertEquals("192.168.40.1,2001:db8::1", Dns.helperArg(Arrays.asList("192.168.40.1", "2001:db8::1")));
    }
}
