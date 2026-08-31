package org.subliminal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Options for one Subliminal subtitle-download operation. */
public final class SubliminalSettings {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern LANGUAGE_TAG = Pattern.compile(
            "(?i)[a-z]{2,3}(?:-[a-z0-9]{2,8})*");

    private List<String> languages = List.of("en");
    private Duration timeout = DEFAULT_TIMEOUT;

    public List<String> getLanguages() {
        return languages;
    }

    public SubliminalSettings setLanguages(List<String> languages) {
        this.languages = normalizeLanguages(languages);
        return this;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public SubliminalSettings setTimeout(Duration timeout) {
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? DEFAULT_TIMEOUT : timeout;
        return this;
    }

    public SubliminalSettings copy() {
        return new SubliminalSettings()
                .setLanguages(languages)
                .setTimeout(timeout);
    }

    /** Parses comma-separated IETF language tags, defaulting to English. */
    public static List<String> parseLanguages(String value) {
        if (value == null || value.isBlank()) {
            return List.of("en");
        }
        List<String> parsed = new ArrayList<>();
        for (String token : value.split(",")) {
            if (!token.isBlank()) {
                parsed.add(token);
            }
        }
        return normalizeLanguages(parsed);
    }

    private static List<String> normalizeLanguages(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String tag = value.strip();
                if (!LANGUAGE_TAG.matcher(tag).matches()) {
                    throw new IllegalArgumentException(
                            "Invalid subtitle language tag: " + tag);
                }
                normalized.add(normalizeLanguageTag(tag));
            }
        }
        return normalized.isEmpty() ? List.of("en") : List.copyOf(normalized);
    }

    private static String normalizeLanguageTag(String value) {
        String[] parts = value.split("-");
        StringBuilder normalized = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];
            normalized.append('-');
            if (part.length() == 2
                    || (part.length() == 3 && part.chars().allMatch(Character::isDigit))) {
                normalized.append(part.toUpperCase(Locale.ROOT));
            } else if (part.length() == 4) {
                normalized.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.substring(1).toLowerCase(Locale.ROOT));
            } else {
                normalized.append(part.toLowerCase(Locale.ROOT));
            }
        }
        return normalized.toString();
    }
}
