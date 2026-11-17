package com.seekstars.muselauncher;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProxySpecTest {
    @Test public void normalizesAndSeparatesCredentials() {
        assertEquals("http://localhost:7890", ProxySpec.parse(" localhost:7890 ").value);
        assertEquals("", ProxySpec.parse(null).rule);
        ProxySpec proxy = ProxySpec.parse("HTTPS://reader:a%3Ab%40c@EXAMPLE.COM:443");
        assertEquals("https://example.com:443", proxy.rule);
        assertEquals("a:b@c", proxy.password);
        assertTrue(proxy.authenticates("example.com"));
        assertFalse(proxy.authenticates("muse.ai"));
        assertEquals("socks5://[::1]:1080", ProxySpec.parse("socks5://[::1]").rule);
    }

    @Test public void rejectsInvalidOrUnsupportedProxyWithoutEchoingSecret() {
        String[] bad = {"ftp://localhost", "http://localhost:0", "http://localhost:65536",
                "http://localhost:", "http://", "http://localhost/path", "http://localhost?q=x",
                "http://localhost#x", "socks5://reader:secret@localhost", "http://reader:%0A@localhost"};
        for (String input : bad) {
            try { ProxySpec.parse(input); fail(input); }
            catch (IllegalArgumentException e) { assertFalse(e.getMessage().contains("secret")); }
        }
    }
}
