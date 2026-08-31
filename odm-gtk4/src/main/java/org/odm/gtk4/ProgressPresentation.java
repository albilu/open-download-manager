package org.odm.gtk4;

import java.util.Locale;

/** Consistent display and fill values for all download percentages. */
final class ProgressPresentation {

    private ProgressPresentation() {
    }

    static String percentage(double value) {
        return String.format(Locale.ROOT, "%.2f%%", clamp(value));
    }

    static int wholePercentage(double value) {
        return (int) Math.round(clamp(value));
    }

    static double fraction(double percentage) {
        return clamp(percentage) / 100.0;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return Math.max(0, Math.min(100, value));
    }
}
