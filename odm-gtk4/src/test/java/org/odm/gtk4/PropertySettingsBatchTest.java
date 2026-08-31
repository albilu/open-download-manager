package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.aria2.Aria2Settings;
import org.junit.jupiter.api.Test;
import org.manager.download.Download;
import org.manager.download.DownloadOperations;
import org.manager.download.ExternalToolSettings;
import org.ytdlp.YtDlpSettings;

class PropertySettingsBatchTest {

    @Test
    void appliesOneSnapshotToEverySelectedDownloadAndWaitsForHandlers() {
        Download first = download("first.mp4", new Aria2Settings());
        Download second = download("second.mp4", new Aria2Settings());
        DownloadOperations operations = mock(DownloadOperations.class);
        CompletableFuture<Void> firstApplied = new CompletableFuture<>();
        CompletableFuture<Void> secondApplied = new CompletableFuture<>();
        when(operations.changeSettings(first)).thenReturn(firstApplied);
        when(operations.changeSettings(second)).thenReturn(secondApplied);

        PropertySettingsBatch.Values values = new PropertySettingsBatch.Values(
                12, 512, 64, 7, 3,
                "https://referrer.example/", "ODM test", "session=abc",
                false, 4, "proxy.example", 1080, "alice", "secret");

        CompletableFuture<Void> applied = PropertySettingsBatch.apply(
                List.of(first, second), operations, values);

        assertFalse(applied.isDone(), "the batch must await every handler update");
        firstApplied.complete(null);
        assertFalse(applied.isDone(), "one unfinished handler keeps the batch pending");
        secondApplied.complete(null);
        applied.join();

        for (Download download : List.of(first, second)) {
            ExternalToolSettings settings = download.getSettings();
            assertEquals(12, settings.getMaxConnections());
            assertEquals(512, settings.getDownloadLimitKB());
            assertEquals(64, settings.getUploadLimitKB());
            assertEquals(7, settings.getMaxRetries());
            assertEquals(3, settings.getRetryDelaySeconds());
            assertEquals("https://referrer.example/", settings.getReferer());
            assertEquals("ODM test", settings.getUserAgent());
            assertEquals("Cookie: session=abc", settings.getCookieHeader());
            assertTrue(download.isUseProxy());
            assertEquals("socks5h://alice:secret@proxy.example:1080",
                    download.getProxyAddress());
        }
        verify(operations).changeSettings(first);
        verify(operations).changeSettings(second);
    }

    @Test
    void exposesOnlyCapabilitiesSharedByEverySelectedEngine() {
        Download aria2 = download("movie.mkv", new Aria2Settings());
        Download ytDlp = download("media", new YtDlpSettings());

        var capabilities = PropertySettingsBatch.commonCapabilities(List.of(aria2, ytDlp));

        assertTrue(capabilities.contains(ExternalToolSettings.Capability.CONNECTIONS));
        assertTrue(capabilities.contains(ExternalToolSettings.Capability.DOWNLOAD_LIMIT));
        assertFalse(capabilities.contains(ExternalToolSettings.Capability.UPLOAD_LIMIT));
    }

    @Test
    void doesNotApplyAFieldThatIsUnavailableForPartOfAMixedSelection() {
        Aria2Settings aria2Settings = new Aria2Settings();
        aria2Settings.setUploadLimitKB(77);
        Download aria2 = download("movie.mkv", aria2Settings);
        Download ytDlp = download("media", new YtDlpSettings());
        DownloadOperations operations = mock(DownloadOperations.class);
        when(operations.changeSettings(any(Download.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        PropertySettingsBatch.Values values = new PropertySettingsBatch.Values(
                4, 100, 12, 2, 1, "", "", "",
                false, 0, "", 0, "", "");

        PropertySettingsBatch.apply(List.of(aria2, ytDlp), operations, values).join();

        assertEquals(77, aria2Settings.getUploadLimitKB(),
                "a disabled mixed-engine field must not be silently reset");
    }

    @Test
    void emptySelectionIsANoOp() {
        DownloadOperations operations = mock(DownloadOperations.class);

        CompletableFuture<Void> result = PropertySettingsBatch.apply(
                List.of(), operations, PropertySettingsBatch.Values.defaults());

        assertTrue(result.isDone());
        result.join();
    }

    private static Download download(String name,
            org.manager.download.DownloadSettings settings) {
        Download download = new Download(URI.create("https://example.com/" + name));
        download.setName(name);
        download.setSettings(settings);
        return download;
    }
}
