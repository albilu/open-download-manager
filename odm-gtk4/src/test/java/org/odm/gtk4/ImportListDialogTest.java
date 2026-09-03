package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.aria2.Aria2Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;

class ImportListDialogTest {

    @TempDir
    Path tempDir;

    @Test
    void importDialogIsNotPresentedUntilTheChosenFileHasLoaded() throws Exception {
        Path list = tempDir.resolve("downloads.txt");
        Files.writeString(list, "# comment\nhttps://example.test/a.zip\n\n"
                + "https://example.test/b.iso\n");
        AtomicReference<Runnable> pendingRead = new AtomicReference<>();
        AtomicReference<List<String>> presentedLines = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ImportListDialog.loadSelectionThenPresent(list, pendingRead::set,
                presentedLines::set, failure::set);

        assertNull(presentedLines.get(),
                "the import dialog must remain absent while the file is being read");
        assertNotNull(pendingRead.get(), "a selected file must schedule an asynchronous read");

        pendingRead.get().run();

        assertEquals(List.of("https://example.test/a.zip", "https://example.test/b.iso"),
                presentedLines.get());
        assertNull(failure.get());
    }

    @Test
    void cancellingTheFileChooserDoesNotCreateTheImportDialog() {
        AtomicReference<Runnable> pendingRead = new AtomicReference<>();
        AtomicReference<List<String>> presentedLines = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ImportListDialog.loadSelectionThenPresent(null, pendingRead::set,
                presentedLines::set, failure::set);

        assertNull(pendingRead.get());
        assertNull(presentedLines.get());
        assertNull(failure.get());
    }

    @Test
    void configuredUrlAndSourceSizeLimitsAreEnforced() throws Exception {
        Path tooMany = tempDir.resolve("too-many.txt");
        Files.writeString(tooMany, "https://example.test/1\n"
                + "https://example.test/2\nhttps://example.test/3\n");

        IllegalArgumentException urlFailure = assertThrows(IllegalArgumentException.class,
                () -> ImportListDialog.readImportLines(
                        tooMany, new ImportLimits(2, 1)));
        assertTrue(urlFailure.getMessage().contains("2 URL"));

        Path tooLarge = tempDir.resolve("too-large.txt");
        Files.writeString(tooLarge, "x".repeat(1024 * 1024 + 1));
        IllegalArgumentException sizeFailure = assertThrows(IllegalArgumentException.class,
                () -> ImportListDialog.readImportLines(
                        tooLarge, new ImportLimits(10, 1)));
        assertTrue(sizeFailure.getMessage().contains("1 MiB"));
    }

    @Test
    void everyVisibleTransferOptionIsAppliedToImportedDownloads() throws Exception {
        Download download = new Download(new java.net.URI("https://example.test/file.iso"));
        download.setSettings(new Aria2Settings());

        new ImportListDialog.ImportOptions(false, 0, "", 0, "", "",
                12, 256, 64, 9, 4, "https://referrer.test/",
                "ODM import", "session=abc").apply(download);

        Aria2Settings settings = (Aria2Settings) download.getSettings();
        assertEquals(12, settings.getMaxConnections());
        assertEquals(256, settings.getDownloadLimitKB());
        assertEquals(64, settings.getUploadLimitKB());
        assertEquals(9, settings.getMaxRetries());
        assertEquals(4, settings.getRetryDelaySeconds());
        assertEquals("https://referrer.test/", settings.getReferer());
        assertEquals("ODM import", settings.getUserAgent());
        assertEquals("Cookie: session=abc", settings.getCookieHeader());
    }
}
