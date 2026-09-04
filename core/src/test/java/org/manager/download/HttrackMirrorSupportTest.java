package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.httrack.HttrackSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttrackMirrorSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void completedCachedMirrorCanBeUpdatedAndExposesNativeLogs() throws Exception {
        Path mirror = tempDir.resolve("site");
        Files.createDirectories(mirror.resolve("hts-cache"));
        Files.writeString(mirror.resolve("hts-log.txt"), "crawl log");

        Download download = websiteRecord(mirror, Download.Status.COMPLETED);

        assertEquals(mirror, HttrackMirrorSupport.mirrorDirectory(download));
        assertTrue(HttrackMirrorSupport.canUpdate(download));
        assertTrue(HttrackMirrorSupport.hasDiagnostic(download,
                HttrackMirrorSupport.DiagnosticLog.ACTIVITY));
        assertFalse(HttrackMirrorSupport.hasDiagnostic(download,
                HttrackMirrorSupport.DiagnosticLog.ERRORS));
        assertEquals(mirror.resolve("hts-err.txt"),
                HttrackMirrorSupport.diagnosticPath(download,
                        HttrackMirrorSupport.DiagnosticLog.ERRORS));

        HttrackSettings update = HttrackMirrorSupport.prepareUpdate(download, false);
        assertEquals(HttrackSettings.RunMode.UPDATE, update.getRunMode());
        assertFalse(update.isPurgeOldFiles());
        assertTrue(update.buildCommandLine().contains("-X0"));

        HttrackMirrorSupport.prepareUpdate(download, true);
        assertTrue(update.isPurgeOldFiles());
        assertTrue(update.buildCommandLine().contains("-X1"));
    }

    @Test
    void updateRequiresCompletedHttrackRecordAndExistingCache() throws Exception {
        Path mirror = tempDir.resolve("uncached");
        Files.createDirectories(mirror);
        Download download = websiteRecord(mirror, Download.Status.COMPLETED);

        assertFalse(HttrackMirrorSupport.canUpdate(download));
        Files.createDirectories(mirror.resolve("hts-cache"));
        download.setStatus(Download.Status.DOWNLOADING);
        assertFalse(HttrackMirrorSupport.canUpdate(download));
        download.setStatus(Download.Status.COMPLETED);
        download.setSettings(new org.aria2.Aria2Settings());
        assertFalse(HttrackMirrorSupport.canUpdate(download));
    }

    private Download websiteRecord(Path mirror, Download.Status status) {
        Download download = new Download(URI.create("https://example.test/"));
        download.setType(Download.Type.WEBSITE_SCRAPING);
        download.setName("site");
        download.setDestination(tempDir);
        download.setSettings(new HttrackSettings());
        download.setStatus(status);
        download.recordOutputPath(mirror);
        return download;
    }
}
