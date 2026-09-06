package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.url.DownloadUrlPolicy;

class ManagerSourceClassificationTest {
    @TempDir Path directory;

    @Test
    void everyCreationMethodHonorsItsProtocolAndEngineContractAcrossTheSourceMatrix() throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", directory.resolve("config").toString())
                .and("XDG_DATA_HOME", directory.resolve("data").toString())
                .and("XDG_STATE_HOME", directory.resolve("state").toString()).execute(() -> {
            DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
            manager.setDownloadGate(id -> false);
            try {
                for (var candidate : DownloadSourceClassificationTest.sourceCases().toList()) {
                    URI input = URI.create(candidate.input());
                    var source = DownloadUrlPolicy.require(input);
                    assertSource(manager.createDownload(input, directory), candidate,
                            candidate.protocol(), candidate.engine());

                    if (candidate.protocol() == Download.Protocol.MAGNET) {
                        assertSource(manager.createMagnetDownload(input, directory), candidate,
                                Download.Protocol.MAGNET, Download.Type.ARIA2);
                        assertThrows(IllegalArgumentException.class,
                                () -> manager.createMetaLinkDownload(input, directory), candidate.input());
                    } else {
                        assertThrows(IllegalArgumentException.class,
                                () -> manager.createMagnetDownload(input, directory), candidate.input());
                        // Explicit descriptor imports can identify extensionless endpoints.
                        assertSource(manager.createMetaLinkDownload(input, directory), candidate,
                                Download.Protocol.METALINK, Download.Type.ARIA2);
                    }

                    if (source.isWeb()) {
                        assertSource(manager.createYoutubeDownload(input, directory, null), candidate,
                                candidate.protocol(), Download.Type.YOUTUBE);
                        assertSource(manager.createWebsiteDownload(input, directory, null), candidate,
                                candidate.protocol(), Download.Type.WEBSITE_SCRAPING);
                    } else {
                        int before = manager.getDownloadCount();
                        assertThrows(IllegalArgumentException.class,
                                () -> manager.createYoutubeDownload(input, directory, null), candidate.input());
                        assertThrows(IllegalArgumentException.class,
                                () -> manager.createWebsiteDownload(input, directory, null), candidate.input());
                        assertEquals(before, manager.getDownloadCount(), "Rejected sources must not enter history");
                    }
                }

                for (String name : List.of("archive.torrent", "archive.TORRENT", "archive with spaces.torrent")) {
                    Path descriptor = Files.writeString(directory.resolve(name),
                            "d4:infod6:lengthi1e4:name1:x12:piece lengthi1e6:pieces20:aaaaaaaaaaaaaaaaaaaaee");
                    Download torrent = manager.createTorrentDownload(descriptor, directory);
                    assertEquals(descriptor.toUri(), torrent.getUri());
                    assertEquals(Download.Protocol.TORRENT, torrent.getProtocol());
                    assertEquals(Download.Type.ARIA2, torrent.getType());
                }
                for (String name : List.of("archive.zip", "archive.meta4", "archive.torrent.txt")) {
                    Path wrongType = Files.writeString(directory.resolve(name), "not a torrent");
                    assertThrows(IllegalArgumentException.class,
                            () -> manager.createTorrentDownload(wrongType, directory));
                }
                assertEquals(0, manager.getRunningDownloadCount());
                assertTrue(manager.getDownloadsByStatus(Download.Status.DOWNLOADING).isEmpty());
            } finally {
                manager.getAllDownloads().forEach(download -> manager.cancelDownload(download, false).join());
                DownloadManagerFactory.shutdown();
            }
        });
    }

    private static void assertSource(Download download, DownloadSourceClassificationTest.SourceCase candidate,
            Download.Protocol protocol, Download.Type engine) {
        assertEquals(candidate.normalized(), download.getUri().toString(), candidate.input());
        assertEquals(protocol, download.getProtocol(), candidate.input());
        assertEquals(engine, download.getType(), candidate.input());
    }
}
