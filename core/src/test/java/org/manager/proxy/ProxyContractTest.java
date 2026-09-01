package org.manager.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Proxy parsing, health tracking and URL round-trip")
class ProxyContractTest {

    @Test
    @DisplayName("fromUrl parses every supported scheme")
    void schemeParsing() {
        assertEquals(Proxy.Type.HTTP, Proxy.fromUrl("http://1.2.3.4:8080").getType());
        assertEquals(Proxy.Type.HTTPS, Proxy.fromUrl("https://1.2.3.4:8443").getType());
        assertEquals(Proxy.Type.SOCKS4, Proxy.fromUrl("socks4://1.2.3.4:1080").getType());
        assertEquals(Proxy.Type.SOCKS5, Proxy.fromUrl("socks5://1.2.3.4:1080").getType());
        assertEquals(Proxy.Type.SOCKS5, Proxy.fromUrl("socks5h://1.2.3.4:1080").getType(),
                "socks5h means remote DNS but still a SOCKS5 proxy");
        assertEquals(Proxy.Type.HTTP, Proxy.fromUrl("1.2.3.4:3128").getType(),
                "scheme-less URLs default to HTTP");
    }

    @Test
    @DisplayName("fromUrl extracts credentials and strips them from the host")
    void credentialParsing() {
        Proxy proxy = Proxy.fromUrl("http://alice:s3cret@proxy.corp:3128");
        assertEquals("proxy.corp", proxy.getHost());
        assertEquals(3128, proxy.getPort());
        assertEquals("alice", proxy.getUsername());
        assertEquals("s3cret", proxy.getPassword());
        assertTrue(proxy.hasAuthentication());

        Proxy userOnly = Proxy.fromUrl("socks5://bob@1.2.3.4:1080");
        assertEquals("bob", userOnly.getUsername());
        assertNull(userOnly.getPassword());
        assertTrue(userOnly.hasAuthentication());

        Proxy anonymous = Proxy.fromUrl("http://1.2.3.4:8080");
        assertNull(anonymous.getUsername());
        assertFalse(anonymous.hasAuthentication());
    }

    @Test
    @DisplayName("default ports are applied per type when the URL has none")
    void defaultPorts() {
        assertEquals(8080, Proxy.fromUrl("http://proxy").getPort());
        assertEquals(8443, Proxy.fromUrl("https://proxy").getPort());
        assertEquals(1080, Proxy.fromUrl("socks5://proxy").getPort());
    }

