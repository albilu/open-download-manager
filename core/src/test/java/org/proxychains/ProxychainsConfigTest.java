package org.proxychains;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProxychainsConfig class.
 * Tests configuration file creation, parsing, proxy management, and various settings.
 */
@DisplayName("ProxychainsConfig Unit Tests")
class ProxychainsConfigTest {

    @TempDir
    Path tempDir;

    private ProxychainsConfig config;

    @BeforeEach
    void setUp() {
        config = new ProxychainsConfig();
    }

    @AfterEach
    void tearDown() {
        // Clean up any temporary files
        config = null;
    }

    @Test
    @DisplayName("Should create config with default settings")
    void shouldCreateConfigWithDefaults() {
        assertEquals(ProxychainsConfig.ChainType.DYNAMIC, config.getChainType());
        assertTrue(config.isProxyDns());
        assertEquals(15000, config.getTcpReadTimeout());
        assertEquals(8000, config.getTcpConnectTimeout());
        assertTrue(config.getProxyList().isEmpty());
        assertNull(config.getConfigPath());
    }

    @Test
    @DisplayName("Should set and get chain type")
    void shouldSetAndGetChainType() {
        ProxychainsConfig result = config.setChainType(ProxychainsConfig.ChainType.STRICT);

        assertSame(config, result); // Should return this for method chaining
        assertEquals(ProxychainsConfig.ChainType.STRICT, config.getChainType());
    }

    @ParameterizedTest
    @DisplayName("Should handle all chain types")
    @EnumSource(ProxychainsConfig.ChainType.class)
    void shouldHandleAllChainTypes(ProxychainsConfig.ChainType chainType) {
        config.setChainType(chainType);
        assertEquals(chainType, config.getChainType());
    }

    @Test
    @DisplayName("Should set and get proxy DNS setting")
    void shouldSetAndGetProxyDns() {
        ProxychainsConfig result = config.setProxyDns(false);

        assertSame(config, result);
        assertFalse(config.isProxyDns());

        config.setProxyDns(true);
        assertTrue(config.isProxyDns());
    }

    @Test
    @DisplayName("Should set and get TCP timeouts")
    void shouldSetAndGetTcpTimeouts() {
        config.setTcpReadTimeout(20000);
        config.setTcpConnectTimeout(10000);

        assertEquals(20000, config.getTcpReadTimeout());
        assertEquals(10000, config.getTcpConnectTimeout());
    }

    @Test
    @DisplayName("Should add proxy without authentication")
    void shouldAddProxyWithoutAuth() {
        ProxychainsConfig result = config.addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050);

        assertSame(config, result);
        assertEquals(1, config.getProxyList().size());

