package org.subliminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubliminalSettingsTest {

    @Test
    void languagesAreValidatedNormalizedAndDeduplicated() {
        SubliminalSettings settings = new SubliminalSettings()
                .setLanguages(List.of(" fr ", "it", "FR", "pt-br"));

        assertEquals(List.of("fr", "it", "pt-BR"), settings.getLanguages());
        assertEquals(List.of("en"), SubliminalSettings.parseLanguages("  "));
        assertThrows(IllegalArgumentException.class,
                () -> SubliminalSettings.parseLanguages("fr;rm -rf"));
    }

    @Test
    void invalidTimeoutFallsBackToTheBoundedDefault() {
        SubliminalSettings settings = new SubliminalSettings().setTimeout(Duration.ZERO);

        assertEquals(Duration.ofMinutes(5), settings.getTimeout());
    }
}
