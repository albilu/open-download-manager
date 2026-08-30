package org.aria2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.download.DownloadSettings;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Aria2Settings class.
 * Tests all configuration options, method chaining, serialization, and validation.
 */
@DisplayName("Aria2Settings Unit Tests")
class Aria2SettingsTest {

    private Aria2Settings settings;

    @BeforeEach
    void setUp() {
        settings = new Aria2Settings();
    }

    @Test
    @DisplayName("Should create settings with default values")
    void shouldCreateSettingsWithDefaults() {
        assertEquals(5, settings.getMaxConnectionPerServer());
        assertTrue(settings.isContinueDownload());
        assertEquals(20, settings.getMinSplitSize());
        assertEquals("prealloc", settings.getFileAllocation());
        assertTrue(settings.isEnableRpc());
        assertEquals(6800, settings.getRpcPort());
        assertFalse(settings.isCheckIntegrity());
        assertEquals(5, settings.getRetryWait());
        assertEquals(5, settings.getMaxTries());
        assertEquals(60, settings.getTimeout());
        assertFalse(settings.isAllowOverwrite());
        assertTrue(settings.isAutoFileRenaming());
        assertTrue(settings.isFollowMetalink());
        assertTrue(settings.isFollowTorrent());
        assertTrue(settings.isUseBt());
        assertEquals(55, settings.getBtMaxPeers());
        assertEquals(50, settings.getBtRequestPeerSpeedLimit());
        assertFalse(settings.isSeedRatio());
        assertEquals(0.0, settings.getSeedTime());
    }

    @Test
    @DisplayName("Should support method chaining for all setters")
    void shouldSupportMethodChaining() {
        Aria2Settings result = settings
                .setMaxConnectionPerServer(10)
                .setContinueDownload(false)
                .setMinSplitSize(50)
                .setFileAllocation("falloc")
                .setEnableRpc(false)
                .setRpcPort(7800)
                .setCheckIntegrity(true)
                .setRetryWait(10)
                .setMaxTries(3)
                .setTimeout(120)
                .setAllowOverwrite(true)
                .setAutoFileRenaming(false)
                .setFollowMetalink(false)
                .setFollowTorrent(false)
                .setUseBt(false)
                .setBtMaxPeers(100)
                .setBtRequestPeerSpeedLimit(100)
                .setSeedRatio(true)
                .setSeedTime(60.0);

        assertSame(settings, result);
    }

    @ParameterizedTest
    @DisplayName("Should validate max connection per server values")
    @ValueSource(ints = {1, 5, 10, 16, 50})
    void shouldSetMaxConnectionPerServer(int connections) {
        settings.setMaxConnectionPerServer(connections);
        assertEquals(connections, settings.getMaxConnectionPerServer());
    }

    @ParameterizedTest
    @DisplayName("Should validate min split size values")
    @ValueSource(ints = {1, 10, 20, 50, 100})
    void shouldSetMinSplitSize(int size) {
        settings.setMinSplitSize(size);
        assertEquals(size, settings.getMinSplitSize());
    }

    @ParameterizedTest
    @DisplayName("Should validate file allocation methods")
    @ValueSource(strings = {"prealloc", "falloc", "none", "trunc"})
    void shouldSetFileAllocation(String allocation) {
        settings.setFileAllocation(allocation);
        assertEquals(allocation, settings.getFileAllocation());
    }

    @ParameterizedTest
    @DisplayName("Should validate RPC port values")
    @ValueSource(ints = {1024, 6800, 8080, 9999, 65535})
    void shouldSetRpcPort(int port) {
        settings.setRpcPort(port);
        assertEquals(port, settings.getRpcPort());
    }

    @ParameterizedTest
    @DisplayName("Should validate retry wait values")
    @ValueSource(ints = {0, 1, 5, 10, 30})
    void shouldSetRetryWait(int wait) {
        settings.setRetryWait(wait);
        assertEquals(wait, settings.getRetryWait());
    }

    @ParameterizedTest
    @DisplayName("Should validate max tries values")
    @ValueSource(ints = {0, 1, 3, 5, 10})
    void shouldSetMaxTries(int tries) {
        settings.setMaxTries(tries);
        assertEquals(tries, settings.getMaxTries());
    }

