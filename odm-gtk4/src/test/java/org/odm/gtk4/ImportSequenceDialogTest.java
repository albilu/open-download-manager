package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

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
    void generationRequiresAPlaceholderAndHonorsTheGlobalCap() {
        assertTrue(ImportSequenceDialog.generateSequence(
                "https://example.test/static", false, 1, 10, "", "", 10).isEmpty());
        assertEquals(ImportSequenceDialog.MAX_IMPORT_URLS,
                ImportSequenceDialog.generateSequence("item-{}", false,
                        1, 10_000, "", "", 10_000).size());
    }
}
