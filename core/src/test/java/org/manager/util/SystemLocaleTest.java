package org.manager.util;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemLocaleTest {
    @Test
    void localeCategoriesAndLanguagePriorityFollowDesktopEnvironment() {
        assertEquals(Locale.CANADA_FRENCH, SystemLocale.display(Map.of("LANG", "en_US.UTF-8",
                "LC_MESSAGES", "fr_CA.UTF-8"), Locale.US));
        assertEquals(Locale.US, SystemLocale.display(Map.of("LANG", "fr_FR.UTF-8",
                "LC_ALL", "en_US.UTF-8"), Locale.FRANCE));
        assertEquals(Locale.FRANCE, SystemLocale.display(Map.of("LANG", "en_US.UTF-8",
                "LANGUAGE", "de:fr_FR:en"), Locale.US));
        assertEquals(Locale.ENGLISH, SystemLocale.display(Map.of("LC_ALL", "C",
                "LANGUAGE", "fr"), Locale.FRANCE));
        assertEquals(Locale.ENGLISH, SystemLocale.display(Map.of("LC_ALL", "C.UTF-8",
                "LANGUAGE", "fr"), Locale.FRANCE));
        assertEquals(Locale.ENGLISH, SystemLocale.display(Map.of("LANG", "de_DE.UTF-8"), Locale.GERMANY));
        assertEquals(Locale.CANADA_FRENCH, SystemLocale.display(Map.of(), Locale.CANADA_FRENCH));
    }
}
