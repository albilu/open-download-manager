package org.manager.download;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, persistable history entry for a user-requested download
 * operation shown in the Details tab.
 *
 * <p>This is deliberately separate from after-completion action history:
 * Recheck Data is a manual aria2 operation, not a checksum completion action.</p>
 */
public record DownloadOperationResult(
        String id,
        OperationType operationType,
        String description,
        Status status,
        String message,
        Instant startedAt,
        Instant finishedAt) {

    public enum OperationType {
        RECHECK_DATA("Recheck Data");

        private final String description;

        OperationType(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public enum Status {
        RUNNING,
        ACCEPTED,
        FAILED,
        INTERRUPTED
    }

    public DownloadOperationResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        description = description == null || description.isBlank()
                ? operationType.description() : description;
        message = message == null ? "" : message;
    }

    public static DownloadOperationResult running(OperationType operationType) {
        Objects.requireNonNull(operationType, "operationType");
        return new DownloadOperationResult(
                UUID.randomUUID().toString(),
                operationType,
                operationType.description(),
                Status.RUNNING,
                "Requesting operation…",
                Instant.now(),
                null);
    }

    public DownloadOperationResult finished(Status finalStatus, String resultMessage) {
        if (finalStatus == Status.RUNNING) {
            throw new IllegalArgumentException("A finished result cannot remain RUNNING");
        }
        return new DownloadOperationResult(id, operationType, description, finalStatus,
                resultMessage, startedAt, Instant.now());
    }

    @JsonIgnore
    public boolean isRunning() {
        return status == Status.RUNNING;
    }
}
