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
    void numericRangesStopAtTheirEndWithoutIntegerWraparound() {
        assertEquals(List.of("2147483646", "2147483647"),
                ImportSequenceDialog.generateSequence("{}", false,
                        Integer.MAX_VALUE - 1, Integer.MAX_VALUE, "", "", 10));
        assertEquals(List.of("-2147483648", "-2147483647"),
                ImportSequenceDialog.generateSequence("{}", false,
                        Integer.MIN_VALUE, Integer.MIN_VALUE + 1, "", "", 10));
        assertEquals(List.of("1", "2", "3"),
                ImportSequenceDialog.generateSequence("{}", false, 3, 1, "", "", 10));
    }

    @Test
    void everyPlaceholderIsReplacedAndBothCountLimitsApply() {
        assertEquals(List.of("https://example.com/1/file-1.zip", "https://example.com/2/file-2.zip"),
                ImportSequenceDialog.generateSequence("https://example.com/{}/file-{}.zip", false,
                        1, 10, "", "", 2, 3));
        assertEquals(List.of("c", "b"), ImportSequenceDialog.generateSequence("{}", true,
                1, 10, "c", "a", 10, 2));
        for (String invalid : List.of("", "ab")) {
            assertEquals(List.of(), ImportSequenceDialog.generateSequence("{}", true,
                    1, 10, invalid, "c", 10));
            assertEquals(List.of(), ImportSequenceDialog.generateSequence("{}", true,
                    1, 10, "a", invalid, 10));
        }
    }

    @Test
    void httpFieldsAreAppliedAlongsideSequenceTransferOptions() throws Exception {
        Download download = new Download(new java.net.URI("https://example.test/file-1.zip"));
        download.setSettings(new Aria2Settings());

        new ImportSequenceDialog.ImportOptions(false, 9050, 0, "", 0, "", "",
                7, 300, 40, 6, 2, "https://referrer.test/",
                "ODM sequence", "token=xyz").apply(download);

        Aria2Settings settings = (Aria2Settings) download.getSettings();
        assertEquals(7, settings.getMaxConnections());
        assertEquals(300, settings.getDownloadLimitKB());
        assertEquals(0, settings.getUploadLimitKB(),
                "upload limits are irrelevant for an HTTP record");
        assertEquals(6, settings.getMaxRetries());
        assertEquals(2, settings.getRetryDelaySeconds());
        assertEquals("https://referrer.test/", settings.getReferer());
        assertEquals("ODM sequence", settings.getUserAgent());
        assertEquals("Cookie: token=xyz", settings.getCookieHeader());
    }
}
