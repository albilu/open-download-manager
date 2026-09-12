package org.manager.util;

import java.util.List;
import java.util.Locale;

/**
 * Single source of truth for user-visible byte-size unit labels.
 *
 * <p>English labels are the only active table. The French octet-based labels
 * are defined and tested but not displayed yet; flip {@link #forLocale} when
 * the French localisation lands.
 */
public final class SizeUnits {

    /** One tebibyte in bytes (1024^4); matches the 1024-based display convention. */
    public static final long TEBIBYTE = 1099511627776L;

    private static final List<String> ENGLISH = List.of("B", "KB", "MB", "GB", "TB");
    private static final List<String> FRENCH = List.of("o", "Ko", "Mo", "Go", "To");

    private SizeUnits() {
    }

    /** Active unit labels. English until the French localisation lands. */
    public static List<String> current() {
        return ENGLISH;
    }

    /** Prepared French labels (o/Ko/Mo/Go/To); not displayed yet. */
    public static List<String> french() {
        return FRENCH;
    }

    /**
     * Localisation hook: resolves labels for a locale. Currently returns
     * English for every locale; return the French table for French locales
     * when localisation lands.
     *
     * @param locale the caller's locale, currently ignored
     * @return the English label table
     */
    public static List<String> forLocale(Locale locale) {
        return ENGLISH;
    }
}
