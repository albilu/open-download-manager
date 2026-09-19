package org.manager.util;

import java.util.List;
import java.util.Locale;

/**
 * Single source of truth for user-visible byte-size unit labels.
 *
 * <p>Display labels follow the desktop language; byte quantities remain unchanged.
 */
public final class SizeUnits {

    /** One tebibyte in bytes (1024^4); matches the 1024-based display convention. */
    public static final long TEBIBYTE = 1099511627776L;

    private static final List<String> ENGLISH = List.of("B", "KB", "MB", "GB", "TB");
    private static final List<String> FRENCH = List.of("o", "Ko", "Mo", "Go", "To");

    private SizeUnits() {
    }

    /** Active unit labels, selected from the system message locale. */
    public static List<String> current() {
        return forLocale(SystemLocale.display());
    }

    /** French labels (o/Ko/Mo/Go/To). */
    public static List<String> french() {
        return FRENCH;
    }

    /**
     * Resolves French regional locales to octet labels, with English fallback.
     *
     * @param locale the caller's locale
     * @return the locale's unit label table
     */
    public static List<String> forLocale(Locale locale) {
        return locale != null && "fr".equals(locale.getLanguage()) ? FRENCH : ENGLISH;
    }
}
