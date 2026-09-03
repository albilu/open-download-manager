package org.odm.gtk4;

import org.manager.GlobalSettings;

/**
 * User-configurable admission limits shared by every bulk-import workflow.
 * The outer bounds remain defensive implementation limits because local and
 * remote sources are materialized in memory before parsing.
 */
record ImportLimits(int maxUrls, int maxSourceSizeMiB) {

    static final String MAX_URLS_KEY = "imports.maxUrls";
    static final String MAX_SOURCE_SIZE_MIB_KEY = "imports.maxSourceSizeMiB";

    static final int DEFAULT_MAX_URLS = 1_000;
    static final int MIN_MAX_URLS = 1;
    static final int MAX_CONFIGURABLE_URLS = 10_000;

    static final int DEFAULT_MAX_SOURCE_SIZE_MIB = 8;
    static final int MIN_MAX_SOURCE_SIZE_MIB = 1;
    static final int MAX_CONFIGURABLE_SOURCE_SIZE_MIB = 64;

    ImportLimits {
        maxUrls = Math.clamp(maxUrls, MIN_MAX_URLS, MAX_CONFIGURABLE_URLS);
        maxSourceSizeMiB = Math.clamp(maxSourceSizeMiB,
                MIN_MAX_SOURCE_SIZE_MIB, MAX_CONFIGURABLE_SOURCE_SIZE_MIB);
    }

    static ImportLimits defaults() {
        return new ImportLimits(DEFAULT_MAX_URLS, DEFAULT_MAX_SOURCE_SIZE_MIB);
    }

    static ImportLimits from(GlobalSettings settings) {
        if (settings == null) {
            return defaults();
        }
        return new ImportLimits(
                settings.getIntProperty(MAX_URLS_KEY, DEFAULT_MAX_URLS),
                settings.getIntProperty(MAX_SOURCE_SIZE_MIB_KEY,
                        DEFAULT_MAX_SOURCE_SIZE_MIB));
    }

    long maxSourceBytes() {
        return maxSourceSizeMiB * 1024L * 1024L;
    }

    void applyTo(GlobalSettings settings) {
        settings.setProperty(MAX_URLS_KEY, String.valueOf(maxUrls));
        settings.setProperty(MAX_SOURCE_SIZE_MIB_KEY,
                String.valueOf(maxSourceSizeMiB));
    }
}