        ProxychainsConfig.ProxyEntry proxy = config.getProxyList().get(0);
        assertEquals(ProxychainsConfig.ProxyType.SOCKS5, proxy.getType());
        assertEquals("127.0.0.1", proxy.getHost());
        assertEquals(9050, proxy.getPort());
        assertNull(proxy.getUsername());
        assertNull(proxy.getPassword());
        assertFalse(proxy.hasAuthentication());
    }

    @Test
    @DisplayName("Should add proxy with authentication")
    void shouldAddProxyWithAuth() {
        config.addProxy(ProxychainsConfig.ProxyType.HTTP, "proxy.example.com", 8080, "testuser", "testpass");

        assertEquals(1, config.getProxyList().size());

        ProxychainsConfig.ProxyEntry proxy = config.getProxyList().get(0);
        assertEquals(ProxychainsConfig.ProxyType.HTTP, proxy.getType());
        assertEquals("proxy.example.com", proxy.getHost());
        assertEquals(8080, proxy.getPort());
        assertEquals("testuser", proxy.getUsername());
        assertEquals("testpass", proxy.getPassword());
        assertTrue(proxy.hasAuthentication());
    }

    @Test
    @DisplayName("Should add multiple proxies")
    void shouldAddMultipleProxies() {
        config.addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050)
              .addProxy(ProxychainsConfig.ProxyType.HTTP, "proxy.example.com", 8080, "user", "pass")
              .addProxy(ProxychainsConfig.ProxyType.SOCKS4, "another.proxy.com", 1080);

        assertEquals(3, config.getProxyList().size());

        // Verify order is maintained
        assertEquals(ProxychainsConfig.ProxyType.SOCKS5, config.getProxyList().get(0).getType());
        assertEquals(ProxychainsConfig.ProxyType.HTTP, config.getProxyList().get(1).getType());
        assertEquals(ProxychainsConfig.ProxyType.SOCKS4, config.getProxyList().get(2).getType());
    }

    @Test
    @DisplayName("Should clear all proxies")
    void shouldClearAllProxies() {
        config.addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050)
              .addProxy(ProxychainsConfig.ProxyType.HTTP, "proxy.example.com", 8080);

        assertEquals(2, config.getProxyList().size());

        ProxychainsConfig result = config.clearProxies();

        assertSame(config, result);
        assertTrue(config.getProxyList().isEmpty());
    }

    @Test
    @DisplayName("Should return unmodifiable proxy list")
    void shouldReturnUnmodifiableProxyList() {
        config.addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050);

        List<ProxychainsConfig.ProxyEntry> proxyList = config.getProxyList();

        assertThrows(UnsupportedOperationException.class, () -> {
            proxyList.add(new ProxychainsConfig.ProxyEntry(
                ProxychainsConfig.ProxyType.HTTP, "test.com", 8080, null, null));
        });
    }

    @Test
    @DisplayName("Should create temporary config file")
    void shouldCreateTempConfigFile() throws IOException {
        config.addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050)
              .setChainType(ProxychainsConfig.ChainType.STRICT)
              .setProxyDns(true)
              .setTcpReadTimeout(20000)
              .setTcpConnectTimeout(12000);

        Path configFile = config.createTempConfig();

        assertNotNull(configFile);
        assertTrue(Files.exists(configFile));
        assertEquals(configFile, config.getConfigPath());

        // Verify file content
        String content = Files.readString(configFile);
        assertTrue(content.contains("strict_chain"));
        assertTrue(content.contains("proxy_dns"));
        assertTrue(content.contains("tcp_read_time_out 20000"));
        assertTrue(content.contains("tcp_connect_time_out 12000"));
        assertTrue(content.contains("[ProxyList]"));
        assertTrue(content.contains("socks5 127.0.0.1 9050"));
    }

    @Test
    @DisplayName("Should save config to specified file")
    void shouldSaveConfigToFile() throws IOException {
        Path configFile = tempDir.resolve("test-proxychains.conf");

        config.addProxy(ProxychainsConfig.ProxyType.HTTP, "proxy.test.com", 8080, "user", "pass")
              .setChainType(ProxychainsConfig.ChainType.RANDOM)
              .setProxyDns(false);

        config.saveToFile(configFile);

        assertTrue(Files.exists(configFile));

        String content = Files.readString(configFile);
        assertTrue(content.contains("random_chain"));
        assertFalse(content.contains("proxy_dns"));
        assertTrue(content.contains("http proxy.test.com 8080 user pass"));
    }

    @Test
    @DisplayName("Should load config from existing file")
    void shouldLoadConfigFromFile() throws IOException {
        Path configFile = tempDir.resolve("existing-config.conf");

        String configContent = """
            strict_chain
            proxy_dns
            tcp_read_time_out 25000
            tcp_connect_time_out 15000

            [ProxyList]
            socks5 127.0.0.1 9050
            http proxy.example.com 8080 testuser testpass
            socks4 another.proxy.com 1080
            """;

        Files.writeString(configFile, configContent);

        ProxychainsConfig loadedConfig = new ProxychainsConfig(configFile);

        assertEquals(ProxychainsConfig.ChainType.STRICT, loadedConfig.getChainType());
        assertTrue(loadedConfig.isProxyDns());
        assertEquals(25000, loadedConfig.getTcpReadTimeout());
        assertEquals(15000, loadedConfig.getTcpConnectTimeout());
        assertEquals(configFile, loadedConfig.getConfigPath());

        List<ProxychainsConfig.ProxyEntry> proxies = loadedConfig.getProxyList();
        assertEquals(3, proxies.size());

        // Verify first proxy
        ProxychainsConfig.ProxyEntry proxy1 = proxies.get(0);
        assertEquals(ProxychainsConfig.ProxyType.SOCKS5, proxy1.getType());
        assertEquals("127.0.0.1", proxy1.getHost());
        assertEquals(9050, proxy1.getPort());
        assertFalse(proxy1.hasAuthentication());

        // Verify second proxy with auth
        ProxychainsConfig.ProxyEntry proxy2 = proxies.get(1);
        assertEquals(ProxychainsConfig.ProxyType.HTTP, proxy2.getType());
        assertEquals("proxy.example.com", proxy2.getHost());
        assertEquals(8080, proxy2.getPort());
        assertEquals("testuser", proxy2.getUsername());
        assertEquals("testpass", proxy2.getPassword());
        assertTrue(proxy2.hasAuthentication());

        // Verify third proxy
        ProxychainsConfig.ProxyEntry proxy3 = proxies.get(2);
        assertEquals(ProxychainsConfig.ProxyType.SOCKS4, proxy3.getType());
        assertEquals("another.proxy.com", proxy3.getHost());
        assertEquals(1080, proxy3.getPort());
    }

    @Test
    @DisplayName("Should handle config file with comments and empty lines")
    void shouldHandleConfigFileWithCommentsAndEmptyLines() throws IOException {
        Path configFile = tempDir.resolve("config-with-comments.conf");

        String configContent = """
            # This is a comment
            dynamic_chain
            # Another comment

            proxy_dns

            # Timeout settings
            tcp_read_time_out 30000
            tcp_connect_time_out 20000

            [ProxyList]
            # SOCKS5 proxy
            socks5 127.0.0.1 9050
            # HTTP proxy with auth
            http proxy.example.com 8080 user pass
            """;

        Files.writeString(configFile, configContent);

        ProxychainsConfig loadedConfig = new ProxychainsConfig(configFile);

        assertEquals(ProxychainsConfig.ChainType.DYNAMIC, loadedConfig.getChainType());
        assertTrue(loadedConfig.isProxyDns());
        assertEquals(30000, loadedConfig.getTcpReadTimeout());
        assertEquals(20000, loadedConfig.getTcpConnectTimeout());
        assertEquals(2, loadedConfig.getProxyList().size());
    }

    @Test
    @DisplayName("Should handle invalid timeout values in config file")
    void shouldHandleInvalidTimeoutValues() throws IOException {
        Path configFile = tempDir.resolve("invalid-timeouts.conf");

        String configContent =
            "dynamic_chain\n" +
            "tcp_read_time_out invalid_value\n" +
            "tcp_connect_time_out not_a_number\n" +
            "\n" +
            "[ProxyList]\n" +
            "socks5 127.0.0.1 9050\n";

        Files.writeString(configFile, configContent);

        // Should not throw exception, should use default values
        ProxychainsConfig loadedConfig = new ProxychainsConfig(configFile);

        assertEquals(15000, loadedConfig.getTcpReadTimeout()); // Default value
        assertEquals(8000, loadedConfig.getTcpConnectTimeout()); // Default value
    }

    @Test
    @DisplayName("Should handle invalid proxy entries gracefully")
    void shouldHandleInvalidProxyEntries() throws IOException {
        Path configFile = tempDir.resolve("invalid-proxies.conf");

        String configContent =
            "dynamic_chain\n" +
            "\n" +
            "[ProxyList]\n" +
            "socks5 127.0.0.1 9050\n" +
            "invalid_line_without_port 127.0.0.1\n" +
            "http proxy.example.com invalid_port\n" +
            "socks4 valid.proxy.com 1080\n";

        Files.writeString(configFile, configContent);

        ProxychainsConfig loadedConfig = new ProxychainsConfig(configFile);

        // Should only load valid proxy entries
        assertEquals(2, loadedConfig.getProxyList().size());
        assertEquals("127.0.0.1", loadedConfig.getProxyList().get(0).getHost());
        assertEquals("valid.proxy.com", loadedConfig.getProxyList().get(1).getHost());
    }

    @ParameterizedTest
    @DisplayName("Should parse proxy strings correctly")
    @CsvSource({
        "socks5://127.0.0.1:9050, SOCKS5, 127.0.0.1, 9050, , ",
        "http://proxy.example.com:8080, HTTP, proxy.example.com, 8080, , ",
        "socks4://proxy.test.com:1080, SOCKS4, proxy.test.com, 1080, , ",
        "socks5://user:pass@127.0.0.1:9050, SOCKS5, 127.0.0.1, 9050, user, pass",
        "http://testuser:testpass@proxy.example.com:8080, HTTP, proxy.example.com, 8080, testuser, testpass"
    })
    void shouldParseProxyStrings(String proxyString, ProxychainsConfig.ProxyType expectedType,
                                String expectedHost, int expectedPort, String expectedUser, String expectedPass) {
        config.parseProxyString(proxyString);

        assertEquals(1, config.getProxyList().size());

        ProxychainsConfig.ProxyEntry proxy = config.getProxyList().get(0);
        assertEquals(expectedType, proxy.getType());
        assertEquals(expectedHost, proxy.getHost());
        assertEquals(expectedPort, proxy.getPort());
        assertEquals(expectedUser, proxy.getUsername());
        assertEquals(expectedPass, proxy.getPassword());
    }

    @ParameterizedTest
    @DisplayName("Should handle invalid proxy strings gracefully")
    @ValueSource(strings = {
        "",
        "invalid://no-port",
        "unknown-type://127.0.0.1:9050",
        "socks5://invalid-host-port-format",
        "http://user@no-port",
        "malformed-url"
    })
    void shouldHandleInvalidProxyStrings(String invalidProxyString) {
        // Should not throw exception
        assertDoesNotThrow(() -> config.parseProxyString(invalidProxyString));

        // Should not add any proxies
        assertTrue(config.getProxyList().isEmpty());
    }

    @Test
    @DisplayName("Should handle null and empty proxy strings")
    void shouldHandleNullAndEmptyProxyStrings() {
        ProxychainsConfig result1 = config.parseProxyString(null);
        ProxychainsConfig result2 = config.parseProxyString("");

        assertSame(config, result1);
        assertSame(config, result2);
        assertTrue(config.getProxyList().isEmpty());
    }

    @ParameterizedTest
    @DisplayName("Should convert proxy type from string")
    @CsvSource({
        "http, HTTP",
        "HTTP, HTTP",
        "https, HTTPS",
        "HTTPS, HTTPS",
        "socks4, SOCKS4",
        "SOCKS4, SOCKS4",
        "socks5, SOCKS5",
        "SOCKS5, SOCKS5"
    })
    void shouldConvertProxyTypeFromString(String input, ProxychainsConfig.ProxyType expected) {
        assertEquals(expected, ProxychainsConfig.ProxyType.fromString(input));
    }

    @Test
    @DisplayName("Should return null for unknown proxy type")
    void shouldReturnNullForUnknownProxyType() {
        assertNull(ProxychainsConfig.ProxyType.fromString("unknown"));
        assertNull(ProxychainsConfig.ProxyType.fromString(""));
        assertNull(ProxychainsConfig.ProxyType.fromString(null));
    }

    @Test
    @DisplayName("Should get proxy type values")
    void shouldGetProxyTypeValues() {
        assertEquals("http", ProxychainsConfig.ProxyType.HTTP.getValue());
        assertEquals("https", ProxychainsConfig.ProxyType.HTTPS.getValue());
        assertEquals("socks4", ProxychainsConfig.ProxyType.SOCKS4.getValue());
        assertEquals("socks5", ProxychainsConfig.ProxyType.SOCKS5.getValue());
    }

    @Test
    @DisplayName("Should get chain type values")
    void shouldGetChainTypeValues() {
        assertEquals("dynamic_chain", ProxychainsConfig.ChainType.DYNAMIC.getValue());
        assertEquals("strict_chain", ProxychainsConfig.ChainType.STRICT.getValue());
        assertEquals("random_chain", ProxychainsConfig.ChainType.RANDOM.getValue());
    }

    @Test
    @DisplayName("Should format proxy entry without authentication")
    void shouldFormatProxyEntryWithoutAuth() {
        ProxychainsConfig.ProxyEntry proxy = new ProxychainsConfig.ProxyEntry(
            ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050, null, null);

        assertEquals("socks5 127.0.0.1 9050", proxy.toString());
        assertFalse(proxy.hasAuthentication());
    }

    @Test
    @DisplayName("Should format proxy entry with authentication")
    void shouldFormatProxyEntryWithAuth() {
        ProxychainsConfig.ProxyEntry proxy = new ProxychainsConfig.ProxyEntry(
            ProxychainsConfig.ProxyType.HTTP, "proxy.example.com", 8080, "user", "pass");

        assertEquals("http proxy.example.com 8080 user pass", proxy.toString());
        assertTrue(proxy.hasAuthentication());
    }

    @Test
    @DisplayName("Should detect authentication correctly")
    void shouldDetectAuthenticationCorrectly() {
        // No auth - both null
        ProxychainsConfig.ProxyEntry proxy1 = new ProxychainsConfig.ProxyEntry(
            ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050, null, null);
        assertFalse(proxy1.hasAuthentication());

        // No auth - empty strings
        ProxychainsConfig.ProxyEntry proxy2 = new ProxychainsConfig.ProxyEntry(
            ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050, "", "");
        assertFalse(proxy2.hasAuthentication());

        // No auth - missing password
        ProxychainsConfig.ProxyEntry proxy3 = new ProxychainsConfig.ProxyEntry(
            ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050, "user", null);
        assertFalse(proxy3.hasAuthentication());

        // No auth - missing username
        ProxychainsConfig.ProxyEntry proxy4 = new ProxychainsConfig.ProxyEntry(
            ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050, null, "pass");
        assertFalse(proxy4.hasAuthentication());

        // Has auth
        ProxychainsConfig.ProxyEntry proxy5 = new ProxychainsConfig.ProxyEntry(
            ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050, "user", "pass");
        assertTrue(proxy5.hasAuthentication());
    }

    @Test
    @DisplayName("Should find default config paths")
    void shouldFindDefaultConfigPaths() {
        // This test depends on the system, so we just verify it doesn't throw
        assertDoesNotThrow(() -> {
            Path defaultPath = ProxychainsConfig.getDefaultConfigPath();
            // Could be null if no default config exists, which is fine
        });
    }

    @Test
    @DisplayName("Should handle file not found exception when loading config")
    void shouldHandleFileNotFound() {
        Path nonExistentFile = tempDir.resolve("non-existent.conf");

        assertThrows(IOException.class, () -> new ProxychainsConfig(nonExistentFile));
    }

    @Test
    @DisplayName("Should maintain proxy entry immutability")
    void shouldMaintainProxyEntryImmutability() {
        ProxychainsConfig.ProxyEntry proxy = new ProxychainsConfig.ProxyEntry(
            ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050, "user", "pass");

        // Verify getters return the correct values
        assertEquals(ProxychainsConfig.ProxyType.SOCKS5, proxy.getType());
        assertEquals("127.0.0.1", proxy.getHost());
        assertEquals(9050, proxy.getPort());
        assertEquals("user", proxy.getUsername());
        assertEquals("pass", proxy.getPassword());

        // ProxyEntry should be immutable (no setters)
        // This is verified by the fact that all fields are final and there are only getters
    }
}