    @ParameterizedTest
    @DisplayName("Should validate timeout values")
    @ValueSource(ints = {10, 30, 60, 300, 3600})
    void shouldSetTimeout(int timeout) {
        settings.setTimeout(timeout);
        assertEquals(timeout, settings.getTimeout());
    }

    @ParameterizedTest
    @DisplayName("Should validate BitTorrent max peers values")
    @ValueSource(ints = {10, 25, 55, 100, 200})
    void shouldSetBtMaxPeers(int peers) {
        settings.setBtMaxPeers(peers);
        assertEquals(peers, settings.getBtMaxPeers());
    }

    @ParameterizedTest
    @DisplayName("Should validate BitTorrent request peer speed limit values")
    @ValueSource(ints = {10, 25, 50, 100, 1000})
    void shouldSetBtRequestPeerSpeedLimit(int speedLimit) {
        settings.setBtRequestPeerSpeedLimit(speedLimit);
        assertEquals(speedLimit, settings.getBtRequestPeerSpeedLimit());
    }

    @ParameterizedTest
    @DisplayName("Should validate seed time values")
    @CsvSource({
        "0.0, 0.0",
        "1.5, 1.5",
        "60.0, 60.0",
        "120.5, 120.5"
    })
    void shouldSetSeedTime(double seedTime, double expected) {
        settings.setSeedTime(seedTime);
        assertEquals(expected, settings.getSeedTime());
    }

    @Test
    @DisplayName("Should convert to map with all basic settings")
    void shouldConvertToMapWithBasicSettings() {
        settings.setMaxConnectionPerServer(10)
                .setContinueDownload(false)
                .setMinSplitSize(30)
                .setFileAllocation("falloc")
                .setCheckIntegrity(true)
                .setRetryWait(10)
                .setMaxTries(3)
                .setTimeout(120)
                .setAllowOverwrite(true)
                .setAutoFileRenaming(false);

        Map<String, String> map = settings.toMap();

        assertEquals("10", map.get("max-connection-per-server"));
        assertEquals("false", map.get("continue"));
        assertEquals("30M", map.get("min-split-size"));
        assertEquals("falloc", map.get("file-allocation"));
        assertEquals("true", map.get("check-integrity"));
        assertEquals("10", map.get("retry-wait"));
        assertEquals("3", map.get("max-tries"));
        assertEquals("120", map.get("timeout"));
        assertEquals("true", map.get("allow-overwrite"));
        assertEquals("false", map.get("auto-file-renaming"));
    }

    @Test
    @DisplayName("toMap never emits daemon-lifecycle options for a running download")
    void toMapNeverEmitsRpcOptions() {
        settings.setEnableRpc(true).setRpcPort(7800);

        Map<String, String> map = settings.toMap();

        // toMap feeds per-download changeOption calls on a RUNNING daemon:
        // listener-lifecycle options must never appear there, regardless of
        // the daemon configuration fields
        assertFalse(map.containsKey("enable-rpc"));
        assertFalse(map.containsKey("rpc-listen-port"));
    }

    @Test
    @DisplayName("Should convert to map with BitTorrent settings when enabled")
    void shouldConvertToMapWithBitTorrentSettings() {
        settings.setUseBt(true)
                .setBtMaxPeers(100)
                .setBtRequestPeerSpeedLimit(75);

        Map<String, String> map = settings.toMap();

        assertEquals("100", map.get("bt-max-peers"));
        assertEquals("75K", map.get("bt-request-peer-speed-limit"));
        assertFalse(map.containsKey("bt-enable-lpd"));
        assertFalse(map.containsKey("enable-dht"));
        assertFalse(map.containsKey("enable-peer-exchange"));
    }

    @Test
    @DisplayName("Should disable BitTorrent features when BT is disabled")
    void shouldDisableBitTorrentFeaturesWhenBtDisabled() {
        settings.setUseBt(false);

        Map<String, String> map = settings.toMap();

        assertEquals("false", map.get("bt-enable-lpd"));
        assertEquals("false", map.get("enable-dht"));
        assertEquals("false", map.get("enable-peer-exchange"));
        assertFalse(map.containsKey("bt-max-peers"));
        assertFalse(map.containsKey("bt-request-peer-speed-limit"));
    }

    @Test
    @DisplayName("Should include seed settings when configured")
    void shouldIncludeSeedSettingsWhenConfigured() {
        settings.setSeedRatio(true).setSeedTime(30.5);

        Map<String, String> map = settings.toMap();

        assertEquals("1.0", map.get("seed-ratio"));
        assertEquals("30.5", map.get("seed-time"));
    }

