package org.odm.gtk4;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.gnome.gio.Icon;
import org.gnome.gio.ThemedIcon;
import org.manager.download.Download;
import org.manager.download.action.CompletionActionResult;

/** Aggregates one record's after-completion results for the download list. */
final class CompletionActionPresentation {

    enum Outcome {
        NONE,
        RUNNING,
        SUCCEEDED,
        PARTIAL,
        FAILED
    }

    record Summary(Outcome outcome, int succeeded, int failed, int running, int total) {
    }

    private static final Map<Outcome, Icon> OUTCOME_ICONS = new EnumMap<>(Outcome.class);

    private CompletionActionPresentation() {
    }

    static Summary summarize(Download download) {
        List<CompletionActionResult> results = download == null
                ? List.of() : download.getCompletionActionResults().stream()
                        .filter(java.util.Objects::nonNull)
                        .toList();
        if (results.isEmpty()) {
            return new Summary(Outcome.NONE, 0, 0, 0, 0);
        }

        int succeeded = 0;
        int failed = 0;
        int running = 0;
        for (CompletionActionResult result : results) {
            switch (result.status()) {
                case SUCCEEDED -> succeeded++;
                case FAILED, INTERRUPTED -> failed++;
                case RUNNING -> running++;
            }
        }
        Outcome outcome;
        if (running > 0) {
            outcome = Outcome.RUNNING;
        } else if (succeeded == results.size()) {
            outcome = Outcome.SUCCEEDED;
        } else if (failed == results.size()) {
            outcome = Outcome.FAILED;
        } else {
            outcome = Outcome.PARTIAL;
        }
        return new Summary(outcome, succeeded, failed, running, results.size());
    }

    /** Shows only a terminal aggregate outcome; non-terminal cells stay empty. */
    static Icon icon(Download download) {
        Outcome outcome = summarize(download).outcome();
        return outcomeIcon(outcome);
    }

    static Icon outcomeIcon(Outcome outcome) {
        String iconName = outcomeIconName(outcome);
        if (iconName == null) {
            return null;
        }
        synchronized (OUTCOME_ICONS) {
            return OUTCOME_ICONS.computeIfAbsent(outcome, ignored -> createOutcomeIcon(outcome));
        }
    }

    private static Icon createOutcomeIcon(Outcome outcome) {
        if (outcome == Outcome.SUCCEEDED) {
            return ThemedIcon.fromNames(new String[]{
                "checkbox-checked-symbolic",
                "emblem-default",
                "checkmark-symbolic"
            });
        }
        return new ThemedIcon(outcomeIconName(outcome));
    }

    static String tooltip(Download download) {
        if (download == null) {
            return null;
        }
        Summary summary = summarize(download);
        return switch (summary.outcome()) {
            case NONE -> null;
            case RUNNING -> "After-completion actions running (%d/%d finished)"
                    .formatted(summary.succeeded() + summary.failed(), summary.total());
            case SUCCEEDED -> "All after-completion actions succeeded (%d/%d)"
                    .formatted(summary.succeeded(), summary.total());
            case PARTIAL -> "After-completion actions partially succeeded (%d/%d)"
                    .formatted(summary.succeeded(), summary.total());
            case FAILED -> "All after-completion actions failed (%d/%d)"
                    .formatted(summary.failed(), summary.total());
        };
    }

    static String outcomeIconName(Outcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> "checkbox-checked-symbolic";
            case PARTIAL -> "dialog-warning-symbolic";
            case FAILED -> "dialog-error-symbolic";
            case NONE, RUNNING -> null;
        };
    }
}
