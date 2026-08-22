package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Round-trip tests for the odm-state.json serialization. Before the mapper
 * was configured (Java time module, polymorphic DownloadSettings, Path
 * support), saveState() threw and no state file was ever written.
 */
class DownloadStateSerializationTest {

    private final ObjectMapper mapper = DownloadManagerImpl.createStateObjectMapper();

    @Test
    void aria2DownloadRoundTripPreservesIdentityAndFields() throws Exception {
        Download original = new Download(URI.create("https://example.com/file.iso"));
        original.setName("file.iso");
        original.setDestination(Path.of("/tmp", "odm-downloads"));
        original.setMirrors(List.of(URI.create("https://mirror.example.com/file.iso")));
        original.setStatus(Download.Status.PAUSED);
        original.setSize(123_456);
        original.setDownloaded(1_234);
        original.setErrorMessage(null);
        original.setQueuePosition(3);
        original.setGid("abcdef0123456789");

        org.aria2.Aria2Settings settings = (org.aria2.Aria2Settings) original.getSettings();
        settings.setOption("header", "Cookie: session=1");
        settings.setUseProxy(true);
        settings.setProxyAddress("socks5://127.0.0.1:9050");

        Download restored = roundTrip(original);

        assertEquals(original.getId(), restored.getId(), "download id must survive restart");
        assertEquals(original.getCreatedAt(), restored.getCreatedAt(), "createdAt must survive restart");
        assertEquals(original.getUri(), restored.getUri());
        assertEquals(original.getName(), restored.getName());
        assertEquals(original.getDestination(), restored.getDestination());
        assertEquals(original.getMirrors(), restored.getMirrors());
        assertEquals(original.getStatus(), restored.getStatus());
        assertEquals(original.getSize(), restored.getSize());
        assertEquals(original.getDownloaded(), restored.getDownloaded());
        assertEquals(original.getGid(), restored.getGid());

        var restoredSettings = assertInstanceOf(org.aria2.Aria2Settings.class, restored.getSettings());
        assertEquals("Cookie: session=1", restoredSettings.getOption("header"));
        assertEquals("socks5://127.0.0.1:9050", restoredSettings.getProxyAddress());
    }

    @Test
    void youtubeDownloadRoundTripRestoresYtDlpSettings() throws Exception {
        Download original = new Download(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertNotNull(original.getSettings());

        Download restored = roundTrip(original);
        assertInstanceOf(org.ytdlp.YtDlpSettings.class, restored.getSettings());
        assertEquals(original.getId(), restored.getId());
    }

    @Test
    void timestampsRoundTripAsIsoStrings() throws Exception {
        Download original = new Download(URI.create("https://example.com/timestamped"));
        Instant started = Instant.parse("2026-08-19T10:15:30Z");
        Instant completed = Instant.parse("2026-08-19T11:00:00Z");
        original.setStartedAt(started);
        original.setCompletedAt(completed);

        Download restored = roundTrip(original);
        assertEquals(started, restored.getStartedAt());
        assertEquals(completed, restored.getCompletedAt());
    }

    @Test
    void unknownPropertiesAreTolerated() throws Exception {
        Download original = new Download(URI.create("https://example.com/future"));
        String json = mapper.writeValueAsString(original);
        // Simulate a state file written by a newer ODM version
        String extended = json.replaceFirst("\\{", "{\"futureField\":42,");
        Download restored = mapper.readValue(extended, Download.class);
        assertEquals(original.getId(), restored.getId());
    }

    @Test
    void stateMapShapeRoundTrips() throws Exception {
        // saveState() persists a map of downloads + active ids
        Download a = new Download(URI.create("https://example.com/a"));
        Download b = new Download(URI.create("https://www.youtube.com/watch?v=abc123"));
        Map<String, Object> state = Map.of(
                "downloads", List.of(a, b),
                "activeDownloadsBeforeExit", java.util.Set.of(a.getId()));

        String json = mapper.writeValueAsString(state);
        Map<String, Object> raw = mapper.readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });

        List<Download> downloads = mapper.convertValue(raw.get("downloads"),
                new com.fasterxml.jackson.core.type.TypeReference<List<Download>>() {
                });
        assertEquals(2, downloads.size());
        assertEquals(a.getId(), downloads.get(0).getId());
        assertInstanceOf(org.ytdlp.YtDlpSettings.class, downloads.get(1).getSettings());
    }

    private Download roundTrip(Download original) throws Exception {
        String json = mapper.writeValueAsString(original);
        return mapper.readValue(json, Download.class);
    }
}
