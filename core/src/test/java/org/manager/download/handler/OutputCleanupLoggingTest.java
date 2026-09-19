package org.manager.download.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class OutputCleanupLoggingTest {
    @TempDir Path directory;

    @Test void archiveOnlyCompletionHasNoOutputToClean() {
        Download download = new Download(URI.create("https://fixture.invalid/archived"));
        download.setDestination(directory);
        download.setArchiveOnlyCompletion(true);
        download.setCompletedAt(java.time.Instant.now());
        Logger logger = (Logger) LoggerFactory.getLogger(YtDlpDownloadHandler.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            YtDlpDownloadHandler.deleteYtDlpOutput(download, null);
            assertTrue(logs.list.stream().noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN)));
            download.setArchiveOnlyCompletion(false);
            YtDlpDownloadHandler.deleteYtDlpOutput(download, null);
            assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                    "missing output after a real completion must remain visible");
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test void missingOutputsBeforeTransferAreQuietButEscapesStillWarn() throws Exception {
        Download download = new Download(URI.create("https://fixture.invalid/item"));
        download.setDestination(directory);
        download.setName("unowned.bin");
        Path unrelated = Files.writeString(directory.resolve("unowned.bin"), "keep");
        for (Class<?> type : List.of(Aria2DownloadHandler.class, YtDlpDownloadHandler.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            var logs = new ListAppender<ILoggingEvent>();
            logs.start();
            logger.addAppender(logs);
            try {
                download.setDownloaded(0);
                if (type == Aria2DownloadHandler.class) {
                    Aria2DownloadHandler.deleteAria2Payloads(download, Arrays.asList(null, "", " "));
                    Aria2DownloadHandler.deleteAria2Payloads(download, List.of());
                } else {
                    YtDlpDownloadHandler.deleteYtDlpOutput(download, null);
                }
                assertTrue(logs.list.stream().noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN)));
                assertEquals("keep", Files.readString(unrelated));

                download.setDownloaded(1);
                if (type == Aria2DownloadHandler.class) {
                    Aria2DownloadHandler.deleteAria2Payloads(download, List.of());
                } else {
                    YtDlpDownloadHandler.deleteYtDlpOutput(download, null);
                }
                assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                        "lost ownership information after transfer must remain visible");
                logs.list.clear();
                if (type == Aria2DownloadHandler.class) {
                    Aria2DownloadHandler.deleteAria2Payloads(download, List.of("../outside.bin"));
                    assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("outside the destination")));
                }
            } finally {
                logger.detachAppender(logs);
                logs.stop();
            }
        }
    }
}
