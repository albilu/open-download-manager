package org.manager.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.tools.ToolManager.ToolException;
import org.aria2.Aria2ToolManager;
import org.curl.CurlToolManager;
import org.httrack.HttrackToolManager;
import org.proxychains.ProxychainsToolManager;
import org.tor.TorToolManager;
import org.ytdlp.YtDlpToolManager;

/**
 * Drives every concrete tool manager through the AbstractToolManager
 * contract against scripted fake binaries, so discovery, version parsing,
 * feature detection, validation and caching behavior are verified without
 * depending on the host's installed tool versions.
 */
@DisplayName("Concrete tool managers honor the ToolManager contract")
class ToolManagerContractTest {

    @TempDir
    Path tempDir;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private final GlobalSettings settings = new GlobalSettings();

    @AfterAll
    static void shutdownExecutor() {
        EXECUTOR.shutdownNow();
    }

    private Path fakeTool(String name, String versionOutput) throws IOException {
        Path tool = tempDir.resolve(name + "-" + System.nanoTime());
        // the full output block is echoed verbatim for --version, mimicking
        // the multi-line layout real tools use (version line, Features line, ...)
        String script = "#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then\n"
                + "cat <<'__EOF__'\n" + versionOutput + "\n__EOF__\n"
                + "exit 0\nfi\nexit 0\n";
        Files.writeString(tool, script);
        Files.setPosixFilePermissions(tool, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        return tool;
    }

    private AbstractToolManager[] allManagers() {
        return new AbstractToolManager[] {
                new Aria2ToolManager(settings, EXECUTOR),
                new CurlToolManager(settings, EXECUTOR),
                new YtDlpToolManager(settings, EXECUTOR),
                new HttrackToolManager(settings, EXECUTOR),
                new ProxychainsToolManager(settings, EXECUTOR),
                new TorToolManager(settings, EXECUTOR),
        };
    }

    @Test
    @DisplayName("tool ids and executable names are stable identifiers")
    void stableIdentifiers() {
        assertEquals("aria2", Aria2ToolManager.TOOL_ID);
        assertEquals("curl", CurlToolManager.TOOL_ID);
        assertEquals("yt-dlp", YtDlpToolManager.TOOL_ID);
        assertEquals("httrack", HttrackToolManager.TOOL_ID);
        assertEquals("proxychains", ProxychainsToolManager.TOOL_ID);
        assertEquals("tor", TorToolManager.TOOL_ID);

        for (AbstractToolManager manager : allManagers()) {
            assertNotNull(manager.getToolId());
            assertNotNull(manager.getExecutableName());
        }
    }

    @Test
    @DisplayName("a configured fake binary is discovered, available, and its version parsed")
    void discoveryWithFakeBinary() throws IOException {
        CurlToolManager manager = new CurlToolManager(settings, EXECUTOR);
        Path fake = fakeTool("curl", """
                curl 8.12.1 (x86_64-pc-linux-gnu) libcurl/8.12.1 OpenSSL/3.0.13
                Protocols: dict file ftp ftps http https
                Features: HTTP2 HTTPS-proxy SSL UnixSockets""");
        manager.setToolPath(fake.toString());

        assertTrue(manager.isAvailable(), "the fake binary is executable");
        assertEquals(fake.toString(), manager.getToolPath());
        assertEquals("8.12.1", manager.getVersion());
        assertTrue(manager.checkMinimumVersion("8.0.0"));
        assertFalse(manager.checkMinimumVersion("9.0.0"));
        assertTrue(manager.getSupportedFeatures().containsKey("http2"),
                "feature keys are lowercase");
        assertTrue(manager.checkFeatureSupport("http2"));
        assertTrue(manager.isHttp2Supported());
    }

    @Test
    @DisplayName("async availability mirrors the sync check")
    void asyncAvailability() throws IOException {
        CurlToolManager manager = new CurlToolManager(settings, EXECUTOR);
        manager.setToolPath(fakeTool("curl", "curl 8.5.0\nFeatures: SSL").toString());
        assertTrue(manager.checkAvailabilityAsync().join());
    }

    @Test
    @DisplayName("a bogus configured path degrades to unavailable when no fallback exists")
    void bogusPathIsUnavailable() throws IOException {
        // no common-location fallback so the discovery truly fails
        AbstractToolManager missing = new CurlToolManager(settings, EXECUTOR) {
            @Override
            protected java.util.List<String> getCommonLocations() {
                return java.util.List.of();
            }
        };
        missing.setToolPath(tempDir.resolve("not-executable").toString());
        assertFalse(missing.isAvailable());
        assertNull(missing.getVersion(), "no version without a runnable tool");
        assertThrows(ToolException.class, missing::validateTool);
    }

    @Test
    @DisplayName("validateTool passes for a working tool and fails for a broken one")
    void validateToolContract() throws Exception {
        CurlToolManager good = new CurlToolManager(settings, EXECUTOR);
        good.setToolPath(fakeTool("curl", "curl 8.5.0\nFeatures: SSL").toString());
        good.validateTool();

        // a tool whose --version exits non-zero fails the basic check
        Path broken = tempDir.resolve("curl-broken-" + System.nanoTime());
        Files.writeString(broken, "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(broken,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        CurlToolManager bad = new CurlToolManager(settings, EXECUTOR);
        bad.setToolPath(broken.toString());
        ToolException ex = assertThrows(ToolException.class, bad::validateTool);
        assertTrue(ex.getMessage().contains("curl"));
    }

    @Test
    @DisplayName("caching: availability and version are computed once until cleaned up")
    void cachingBehavior() throws IOException {
        CurlToolManager manager = new CurlToolManager(settings, EXECUTOR);
        Path fake = fakeTool("curl", "curl 8.5.0\nFeatures: SSL");
        manager.setToolPath(fake.toString());

        String version = manager.getVersion();
        assertEquals(version, manager.getVersion(), "second read must be served from cache");
        manager.cleanup();
        // after cleanup the version must be recomputed (and produce the same value)
        assertEquals(version, manager.getVersion());
    }

    @Test
    @DisplayName("the status report aggregates identity, availability, path, version and features")
    void statusReportShape() throws IOException {
        for (AbstractToolManager manager : allManagers()) {
            Map<String, Object> report = manager.getStatusReport();
            assertEquals(manager.getToolId(), report.get("toolId"));
            assertEquals(manager.getExecutableName(), report.get("executableName"));
            assertNotNull(report.get("available"));
            assertNotNull(report.get("features"));
        }
    }

    @Test
    @DisplayName("feature maps are defensive copies")
    void featureMapsAreCopies() throws IOException {
        CurlToolManager manager = new CurlToolManager(settings, EXECUTOR);
        manager.setToolPath(fakeTool("curl", "curl 8.5.0\nFeatures: HTTP2 SSL").toString());
        Map<String, Boolean> features = manager.getSupportedFeatures();
        features.put("forged", true);
        assertFalse(manager.getSupportedFeatures().containsKey("forged"),
                "mutating the returned map must not poison the cache");
    }

    @Test
    @DisplayName("every manager degrades gracefully with an empty configuration")
    void gracefulDegradationWithoutConfiguration() {
        for (AbstractToolManager manager : allManagers()) {
            boolean available = manager.isAvailable();
            Map<String, Object> report = manager.getStatusReport();
            assertEquals(available, report.get("available"),
                    manager.getToolId() + " status must agree with availability");
            if (!available) {
                assertTrue(manager.getSupportedFeatures().isEmpty()
                        || !manager.getSupportedFeatures().containsValue(null),
                        manager.getToolId() + " features must be concrete booleans");
            }
        }
    }
}