    @Test
    @DisplayName("Should not include seed time when zero")
    void shouldNotIncludeSeedTimeWhenZero() {
        settings.setSeedTime(0.0);

        Map<String, String> map = settings.toMap();

        assertFalse(map.containsKey("seed-time"));
    }

    @Test
    @DisplayName("Should convert to RPC options with proper types")
    void shouldConvertToRpcOptionsWithProperTypes() {
        settings.setMaxConnectionPerServer(8)
                .setMinSplitSize(25)
                .setCheckIntegrity(true)
                .setUseBt(true)
                .setBtMaxPeers(75)
                .setBtRequestPeerSpeedLimit(60);

        Map<String, Object> options = settings.toRpcOptions();

        assertEquals("8", options.get("max-connection-per-server"));
        assertEquals("25M", options.get("min-split-size"));
        assertEquals("true", options.get("check-integrity"));
        assertEquals("75", options.get("bt-max-peers"));
        assertEquals("60K", options.get("bt-request-peer-speed-limit"));
    }

    @Test
    @DisplayName("Should include proxy settings in RPC options when configured")
    void shouldIncludeProxySettingsInRpcOptions() {
        settings.setUseProxy(true).setProxyAddress("http://proxy:8080");

        Map<String, Object> options = settings.toRpcOptions();

        assertEquals("http://proxy:8080", options.get("all-proxy"));
    }

    @Test
    @DisplayName("Should include additional options in RPC options")
    void shouldIncludeAdditionalOptionsInRpcOptions() {
        settings.setOption("split", "8");
        settings.setOption("header", "X-Test: allowed");

        Map<String, Object> options = settings.toRpcOptions();

        assertEquals("8", options.get("split"));
        assertEquals("X-Test: allowed", options.get("header"));
    }

    @Test
    @DisplayName("Should create deep copy with all settings")
    void shouldCreateDeepCopyWithAllSettings() {
        // Configure original settings
        settings.setMaxConnectionPerServer(12)
                .setContinueDownload(false)
                .setMinSplitSize(40)
                .setFileAllocation("none")
                .setEnableRpc(false)
                .setRpcPort(9800)
                .setCheckIntegrity(true)
                .setRetryWait(15)
                .setMaxTries(8)
                .setTimeout(180)
                .setAllowOverwrite(true)
                .setAutoFileRenaming(false)
                .setFollowMetalink(false)
                .setFollowTorrent(false)
                .setUseBt(false)
                .setBtMaxPeers(80)
                .setBtRequestPeerSpeedLimit(90)
                .setSeedRatio(true)
                .setSeedTime(45.5);

        // Add some base settings
        settings.setConnections(8);
        settings.setOption("user-agent", "Test-Agent");
        settings.setOption("test-option", "test-value");

        DownloadSettings copy = settings.copy();

        // Verify it's a different instance
        assertNotSame(settings, copy);
        assertTrue(copy instanceof Aria2Settings);

        Aria2Settings aria2Copy = (Aria2Settings) copy;

        // Verify all Aria2-specific settings are copied
        assertEquals(settings.getMaxConnectionPerServer(), aria2Copy.getMaxConnectionPerServer());
        assertEquals(settings.isContinueDownload(), aria2Copy.isContinueDownload());
        assertEquals(settings.getMinSplitSize(), aria2Copy.getMinSplitSize());
        assertEquals(settings.getFileAllocation(), aria2Copy.getFileAllocation());
        assertEquals(settings.isEnableRpc(), aria2Copy.isEnableRpc());
        assertEquals(settings.getRpcPort(), aria2Copy.getRpcPort());
        assertEquals(settings.isCheckIntegrity(), aria2Copy.isCheckIntegrity());
        assertEquals(settings.getRetryWait(), aria2Copy.getRetryWait());
        assertEquals(settings.getMaxTries(), aria2Copy.getMaxTries());
        assertEquals(settings.getTimeout(), aria2Copy.getTimeout());
        assertEquals(settings.isAllowOverwrite(), aria2Copy.isAllowOverwrite());
        assertEquals(settings.isAutoFileRenaming(), aria2Copy.isAutoFileRenaming());
        assertEquals(settings.isFollowMetalink(), aria2Copy.isFollowMetalink());
        assertEquals(settings.isFollowTorrent(), aria2Copy.isFollowTorrent());
        assertEquals(settings.isUseBt(), aria2Copy.isUseBt());
        assertEquals(settings.getBtMaxPeers(), aria2Copy.getBtMaxPeers());
        assertEquals(settings.getBtRequestPeerSpeedLimit(), aria2Copy.getBtRequestPeerSpeedLimit());
        assertEquals(settings.isSeedRatio(), aria2Copy.isSeedRatio());
        assertEquals(settings.getSeedTime(), aria2Copy.getSeedTime());

        // Verify base settings are copied
        assertEquals(settings.getConnections(), aria2Copy.getConnections());
        assertEquals(settings.getOption("user-agent"), aria2Copy.getOption("user-agent"));
        assertEquals(settings.getAdditionalOptions(), aria2Copy.getAdditionalOptions());
    }

