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
        AfterCompletionAction.Severity severity,
        Instant startedAt,
        Instant finishedAt) {

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
        severity = severity == null ? AfterCompletionAction.Severity.MEDIUM : severity;
    }

    public static CompletionActionResult running(AfterCompletionAction action) {
        return new CompletionActionResult(
                java.util.UUID.randomUUID().toString(),
                action.getType(),
                action.getDescription(),
                Status.RUNNING,
                "Running…",
                action.getSeverity(),
                Instant.now(),
                null);
    }

    public CompletionActionResult finished(Status finalStatus, String resultMessage) {
        if (finalStatus == Status.RUNNING) {
            throw new IllegalArgumentException("A finished result cannot remain RUNNING");
        }
        return new CompletionActionResult(id, actionType, description, finalStatus,
                resultMessage, severity, startedAt, Instant.now());
    }

    @JsonIgnore
    public boolean isRunning() {
        return status == Status.RUNNING;
    }
}
