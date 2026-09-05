package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Set;
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
            assertEquals(0, settings.getUploadLimitKB(),
                    "upload limits are irrelevant for HTTP records");
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

    @Test
    void unchangedMixedFieldsArePreserved() {
        Aria2Settings firstSettings = new Aria2Settings();
        firstSettings.setReferer("https://one.test/");
        Aria2Settings secondSettings = new Aria2Settings();
        secondSettings.setReferer("https://two.test/");
        Download first = download("first.bin", firstSettings);
        Download second = download("second.bin", secondSettings);
        DownloadOperations operations = mock(DownloadOperations.class);
        when(operations.changeSettings(any(Download.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        PropertySettingsBatch.Values values = new PropertySettingsBatch.Values(
                4, 250, 0, 5, 0, "overwritten", "", "",
                false, 0, "", 0, "", "",
                Set.of(ExternalToolSettings.Capability.DOWNLOAD_LIMIT), false);
        PropertySettingsBatch.apply(List.of(first, second), operations, values).join();

        assertEquals(250, firstSettings.getDownloadLimitKB());
        assertEquals(250, secondSettings.getDownloadLimitKB());
        assertEquals("https://one.test/", firstSettings.getReferer());
        assertEquals("https://two.test/", secondSettings.getReferer());
    }

    @Test
    void failedEngineApplicationRestoresThePreviousRecordSettings() {
        Aria2Settings original = new Aria2Settings();
        original.setDownloadLimitKB(100);
        Download download = download("failure.bin", original);
        DownloadOperations operations = mock(DownloadOperations.class);
        when(operations.changeSettings(download))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("engine rejected update")))
                .thenReturn(CompletableFuture.completedFuture(null));

        PropertySettingsBatch.Values values = new PropertySettingsBatch.Values(
                4, 999, 0, 5, 0, "", "", "",
                false, 0, "", 0, "", "",
                Set.of(ExternalToolSettings.Capability.DOWNLOAD_LIMIT), false);

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> PropertySettingsBatch.apply(
                        List.of(download), operations, values).join());
        assertEquals(100, download.getSettings().getDownloadLimitKB());
    }

    @Test
    void synchronousFailureAfterAnEarlierSubmissionRollsBackEveryTarget() {
        Aria2Settings firstOriginal = new Aria2Settings();
        firstOriginal.setDownloadLimitKB(100);
        Aria2Settings secondOriginal = new Aria2Settings();
        secondOriginal.setDownloadLimitKB(200);
        Download first = download("first-failure.bin", firstOriginal);
        Download second = download("second-failure.bin", secondOriginal);
        DownloadOperations operations = mock(DownloadOperations.class);
        when(operations.changeSettings(first))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(operations.changeSettings(second))
                .thenThrow(new IllegalStateException("synchronous engine rejection"))
                .thenReturn(CompletableFuture.completedFuture(null));

        PropertySettingsBatch.Values values = new PropertySettingsBatch.Values(
                4, 999, 0, 5, 0, "", "", "",
                false, 0, "", 0, "", "",
                Set.of(ExternalToolSettings.Capability.DOWNLOAD_LIMIT), false);

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> PropertySettingsBatch.apply(
                        List.of(first, second), operations, values).join());
        assertEquals(100, first.getSettings().getDownloadLimitKB());
        assertEquals(200, second.getSettings().getDownloadLimitKB());
        verify(operations, times(2)).changeSettings(first);
        verify(operations, times(2)).changeSettings(second);
    }

    private static Download download(String name,
            org.manager.download.DownloadSettings settings) {
        Download download = new Download(URI.create("https://example.com/" + name));
        download.setName(name);
        download.setSettings(settings);
        return download;
    }
}
