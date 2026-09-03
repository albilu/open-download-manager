package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.aria2.Aria2Settings;
import org.junit.jupiter.api.Test;
import org.manager.download.Download;

class ImportSequenceDialogTest {

    @Test
    void numericModeUsesTheNumericRangeEvenWhenCharacterValuesExist() {
        assertEquals(List.of(
                "https://example.test/file-2.zip",
                "https://example.test/file-3.zip",
                "https://example.test/file-4.zip"),
                ImportSequenceDialog.generateSequence(
                        "https://example.test/file-{}.zip", false,
                        2, 4, "a", "c", 10));
    }

    @Test
    void characterModeSupportsAscendingAndDescendingRanges() {
        assertEquals(List.of("item-a", "item-b", "item-c"),
                ImportSequenceDialog.generateSequence("item-{}", true,
                        1, 9, "a", "c", 10));
        assertEquals(List.of("item-c", "item-b", "item-a"),
                ImportSequenceDialog.generateSequence("item-{}", true,
                        1, 9, "c", "a", 10));
    }

    @Test
    void generationRequiresAPlaceholderAndHonorsTheConfiguredCap() {
        assertTrue(ImportSequenceDialog.generateSequence(
                "https://example.test/static", false, 1, 10, "", "", 10).isEmpty());
        assertEquals(37, ImportSequenceDialog.generateSequence("item-{}", false,
                1, 10_000, "", "", 10_000, 37).size());
        assertEquals(ImportLimits.DEFAULT_MAX_URLS,
                ImportSequenceDialog.generateSequence("item-{}", false,
                        1, 10_000, "", "", 10_000).size());
    }

    @Test
    void httpFieldsAreAppliedAlongsideSequenceTransferOptions() throws Exception {
        Download download = new Download(new java.net.URI("https://example.test/file-1.zip"));
        download.setSettings(new Aria2Settings());

        new ImportSequenceDialog.ImportOptions(false, 0, "", 0, "", "",
                7, 300, 40, 6, 2, "https://referrer.test/",
                "ODM sequence", "token=xyz").apply(download);

        Aria2Settings settings = (Aria2Settings) download.getSettings();
        assertEquals(7, settings.getMaxConnections());
        assertEquals(300, settings.getDownloadLimitKB());
        assertEquals(40, settings.getUploadLimitKB());
        assertEquals(6, settings.getMaxRetries());
        assertEquals(2, settings.getRetryDelaySeconds());
        assertEquals("https://referrer.test/", settings.getReferer());
        assertEquals("ODM sequence", settings.getUserAgent());
        assertEquals("Cookie: token=xyz", settings.getCookieHeader());
    }
}
