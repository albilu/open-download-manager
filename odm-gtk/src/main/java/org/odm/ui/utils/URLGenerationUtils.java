package org.odm.ui.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Utility class for generating URL sequences with patterns.
 */
public final class URLGenerationUtils {

    private static final Logger LOGGER = Logger.getLogger(URLGenerationUtils.class.getName());

    // URL pattern matching
    private static final Pattern NUM_PATTERN = Pattern.compile("\\{num\\}");
    private static final Pattern CHAR_PATTERN = Pattern.compile("\\{char\\}");

    private URLGenerationUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Generates URLs from a pattern with numeric placeholders.
     * 
     * @param pattern URL pattern with {num} placeholders
     * @param start   Starting number
     * @param end     Ending number (inclusive)
     * @param digits  Minimum number of digits (padded with zeros)
     * @return List of generated URLs
     */
    public static List<String> generateNumericSequence(String pattern, int start, int end, int digits) {
        List<String> urls = new ArrayList<>();

        if (pattern == null || !pattern.contains("{num}")) {
            LOGGER.warning("Invalid pattern for numeric sequence: " + pattern);
            return urls;
        }

        for (int i = start; i <= end; i++) {
            String formattedNum = String.format("%0" + digits + "d", i);
            String url = NUM_PATTERN.matcher(pattern).replaceAll(formattedNum);
            urls.add(url);
        }

        return urls;
    }

    /**
     * Generates URLs from a pattern with character placeholders.
     * 
     * @param pattern URL pattern with {char} placeholders
     * @param start   Starting character
     * @param end     Ending character (inclusive)
     * @return List of generated URLs
     */
    public static List<String> generateCharacterSequence(String pattern, char start, char end) {
        List<String> urls = new ArrayList<>();

        if (pattern == null || !pattern.contains("{char}")) {
            LOGGER.warning("Invalid pattern for character sequence: " + pattern);
            return urls;
        }

        for (char c = start; c <= end; c++) {
            String url = CHAR_PATTERN.matcher(pattern).replaceAll(String.valueOf(c));
            urls.add(url);
        }

        return urls;
    }

    /**
     * Generates URLs from a pattern with both numeric and character placeholders.
     * 
     * @param pattern   URL pattern with {num} and {char} placeholders
     * @param numStart  Starting number
     * @param numEnd    Ending number (inclusive)
     * @param digits    Minimum number of digits (padded with zeros)
     * @param charStart Starting character
     * @param charEnd   Ending character (inclusive)
     * @return List of generated URLs
     */
    public static List<String> generateCombinedSequence(String pattern, int numStart, int numEnd, int digits,
            char charStart, char charEnd) {
        List<String> urls = new ArrayList<>();

        if (pattern == null || !pattern.contains("{num}") || !pattern.contains("{char}")) {
            LOGGER.warning("Invalid pattern for combined sequence: " + pattern);
            return urls;
        }

        for (char c = charStart; c <= charEnd; c++) {
            for (int i = numStart; i <= numEnd; i++) {
                String formattedNum = String.format("%0" + digits + "d", i);
                String url = pattern;
                url = CHAR_PATTERN.matcher(url).replaceAll(String.valueOf(c));
                url = NUM_PATTERN.matcher(url).replaceAll(formattedNum);
                urls.add(url);
            }
        }

        return urls;
    }

    /**
     * Validates if a pattern contains valid placeholders.
     * 
     * @param pattern URL pattern to validate
     * @return true if pattern contains valid placeholders
     */
    public static boolean isValidPattern(String pattern) {
        return pattern != null && !pattern.isEmpty() &&
                (pattern.contains("{num}") || pattern.contains("{char}"));
    }
}
