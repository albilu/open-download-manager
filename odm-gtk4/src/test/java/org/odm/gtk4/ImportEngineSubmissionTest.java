package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadSettingsFactory;
import org.ytdlp.YtDlpSettings;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportEngineSubmissionTest {
    private static final String PAGE = "https://example.test/page";
    private static final String MEDIA = "https://www.youtube.com/watch?v=12345678901";
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(ImportEngine.class)
    void selectedEngineAndOptionsReachEveryQueuedRecord(ImportEngine engine) {
        var manager = mock(DownloadManager.class);
        var global = new GlobalSettings();
        when(manager.getGlobalSettings()).thenReturn(global);
        var factory = new DownloadSettingsFactory(global);
        var queued = new ArrayList<Download>();
        when(manager.createDownload(any(), any())).thenAnswer(call -> {
            var download = new Download(call.<URI>getArgument(0));
            download.initSettings(factory);
            download.setDestination(call.getArgument(1));
            return download;
        });
        when(manager.queueDownload(any())).thenAnswer(call -> {
            Download download = call.getArgument(0);
            download.validateSourcesForTransfer();
            queued.add(download);
            return CompletableFuture.completedFuture(null);
        });
        var options = new ImportListDialog.ImportOptions(false, 9050, 1, "proxy.test", 8080,
                "", "", 8, 256, 0, 7, 4, "https://referrer.test/", "Import UA", "session=test");

        assertEquals(2, DownloadSubmission.queueUrls(manager, List.of(PAGE, MEDIA, PAGE),
                directory, engine, options::apply, 10));
        assertEquals(2, queued.size());
        for (int i = 0; i < queued.size(); i++) {
            Download download = queued.get(i);
            Download.Type expectedType = engine == ImportEngine.AUTO
                    ? i == 0 ? Download.Type.ARIA2 : Download.Type.YOUTUBE : engine.type();
            assertEquals(expectedType, download.getType());
            Class<?> expectedSettings = switch (expectedType) {
                case ARIA2 -> org.aria2.Aria2Settings.class;
                case YOUTUBE -> YtDlpSettings.class;
                case WEBSITE_SCRAPING -> org.httrack.HttrackSettings.class;
                default -> throw new AssertionError(expectedType);
            };
            assertInstanceOf(expectedSettings, download.getSettings());
            assertEquals(directory, download.getDestination());
            assertEquals(256, download.getSettings().getDownloadLimitKB());
            assertEquals(7, download.getSettings().getMaxRetries());
            assertEquals("https://referrer.test/", download.getSettings().getReferer());
            assertEquals("Import UA", download.getSettings().getUserAgent());
            assertEquals("Cookie: session=test", download.getSettings().getCookieHeader());
            assertEquals("http://proxy.test:8080", download.getProxyAddress());
            if (download.getSettings() instanceof YtDlpSettings media) {
                assertTrue(media.isMediaProbeOnFailure(), "explicit media imports retain startup recovery");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "YT_DLP, magnet", "HTTRACK, magnet",
        "YT_DLP, ftp://files.test/file.zip", "HTTRACK, ftp://files.test/file.zip"
    })
    void incompatibleSelectionDoesNotCreateAnyRecords(ImportEngine engine, String incompatible) {
        var manager = mock(DownloadManager.class);
        String source = incompatible.equals("magnet") ? MAGNET : incompatible;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DownloadSubmission.queueUrls(manager, List.of(PAGE, source), directory,
                        engine, options()::apply, 10));
        assertTrue(failure.getMessage().startsWith(engine.label() + ":"), failure.getMessage());
        verify(manager, never()).createDownload(any(), any());
        verify(manager, never()).queueDownload(any());
    }

    @ParameterizedTest
    @EnumSource(value = ImportEngine.class, names = {"AUTO", "ARIA2"})
    void nativeTransferEnginesAcceptTheExistingProtocols(ImportEngine engine) {
        for (String url : List.of(PAGE, MAGNET, "sftp://files.test/file.zip", "file:///tmp/file.torrent")) {
            engine.validate(org.manager.url.DownloadUrlPolicy.require(url));
        }
    }

    @Test void autoKeepsDetectedSettings() {
        var download = new Download(URI.create(MEDIA));
        var settings = new YtDlpSettings();
        settings.setFormat("custom-format");
        download.setSettings(settings);
        ImportEngine.AUTO.apply(download, new DownloadSettingsFactory(new GlobalSettings()));
        assertSame(settings, download.getSettings());
        assertEquals("custom-format", settings.getFormat());
        assertEquals(Download.Type.YOUTUBE, download.getType());
    }

    private static ImportListDialog.ImportOptions options() {
        return new ImportListDialog.ImportOptions(false, 9050, 0, "", 0, "", "",
                4, 0, 0, 5, 0, "", "", "");
    }
}
