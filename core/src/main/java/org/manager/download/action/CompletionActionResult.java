package org.manager.download.action;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, persistable history entry for one completion-action execution.
 */
public record CompletionActionResult(
        String id,
        AfterCompletionAction.ActionType actionType,
        String description,
        Status status,
        String message,
        String output,
        AfterCompletionAction.Severity severity,
        Instant startedAt,
        Instant finishedAt) {

    private static final int MAX_PERSISTED_OUTPUT_LENGTH = 1_048_576;

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED,
        INTERRUPTED
    }

    public CompletionActionResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        description = description == null || description.isBlank()
                ? actionType.name() : description;
        message = message == null ? "" : message;
        output = normalizeOutput(output);
        severity = severity == null ? AfterCompletionAction.Severity.MEDIUM : severity;
    }

    /** Compatibility constructor for records written before detailed output was captured. */
    public CompletionActionResult(String id,
            AfterCompletionAction.ActionType actionType,
            String description,
            Status status,
            String message,
            AfterCompletionAction.Severity severity,
            Instant startedAt,
            Instant finishedAt) {
        this(id, actionType, description, status, message, "", severity,
                startedAt, finishedAt);
    }

    public static CompletionActionResult running(AfterCompletionAction action) {
        return new CompletionActionResult(
                java.util.UUID.randomUUID().toString(),
                action.getType(),
                action.getDescription(),
                Status.RUNNING,
                "Running…",
                "",
                action.getSeverity(),
                Instant.now(),
                null);
    }

    public CompletionActionResult finished(Status finalStatus, String resultMessage) {
        return finished(finalStatus, resultMessage, output);
    }

    public CompletionActionResult finished(Status finalStatus, String resultMessage,
            String detailedOutput) {
        if (finalStatus == Status.RUNNING) {
            throw new IllegalArgumentException("A finished result cannot remain RUNNING");
        }
        return new CompletionActionResult(id, actionType, description, finalStatus,
                resultMessage, detailedOutput, severity, startedAt, Instant.now());
    }

    @JsonIgnore
    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    @JsonIgnore
    public boolean exposesOutput() {
        return actionType.contributesToFinalizingProgress();
    }

    private static String normalizeOutput(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= MAX_PERSISTED_OUTPUT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_PERSISTED_OUTPUT_LENGTH)
                + "\n\n[Output truncated by ODM]";
    }
}
