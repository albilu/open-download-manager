package org.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Custom tool paths persist in settings.json like other fields; legacy
 * files without tool-path keys keep loading with the default paths.
 */
@DisplayName("GlobalSettings tool path persistence")
class GlobalSettingsToolPathPersistenceTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, String> readJson(Path file) throws Exception {
        try (var in = Files.newInputStream(file)) {
            return MAPPER.readValue(in, new TypeReference<Map<String, String>>() {
            });
        }
    }

    @Test
    @DisplayName("custom tool paths survive a save/load round trip")
    void customPathsRoundTrip() throws Exception {
        Path file = tempDir.resolve("settings.json");

        GlobalSettings settings = new GlobalSettings();
        settings.setAria2Path("/opt/custom/aria2c");
        settings.setCurlPath("/opt/custom/curl");
        settings.setTorPath("/opt/custom/tor");
        settings.setSubliminalPath("/opt/custom/subliminal");
        settings.save(file);

        Map<String, String> saved = readJson(file);
        assertEquals("/opt/custom/aria2c", saved.get("aria2Path"));
        assertEquals("/opt/custom/curl", saved.get("curlPath"));
        assertEquals("/opt/custom/tor", saved.get("torPath"));
        assertEquals("/opt/custom/subliminal", saved.get("subliminalPath"));
        assertFalse(saved.containsKey("ytDlpPath"),
                "a default path must not be persisted (no config churn)");

        GlobalSettings reloaded = new GlobalSettings();
        reloaded.load(file);
        assertEquals("/opt/custom/aria2c", reloaded.getAria2Path());
        assertEquals("/opt/custom/curl", reloaded.getCurlPath());
        assertEquals("/opt/custom/tor", reloaded.getTorPath());
        assertEquals("/opt/custom/subliminal", reloaded.getSubliminalPath());
        assertEquals("yt-dlp", reloaded.getYtDlpPath(),
                "a key absent from the file must leave the default intact");
    }

    @Test
    @DisplayName("returning to the default path clears the stale persisted key")
    void returningToDefaultClearsKey() throws Exception {
        Path file = tempDir.resolve("settings.json");
        GlobalSettings settings = new GlobalSettings();
        settings.setAria2Path("/opt/custom/aria2c");
        settings.save(file);
        assertTrue(readJson(file).containsKey("aria2Path"));

        settings.setAria2Path("aria2c");
        settings.save(file);

        assertFalse(readJson(file).containsKey("aria2Path"),
                "reverting to the default must not resurrect the stale key");
    }

    @Test
    @DisplayName("legacy files without tool-path keys load with defaults")
    void legacyFileKeepsDefaults() throws Exception {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, "{\"maxConcurrentDownloads\":\"4\"}");

        GlobalSettings settings = new GlobalSettings();
        settings.load(file);

        assertEquals("aria2c", settings.getAria2Path());
        assertEquals("yt-dlp", settings.getYtDlpPath());
        assertEquals("httrack", settings.getHttrackPath());
        assertEquals("curl", settings.getCurlPath());
        assertEquals("proxychains", settings.getProxychainsPath());
        assertEquals("tor", settings.getTorPath());
        assertEquals("subliminal", settings.getSubliminalPath());
        assertEquals(4, settings.getMaxConcurrentDownloads());
    }
}
