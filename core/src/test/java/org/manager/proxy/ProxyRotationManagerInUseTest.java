package org.manager.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concurrency contract for the in-use registry: multiple downloads may share
 * one proxy, so releasing one download must not free the proxy while another
 * still uses it. And a proxy removed for excessive failures must end up in
 * the terminal BLOCKED tier (previously unreachable: removal happened at 5
 * failures while BLOCKED required 10).
 */
@DisplayName("ProxyRotationManager in-use refcounting and BLOCKED semantics")
class ProxyRotationManagerInUseTest {

    @Test
    @DisplayName("A shared proxy stays in use until every download releases it")
    void sharedProxyRefCounted() {
        ProxyRotationManager manager = new ProxyRotationManager();
        Proxy shared = new Proxy("shared.example.com", 8080, Proxy.Type.HTTP);
        manager.addProxy(shared);

        manager.markProxyInUse(shared, "download-a");
        manager.markProxyInUse(shared, "download-b");
        assertEquals(1, ((Number) manager.getStatistics().get("proxiesInUse")).intValue(),
                "one proxy in use (shared by two downloads)");

        manager.releaseProxy(shared, "download-a");
        assertEquals(1, ((Number) manager.getStatistics().get("proxiesInUse")).intValue(),
                "proxy must remain in use while download-b still holds it");

        // While still in use, selection must prefer other proxies
        Proxy other = new Proxy("other.example.com", 8081, Proxy.Type.HTTP);
        manager.addProxy(other);
        for (int i = 0; i < 10; i++) {
            Proxy picked = manager.getRandomProxy();
            if (picked != null && !picked.getAddress().equals(shared.getAddress())) {
                break;
            }
            assertEquals("other.example.com", picked.getAddress(),
                    "selection must avoid the still-in-use shared proxy when alternatives exist");
        }

        manager.releaseProxy(shared, "download-b");
        assertEquals(0, ((Number) manager.getStatistics().get("proxiesInUse")).intValue(),
                "proxy becomes available once the last holder releases it");
    }

    @Test
    @DisplayName("A proxy removed for excessive failures is BLOCKED, not silently gone")
    void removedProxyEndsBlocked() {
        ProxyRotationManager manager = new ProxyRotationManager(java.util.List.of(), 3, 30, false);
        Proxy flaky = new Proxy("flaky.example.com", 8080, Proxy.Type.HTTP);
        manager.addProxy(flaky);

        for (int i = 0; i < 3; i++) {
            manager.recordFailure(flaky, "connect timeout");
        }

        assertEquals(0, manager.getPoolSize(), "excessive failures must remove the proxy");
        assertEquals(Proxy.Status.BLOCKED, flaky.getStatus(),
                "the removed proxy must carry the terminal BLOCKED status");
        assertFalse(manager.getStatistics().containsValue(null));
        assertTrue(flaky.getFailureCount() >= 3);
    }
}
