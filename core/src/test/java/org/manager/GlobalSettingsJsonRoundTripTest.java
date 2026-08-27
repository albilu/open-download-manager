package org.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pins the settings.json format against fixtures captured from the
 * pre-refactoring implementation: the flat key-value layout must never drift
 * silently, existing installs must load unchanged, and a load/save cycle must
 * be stable at the JSON-map level.
 */
@DisplayName("GlobalSettings settings.json format round-trip")
class GlobalSettingsJsonRoundTripTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, String> readJson(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return MAPPER.readValue(in, new TypeReference<Map<String, String>>() {
            });
        }
    }

    private static Map<String, String> fixture(String name) throws Exception {
        try (InputStream in = GlobalSettingsJsonRoundTripTest.class
                .getResourceAsStream("/settings/" + name)) {
            return MAPPER.readValue(in, new TypeReference<Map<String, String>>() {
            });
        }
    }

    /** Copies a fixture into the temp dir so load(Path) sees it. */
    private Path installFixture(String name) throws Exception {
        Path target = tempDir.resolve("settings.json");
        try (InputStream in = GlobalSettingsJsonRoundTripTest.class
                .getResourceAsStream("/settings/" + name)) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    @Test
    @DisplayName("a fresh save() emits exactly the captured default fixture (modulo the user's home)")
    void freshSaveMatchesDefaultFixture() throws Exception {
        Map<String, String> expected = fixture("settings-default.json");
        Path target = tempDir.resolve("settings.json");

        new GlobalSettings().save(target);

        Map<String, String> saved = readJson(target);
        // defaultDownloadDirectory is user.home-dependent: normalize both sides
        String savedDir = saved.remove("defaultDownloadDirectory");
        String expectedDir = expected.remove("defaultDownloadDirectory")
                .replace("/home/developer", System.getProperty("user.home"));
        assertEquals(Path.of(expectedDir).toString(), Path.of(savedDir).toString());

        assertEquals(sorted(expected), sorted(saved));
    }

    @Test
    @DisplayName("the full fixture loads every typed value and custom key unchanged")
    void fullFixtureLoadsAllValues() throws Exception {
        Path file = installFixture("settings-full.json");

        GlobalSettings settings = new GlobalSettings();
        settings.load(file);

        // concurrency
        assertEquals(9, settings.getMaxConcurrentDownloads());
        assertEquals(2048, settings.getGlobalSpeedLimit());
        // proxy
        assertTrue(settings.isGlobalProxyEnabled());
        assertEquals("socks5://127.0.0.1:9050", settings.getGlobalProxyAddress());
        assertTrue(settings.isProxyRotationEnabled());
        assertEquals(11, settings.getProxyRotationMaxRetries());
        assertEquals("/tmp/proxy-list.txt", settings.getProxyListFilePath());
        // downloads / history
        assertEquals(Path.of("/home/dev/Downloads"), settings.getDefaultDownloadDirectory());
        assertFalse(settings.isSaveDownloadHistory());
        // cleanup policy
        assertEquals(5000, settings.getMaxDownloadsInMemory());
        assertEquals(250, settings.getMaxCompletedDownloadsToKeep());
        assertFalse(settings.isAutomaticCleanupEnabled());
        assertFalse(settings.isEnableLazyLoading());
        assertEquals(100, settings.getPaginationDefaultSize());
        // custom bag keys survive verbatim
        assertEquals("true", settings.getProperty("ui.systemTray", null));
        assertEquals("7", settings.getProperty("aria2.maxConnectionsPerServer", null));
        assertEquals("bestvideo+bestaudio", settings.getProperty("ytdlp.format", null));
        assertEquals("night", settings.getProperty("scheduler.preset", null));
        assertEquals("15", settings.getProperty("tracker.refreshInterval", null));
        assertEquals(300, settings.getIntProperty("antivirus.timeout", 0));
        assertEquals("custom", settings.getProperty("ui.completionAction", null));
        // tool paths are runtime-only: the fixture does not carry them
        assertEquals("aria2c", settings.getAria2Path());
    }

    @Test
    @DisplayName("load(); save() re-emits the fixture exactly (no key or value drift)")
    void fullFixtureRoundTripsStably() throws Exception {
        Path file = installFixture("settings-full.json");

        GlobalSettings settings = new GlobalSettings();
        settings.load(file);
        settings.save(file);

        assertEquals(sorted(fixture("settings-full.json")), sorted(readJson(file)));
    }

    @Test
    @DisplayName("clearing nullable proxy values removes their stale keys")
    void nullProxyValuesClearStaleKeys() throws Exception {
        Path file = installFixture("settings-full.json");

        GlobalSettings settings = new GlobalSettings();
        settings.load(file);
        settings.setGlobalProxyAddress(null);
        settings.setProxyListFilePath(null);
        settings.save(file);

        Map<String, String> saved = readJson(file);
        assertFalse(saved.containsKey("globalProxyAddress"),
                "null proxy address must not resurrect the stale key");
        assertFalse(saved.containsKey("proxyListFilePath"),
                "null proxy list path must not resurrect the stale key");
    }

    private static Map<String, String> sorted(Map<String, String> map) {
        return new TreeMap<>(map);
    }
}