    @Test
    @DisplayName("Should maintain independence after copying")
    void shouldMaintainIndependenceAfterCopying() {
        Aria2Settings copy = (Aria2Settings) settings.copy();

        // Modify original
        settings.setMaxConnectionPerServer(20);
        settings.setMinSplitSize(100);
        settings.setOption("original-option", "original-value");

        // Modify copy
        copy.setMaxConnectionPerServer(15);
        copy.setMinSplitSize(80);
        copy.setOption("copy-option", "copy-value");

        // Verify independence
        assertEquals(20, settings.getMaxConnectionPerServer());
        assertEquals(15, copy.getMaxConnectionPerServer());
        assertEquals(100, settings.getMinSplitSize());
        assertEquals(80, copy.getMinSplitSize());

        assertTrue(settings.getAdditionalOptions().containsKey("original-option"));
        assertFalse(settings.getAdditionalOptions().containsKey("copy-option"));
        assertTrue(copy.getAdditionalOptions().containsKey("copy-option"));
        assertFalse(copy.getAdditionalOptions().containsKey("original-option"));
    }

    @Test
    @DisplayName("Should handle edge cases in toMap conversion")
    void shouldHandleEdgeCasesInToMapConversion() {
        // Test with extreme values
        settings.setMaxConnectionPerServer(0)
                .setMinSplitSize(0)
                .setRetryWait(0)
                .setMaxTries(0)
                .setTimeout(0)
                .setSeedTime(0.0);

        Map<String, String> map = settings.toMap();

        assertEquals("0", map.get("max-connection-per-server"));
        assertEquals("0M", map.get("min-split-size"));
        assertEquals("0", map.get("retry-wait"));
        assertEquals("0", map.get("max-tries"));
        assertEquals("0", map.get("timeout"));
        assertFalse(map.containsKey("seed-time")); // Should not include zero seed time
    }

    @Test
    @DisplayName("Should handle edge cases in toRpcOptions conversion")
    void shouldHandleEdgeCasesInToRpcOptionsConversion() {
        // Test with null proxy
        settings.setUseProxy(false).setProxyAddress(null);

        Map<String, Object> options = settings.toRpcOptions();

        assertFalse(options.containsKey("all-proxy"));
    }

    @Test
    @DisplayName("Internal and untrusted option keys never reach aria2 RPC")
    void filtersAdditionalRpcOptions() {
        settings.setOption("header", "Cookie: session=1");
        settings.setOption("_current_proxy_host", "proxy.example.test");
        settings.setOption("on-download-complete", "/tmp/execute-me");

        Map<String, Object> options = settings.toRpcOptions();

        assertEquals("Cookie: session=1", options.get("header"));
        assertFalse(options.containsKey("_current_proxy_host"));
        assertFalse(options.containsKey("on-download-complete"));
    }

    @Test
    @DisplayName("Should inherit from DownloadSettings")
    void shouldInheritFromDownloadSettings() {
        assertTrue(settings instanceof DownloadSettings);

        // Test that inherited methods work
        settings.setConnections(10);
        settings.setOption("user-agent", "Aria2-Test");
        settings.setOption("inherited-option", "inherited-value");

        assertEquals(10, settings.getConnections());
        assertEquals("Aria2-Test", settings.getOption("user-agent"));
        assertEquals("inherited-value", settings.getAdditionalOptions().get("inherited-option"));
    }
}
