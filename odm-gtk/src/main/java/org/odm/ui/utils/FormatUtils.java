package org.odm.ui.utils;

import java.text.DecimalFormat;

/**
 * Utility class for formatting various types of data.
 */
public final class FormatUtils {

    private FormatUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Formats file size in human-readable format.
     * 
     * @param bytes Size in bytes
     * @return Human-readable size string (e.g., "10.5 MB")
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        String[] units = { "B", "KB", "MB", "GB", "TB" };
        int unitIndex = 0;
        double size = bytes;

        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }

        DecimalFormat df = new DecimalFormat("#.##");
        return df.format(size) + " " + units[unitIndex];
    }

    /**
     * Formats a percentage value.
     * 
     * @param value Percentage value (0-100)
     * @return Formatted percentage string
     */
    public static String formatPercentage(double value) {
        DecimalFormat df = new DecimalFormat("#.#");
        return df.format(value) + "%";
    }

    /**
     * Formats a speed value (bytes per second) to human readable format.
     * 
     * @param bytesPerSecond Speed in bytes per second
     * @return Formatted speed string (e.g., "1.2 MB/s")
     */
    public static String formatSpeed(long bytesPerSecond) {
        String size = formatFileSize(bytesPerSecond);
        return size + "/s";
    }

    /**
     * Parses aria2-style size strings (e.g., "99.9MiB", "1.2GiB", "3.5GiB
     * (3,770,274,697)") to bytes.
     * Supports both binary (MiB, GiB) and decimal (MB, GB) units.
     * If the string contains exact byte count in parentheses, uses that value.
     * 
     * @param sizeString Size string from aria2 output
     * @return Size in bytes, or 0 if parsing fails
     */
    public static long parseFileSize(String sizeString) {
        if (sizeString == null || sizeString.trim().isEmpty()) {
            return 0;
        }

        String trimmed = sizeString.trim();

        // Check if the string contains byte count in parentheses (e.g., "3.5GiB
        // (3,770,274,697)")
        if (trimmed.contains("(") && trimmed.contains(")")) {
            int startParen = trimmed.indexOf("(");
            int endParen = trimmed.indexOf(")", startParen);
            if (startParen < endParen) {
                String bytesStr = trimmed.substring(startParen + 1, endParen);
                // Remove commas from byte count
                bytesStr = bytesStr.replace(",", "");
                try {
                    return Long.parseLong(bytesStr);
                } catch (NumberFormatException e) {
                    // Fall through to parse the human-readable part
                }
            }
        }

        // Handle numeric-only strings as bytes
        try {
            return Long.parseLong(trimmed.replace(",", ""));
        } catch (NumberFormatException e) {
            // Continue to parse with units
        }

        // Extract the human-readable part (before parentheses if they exist)
        String sizeWithUnit = trimmed;
        if (trimmed.contains("(")) {
            sizeWithUnit = trimmed.substring(0, trimmed.indexOf("(")).trim();
        }

        // Extract numeric part and unit
        String numericPart = "";
        String unit = "";

        for (int i = 0; i < sizeWithUnit.length(); i++) {
            char c = sizeWithUnit.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                numericPart += c;
            } else {
                unit = sizeWithUnit.substring(i).trim();
                break;
            }
        }

        if (numericPart.isEmpty()) {
            return 0;
        }

        try {
            double value = Double.parseDouble(numericPart);
            long multiplier = getMultiplierForUnit(unit);
            return Math.round(value * multiplier);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Gets the byte multiplier for a given unit string.
     */
    private static long getMultiplierForUnit(String unit) {
        if (unit == null || unit.isEmpty()) {
            return 1; // Assume bytes
        }

        String upperUnit = unit.toUpperCase();

        // Binary units (1024-based)
        switch (upperUnit) {
            case "KIB":
            case "KI":
                return 1024L;
            case "MIB":
            case "MI":
                return 1024L * 1024L;
            case "GIB":
            case "GI":
                return 1024L * 1024L * 1024L;
            case "TIB":
            case "TI":
                return 1024L * 1024L * 1024L * 1024L;
            case "PIB":
            case "PI":
                return 1024L * 1024L * 1024L * 1024L * 1024L;
        }

        // Decimal units (1000-based)
        switch (upperUnit) {
            case "KB":
            case "K":
                return 1000L;
            case "MB":
            case "M":
                return 1000L * 1000L;
            case "GB":
            case "G":
                return 1000L * 1000L * 1000L;
            case "TB":
            case "T":
                return 1000L * 1000L * 1000L * 1000L;
            case "PB":
            case "P":
                return 1000L * 1000L * 1000L * 1000L * 1000L;
        }

        // Default to bytes
        return 1L;
    }
}
