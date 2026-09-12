package org.manager.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class SizeUnitsTest {

    @Test
    void englishTableHasFiveUnitsEndingInTerabytes() {
        assertEquals(List.of("B", "KB", "MB", "GB", "TB"), SizeUnits.current());
    }

    @Test
    void frenchTableUsesOctetUnits() {
        assertEquals(List.of("o", "Ko", "Mo", "Go", "To"), SizeUnits.french());
    }

    @Test
    void localeHookReturnsEnglishForEveryLocaleUntilLocalisationLands() {
        assertEquals(SizeUnits.current(), SizeUnits.forLocale(Locale.US));
        assertEquals(SizeUnits.current(), SizeUnits.forLocale(Locale.FRANCE));
    }

    @Test
    void tebibyteIs1024ToTheFourth() {
        assertEquals(1024L * 1024 * 1024 * 1024, SizeUnits.TEBIBYTE);
    }
}
