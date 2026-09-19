package org.manager.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class SizeUnitsTest {

    @Test
    void englishTableHasFiveUnitsEndingInTerabytes() {
        assertEquals(List.of("B", "KB", "MB", "GB", "TB"), SizeUnits.forLocale(Locale.US));
    }

    @Test
    void frenchTableUsesOctetUnits() {
        assertEquals(List.of("o", "Ko", "Mo", "Go", "To"), SizeUnits.french());
    }

    @Test
    void frenchRegionalLocalesUseOctetsAndOtherLanguagesFallBackToEnglish() {
        for (String tag : List.of("fr", "fr-FR", "fr-BE", "fr-CA")) {
            assertEquals(SizeUnits.french(), SizeUnits.forLocale(Locale.forLanguageTag(tag)));
        }
        assertEquals(SizeUnits.forLocale(Locale.US), SizeUnits.forLocale(Locale.GERMANY));
        assertEquals(SizeUnits.forLocale(Locale.US), SizeUnits.forLocale(null));
    }

    @Test
    void tebibyteIs1024ToTheFourth() {
        assertEquals(1024L * 1024 * 1024 * 1024, SizeUnits.TEBIBYTE);
    }
}
