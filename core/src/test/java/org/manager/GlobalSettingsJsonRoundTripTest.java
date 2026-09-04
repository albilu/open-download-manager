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
 * Pins the current settings.json flat key-value format and verifies that a
 * load/save cycle is stable at the JSON-map level.
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
        // The base profile follows the desktop's XDG Downloads directory, so
        // keep the static fixture focused on stable persisted keys.
        String savedDir = saved.remove("defaultDownloadDirectory");
        expected.remove("defaultDownloadDirectory");
        assertEquals(org.manager.util.OdmPaths.downloadDirectory(), Path.of(savedDir));

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
        assertFalse(settings.isRetainCompletedAndCanceledHistory());
        // cleanup policy
        assertEquals(5000, settings.getMaxDownloadsInMemory());
        assertEquals(250, settings.getMaxCompletedDownloadsToKeep());
        assertFalse(settings.isAutomaticCleanupEnabled());
        assertEquals(48, settings.getCleanupIntervalHours());
        assertEquals(120, settings.getCompletedDownloadRetentionDays());
        assertEquals(14, settings.getErrorDownloadRetentionDays());
        // custom bag keys survive verbatim
        assertEquals("true", settings.getProperty("ui.systemTray", null));
        assertEquals("7", settings.getProperty("network.maxConnections", null));
        assertEquals("6", settings.getProperty("network.maxRetries", null));
        assertEquals("512", settings.getProperty("network.downloadLimitKb", null));
        assertEquals("64", settings.getProperty("network.uploadLimitKb", null));
        assertEquals("4", settings.getProperty("network.retryDelaySeconds", null));
        assertEquals("https://referrer.test/", settings.getProperty("network.referer", null));
        assertEquals("ODM fixture", settings.getProperty("network.userAgent", null));
        assertEquals("session=fixture", settings.getProperty("network.cookie", null));
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

    @Test
    @DisplayName("legacy history names migrate without treating a hidden cleanup default as consent")
    void legacyHistoryKeysMigrateSafely() throws Exception {
        Path file = tempDir.resolve("legacy-settings.json");
        Files.writeString(file, """
                {"saveDownloadHistory":"false","automaticCleanupEnabled":"true"}
                """);

        GlobalSettings settings = new GlobalSettings();
        settings.load(file);

        assertFalse(settings.isRetainCompletedAndCanceledHistory(),
                "the explicit legacy retention choice must survive");
        assertFalse(settings.isAutomaticCleanupEnabled(),
                "the former hidden true default is not user cleanup consent");

        settings.save(file);
        Map<String, String> saved = readJson(file);
        assertEquals("false", saved.get("history.retainCompletedAndCanceled"));
        assertEquals("false", saved.get("historyCleanup.enabled"));
        assertFalse(saved.containsKey("saveDownloadHistory"));
        assertFalse(saved.containsKey("automaticCleanupEnabled"));
        assertFalse(saved.containsKey("paginationDefaultSize"));
        assertFalse(saved.containsKey("enableLazyLoading"));
    }

    @Test
    @DisplayName("record-specific yt-dlp choices are removed from global settings")
    void recordSpecificYtDlpChoicesAreNotPersistedGlobally() throws Exception {
        Path file = tempDir.resolve("yt-dlp-settings.json");
        GlobalSettings settings = new GlobalSettings();
        for (String key : java.util.List.of(
                "ytdlp.videoFormat", "ytdlp.containerProfile",
                "ytdlp.subtitleLanguages", "ytdlp.writeSubtitles",
                "ytdlp.extractAudio", "ytdlp.cookieBrowser",
                "ytdlp.cookieBrowserProfile")) {
            settings.setProperty(key, "former-global-value");
        }
        settings.setProperty("ytdlp.writeThumbnail", "true");

        settings.save(file);

        Map<String, String> saved = readJson(file);
        assertTrue(saved.containsKey("ytdlp.writeThumbnail"));
        assertFalse(saved.keySet().stream().anyMatch(key -> key.startsWith("ytdlp.")
                && !java.util.Set.of("ytdlp.writeThumbnail").contains(key)));
    }

    @Test
    @DisplayName("ODM auto save migrates the legacy aria2 preference lazily")
    void odmAutoSaveReadsLegacyThenUsesNewKey() {
        GlobalSettings settings = new GlobalSettings();
        settings.setProperty("aria2.autoSave", "false");
        assertFalse(settings.isOdmAutoSaveEnabled());

        settings.setOdmAutoSaveEnabled(true);
        assertTrue(settings.isOdmAutoSaveEnabled());
        assertEquals("true", settings.getProperty("odm.autoSave", null));
        assertEquals("false", settings.getProperty("aria2.autoSave", null),
                "migration must not destructively rewrite an existing settings file");
    }

    @Test
    @DisplayName("the obsolete Axel UI key is removed on the next save")
    void obsoleteAxelPathIsRemoved() throws Exception {
        Path file = tempDir.resolve("obsolete-tool-setting.json");
        GlobalSettings settings = new GlobalSettings();
        settings.setProperty("tools.axelPath", "/usr/bin/axel");

        settings.save(file);

        assertFalse(readJson(file).containsKey("tools.axelPath"));
    }

    @Test
    @DisplayName("obsolete development aliases are not retained as compatibility settings")
    void obsoleteEnginePreferenceAliasesAreRemoved() throws Exception {
        Path file = tempDir.resolve("obsolete-engine-settings.json");
        GlobalSettings settings = new GlobalSettings();
        settings.setProperty("aria2.maxConnectionsPerServer", "7");
        settings.setProperty("aria2.maxConnections", "8");
        settings.setProperty("aria2.maxTries", "5");
        settings.setProperty("ytdlp.format", "best");

        settings.save(file);

        Map<String, String> saved = readJson(file);
        assertFalse(saved.containsKey("aria2.maxConnectionsPerServer"));
        assertFalse(saved.containsKey("aria2.maxConnections"));
        assertFalse(saved.containsKey("aria2.maxTries"));
        assertFalse(saved.containsKey("ytdlp.format"));
    }

    @Test
    @DisplayName("hand-edited negative speed limits are clamped like API values")
    void negativeGlobalSpeedLimitIsClampedOnLoad() throws Exception {
        Path file = tempDir.resolve("negative-speed-setting.json");
        Files.writeString(file, "{\"globalSpeedLimit\":\"-25\"}");

        GlobalSettings settings = new GlobalSettings();
        settings.load(file);

        assertEquals(0, settings.getGlobalSpeedLimit());
    }

    private static Map<String, String> sorted(Map<String, String> map) {
        return new TreeMap<>(map);
    }
}
