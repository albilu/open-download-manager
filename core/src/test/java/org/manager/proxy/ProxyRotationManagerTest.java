package org.manager.proxy;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for ProxyRotationManager: list loading, rotation exclusion, and
 * pool management.
 */
class ProxyRotationManagerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Loads proxies from file, skipping comments, blanks and duplicates")
    void loadsProxiesFromFile() throws Exception {
        Path proxyFile = tempDir.resolve("proxy-list.txt");
        Files.writeString(proxyFile, """
                # comment line
                http://proxy1.example.com:8080

                http://user:pass@proxy2.example.com:3128
                socks5://proxy3.example.com:1080
                http://proxy1.example.com:8080
                http://:badport
                """);

        ProxyRotationManager manager = new ProxyRotationManager();
        int loaded = manager.loadProxiesFromFile(proxyFile);

        assertEquals(3, loaded, "three valid, unique proxies must load");
        assertEquals(3, manager.getPoolSize());
    }

    @Test
    @DisplayName("Alternative proxy excludes the current one")
    void alternativeProxyExcludesCurrent() {
        ProxyRotationManager manager = new ProxyRotationManager();
        Proxy first = new Proxy("proxy1.example.com", 8080, Proxy.Type.HTTP);
        Proxy second = new Proxy("proxy2.example.com", 8081, Proxy.Type.HTTP);
        manager.addProxy(first);
        manager.addProxy(second);

        Proxy alternative = manager.getAlternativeProxy(first);

        assertNotNull(alternative);
        assertNotEquals(first, alternative, "rotation must pick a different proxy");
    }

    @Test
    @DisplayName("Empty pool behavior is explicit")
    void emptyPoolBehavior() {
        ProxyRotationManager manager = new ProxyRotationManager();
        assertTrue(manager.isEmpty());
        assertEquals(0, manager.getPoolSize());

        assertTrue(manager.addProxy(new Proxy("proxy1.example.com", 8080, Proxy.Type.HTTP)));
        assertFalse(manager.isEmpty());
        assertFalse(manager.addProxy(new Proxy("proxy1.example.com", 8080, Proxy.Type.HTTP)),
                "duplicates must be rejected");
        assertEquals(1, manager.getPoolSize());
    }
}
