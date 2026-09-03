package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

class ImportLimitsTest {

    @Test
    void defaultsAndConfiguredValuesComeFromGlobalSettings() {
        GlobalSettings settings = new GlobalSettings();

        assertEquals(ImportLimits.defaults(), ImportLimits.from(settings));

        new ImportLimits(7_500, 32).applyTo(settings);

        assertEquals(new ImportLimits(7_500, 32), ImportLimits.from(settings));
    }

    @Test
    void handEditedValuesRemainInsideDefensiveBounds() {
        GlobalSettings settings = new GlobalSettings();
        settings.setProperty(ImportLimits.MAX_URLS_KEY, "999999");
        settings.setProperty(ImportLimits.MAX_SOURCE_SIZE_MIB_KEY, "0");

        ImportLimits limits = ImportLimits.from(settings);

        assertEquals(ImportLimits.MAX_CONFIGURABLE_URLS, limits.maxUrls());
        assertEquals(ImportLimits.MIN_MAX_SOURCE_SIZE_MIB,
                limits.maxSourceSizeMiB());
    }
}