    @Test
    @DisplayName("invalid proxy URLs are rejected")
    void invalidUrlsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Proxy.fromUrl(null));
        assertThrows(IllegalArgumentException.class, () -> Proxy.fromUrl("   "));
        assertThrows(IllegalArgumentException.class, () -> Proxy.fromUrl("http://host:notaport"));
        assertThrows(IllegalArgumentException.class, () -> Proxy.fromUrl("http://host:99999"));
        assertThrows(IllegalArgumentException.class, () -> Proxy.fromUrl("http://host:0"));
        assertThrows(IllegalArgumentException.class, () -> Proxy.fromUrl("http://:8080"));
        assertThrows(IllegalArgumentException.class, () -> new Proxy("host", 70000, Proxy.Type.HTTP));
        assertThrows(IllegalArgumentException.class, () -> new Proxy("host", 8080, null));
    }

    @Test
    @DisplayName("health recording transitions UNKNOWN -> HEALTHY -> UNHEALTHY -> BLOCKED")
    void healthTransitions() {
        Proxy proxy = new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP);
        assertEquals(Proxy.Status.UNKNOWN, proxy.getStatus());
        assertTrue(proxy.isHealthy(), "unknown proxies get a chance");

        proxy.recordSuccess(100);
        assertEquals(Proxy.Status.HEALTHY, proxy.getStatus());
        assertEquals(1, proxy.getSuccessCount());
        assertEquals(100, proxy.getAverageResponseTime());
        assertNull(proxy.getLastError());
        assertTrue(proxy.isHealthy());

        proxy.recordFailure("timeout");
        assertEquals(Proxy.Status.HEALTHY, proxy.getStatus(), "a single failure must not demote a healthy proxy");
        assertEquals(1, proxy.getFailureCount());
        assertEquals("timeout", proxy.getLastError());

        proxy.recordFailure("timeout");
        proxy.recordFailure("timeout");
        assertEquals(Proxy.Status.UNHEALTHY, proxy.getStatus(), "3 failures mark the proxy unhealthy");
        assertFalse(proxy.isHealthy());

        for (int i = 0; i < 7; i++) {
            proxy.recordFailure("timeout");
        }
        assertEquals(Proxy.Status.BLOCKED, proxy.getStatus(), "10 failures block the proxy permanently");
        assertEquals(0.0, proxy.getHealthScore(), 1e-9);
    }

    @Test
    @DisplayName("average response time is a running average and resets cleanly")
    void responseTimeAveraging() {
        Proxy proxy = new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP);
        proxy.recordSuccess(100);
        proxy.recordSuccess(300);
        assertEquals(200, proxy.getAverageResponseTime());
        proxy.recordSuccess(400);
        assertEquals(300, proxy.getAverageResponseTime());

        proxy.reset();
        assertEquals(Proxy.Status.UNKNOWN, proxy.getStatus());
        assertEquals(0, proxy.getFailureCount());
        assertEquals(0, proxy.getSuccessCount());
        assertEquals(0, proxy.getAverageResponseTime());
        assertNull(proxy.getLastError());
    }

    @Test
    @DisplayName("health score prefers success rate and punishes slow responses")
    void healthScoring() {
        Proxy unknown = new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP);
        assertEquals(0.5, unknown.getHealthScore(), 1e-9, "never-tested proxies score 0.5");

        Proxy perfect = new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP);
        perfect.recordSuccess(100);
        assertEquals(0.7 * 1.0 + 0.3 * (1.0 - 100.0 / 10000.0), perfect.getHealthScore(), 1e-9,
                "perfect success rate with fast responses scores near the maximum");

        Proxy slow = new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP);
        slow.recordSuccess(20_000);
        assertEquals(0.7 * 1.0 + 0.3 * 0.1, slow.getHealthScore(), 1e-9,
                "response score floors at 0.1");

        Proxy flaky = new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP);
        flaky.recordSuccess(100);
        flaky.recordFailure("x");
        assertEquals(0.5 * 0.7 + 0.3 * (1.0 - 100.0 / 10000.0) * 1.0, flaky.getHealthScore(), 1e-2);

        Proxy unhealthy = new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP);
        unhealthy.recordFailure("x");
        unhealthy.recordFailure("x");
        unhealthy.recordFailure("x");
        assertEquals(0.1, unhealthy.getHealthScore(), 1e-9);
    }

    @Test
    @DisplayName("toUrl serializes scheme, credentials and remote-DNS socks5 form")
    void toUrlRoundTrip() {
        assertEquals("http://1.2.3.4:8080", new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP).toUrl());
        assertEquals("https://1.2.3.4:8443", new Proxy("1.2.3.4", 8443, Proxy.Type.HTTPS).toUrl());
        assertEquals("socks4://1.2.3.4:1080", new Proxy("1.2.3.4", 1080, Proxy.Type.SOCKS4).toUrl());
        assertEquals("socks5h://1.2.3.4:1080", new Proxy("1.2.3.4", 1080, Proxy.Type.SOCKS5).toUrl(),
                "SOCKS5 must serialize as socks5h so DNS resolves at the proxy");
        assertEquals("http://alice:pw@1.2.3.4:8080",
                new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP, "alice", "pw").toUrl());
        assertEquals("http://alice@1.2.3.4:8080",
                new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP, "alice", null).toUrl(),
                "username without password serializes without a colon");

        assertEquals("1.2.3.4:8080", new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP).getAddress());
    }

    @Test
    @DisplayName("equality is by endpoint and identity, excluding health state")
    void equalityContract() {
        Proxy a = new Proxy("1.2.3.4", 8080, Proxy.Type.HTTP, "u", null);
        Proxy b = Proxy.fromUrl("http://u@1.2.3.4:8080");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        a.recordSuccess(10);
        assertEquals(a, b, "health changes must not affect identity");

        assertNotEquals(a, new Proxy("1.2.3.4", 8081, Proxy.Type.HTTP));
        assertNotEquals(a, new Proxy("1.2.3.4", 8080, Proxy.Type.SOCKS5));
        assertNotEquals(a, new Proxy("5.6.7.8", 8080, Proxy.Type.HTTP));
        assertNotEquals(a, null);
        assertNotEquals(a, "http://1.2.3.4:8080");
    }

    @Test
    @DisplayName("host input is trimmed; username too")
    void trimmingAndToString() {
        Proxy proxy = new Proxy("  1.2.3.4  ", 8080, Proxy.Type.HTTP, "  bob ", null);
        assertEquals("1.2.3.4", proxy.getHost());
        assertEquals("bob", proxy.getUsername());

        String text = proxy.toString();
        assertTrue(text.contains("1.2.3.4:8080"));
        assertTrue(text.contains("status=UNKNOWN"));
    }
}
