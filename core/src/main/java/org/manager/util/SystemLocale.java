package org.manager.util;

import java.util.Locale;
import java.util.Map;

/** Linux desktop message locale; numeric formatting continues to use Java's FORMAT locale. */
public final class SystemLocale {
    private SystemLocale() { }

    public static Locale display() {
        return display(System.getenv(), Locale.getDefault(Locale.Category.DISPLAY));
    }

    static Locale display(Map<String, String> environment, Locale fallback) {
        String name = messageLocaleName(environment);
        String base = name.split("[.@]", 2)[0];
        // GNU gettext disables translations for the C locale, including its
        // UTF-8 variant, even when LANGUAGE names another language.
        if (base.equals("C") || base.equals("POSIX")) { return Locale.ENGLISH; }
        String languages = environment.getOrDefault("LANGUAGE", "");
        if (languages.isBlank()) { languages = name; }
        if (languages.isBlank()) { return fallback; }
        for (String language : languages.split(":")) {
            String tag = language.split("[.@]", 2)[0].replace('_', '-');
            Locale candidate = Locale.forLanguageTag(tag);
            if (candidate.getLanguage().equals("fr") || candidate.getLanguage().equals("en")) {
                return candidate;
            }
            if (language.equals("C") || language.equals("POSIX")) { return Locale.ENGLISH; }
        }
        return Locale.ENGLISH;
    }

    public static String messageLocaleName(Map<String, String> environment) {
        for (String variable : new String[]{"LC_ALL", "LC_MESSAGES", "LANG"}) {
            String value = environment.getOrDefault(variable, "");
            if (!value.isBlank()) { return value; }
        }
        return "";
    }
}
