package org.proxychains;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proxy URL parsing must survive real-world shapes: bracketed IPv6 hosts,
 * passwords containing colons, and credentials on IPv6 hosts. The old
 * split("://")/@/":" pipeline mis-parsed all three.
 */
@DisplayName("ProxychainsConfig parses IPv6 and separator-bearing proxies")
class ProxychainsConfigParseTest {

    private static ProxychainsConfig.ProxyEntry sole(ProxychainsConfig config) {
        return config.getProxyList().get(0);
    }

    @Test
    @DisplayName("Bracketed IPv6 host with port")
    void bracketedIpv6WithPort() {
        ProxychainsConfig config = new ProxychainsConfig().parseProxyString("socks5://[2001:db8::1]:1080");
        assertEquals(1, config.getProxyList().size());
        assertEquals("2001:db8::1", sole(config).getHost());
        assertEquals(1080, sole(config).getPort());
    }

    @Test
    @DisplayName("IPv4 with colon-bearing password")
    void colonBearingPassword() {
        ProxychainsConfig config = new ProxychainsConfig()
                .parseProxyString("http://user:pa:ss@10.0.0.5:8080");
        assertEquals(1, config.getProxyList().size());
        assertEquals("10.0.0.5", sole(config).getHost());
        assertEquals(8080, sole(config).getPort());
        assertEquals("user", sole(config).getUsername());
        assertEquals("pa:ss", sole(config).getPassword());
    }

    @Test
    @DisplayName("Credentials with a bracketed IPv6 host")
    void credentialsWithIpv6() {
        ProxychainsConfig config = new ProxychainsConfig()
                .parseProxyString("socks5://alice:secret@[fe80::a:1]:1080");
        assertEquals(1, config.getProxyList().size());
        assertEquals("fe80::a:1", sole(config).getHost());
        assertEquals("alice", sole(config).getUsername());
        assertEquals("secret", sole(config).getPassword());
    }

    @Test
    @DisplayName("Legacy plain IPv4 form keeps working")
    void legacyPlainFormStillWorks() {
        ProxychainsConfig config = new ProxychainsConfig().parseProxyString("socks4://1.2.3.4:1080");
        assertEquals(1, config.getProxyList().size());
        assertTrue(config.getProxyList().get(0).getHost().contains("1.2.3.4"));
    }
}
