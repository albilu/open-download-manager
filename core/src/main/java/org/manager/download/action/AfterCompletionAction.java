package org.manager.download.action;

import org.manager.download.Download;

/**
 * Interface for actions to be executed after a download is completed.
 */
public interface AfterCompletionAction {

    /**
     * The severity level of an action failure.
     */
    enum Severity {
        LOW,     // Cosmetic failures (e.g., sound not playing)
        MEDIUM,  // Important but not critical failures (e.g., file move failed)
        HIGH,    // Critical failures that may affect system or data integrity
        CRITICAL // System-critical failures (e.g., shutdown command failed)
    }

    /**
     * The type of action to be executed after download completion.
     */
    enum ActionType {
        PLAY_SOUND(0, false, false),
        CHECKSUM_VALIDATION(1, true, false),
        ANTIVIRUS_CHECK(2, true, false),
        DOWNLOAD_SUBTITLES(3, true, false),
        EXECUTE_COMMAND(4, true, false),
        MOVE_FILE(5, true, false),//unimplemented
        EXTRACT_ARCHIVE(6, true, false),//unimplemented
        SLEEP_COMPUTER(7, false, true),
        SHUTDOWN_COMPUTER(8, false, true);

        private final int priority;
        private final boolean contributesToFinalizingProgress;
        private final boolean global;

        ActionType(int priority, boolean contributesToFinalizingProgress,
                boolean global) {
            this.priority = priority;
            this.contributesToFinalizingProgress = contributesToFinalizingProgress;
            this.global = global;
        }

        /** Execution priority; lower values run first and match declaration order. */
        public int getPriority() {
            return priority;
        }

        /** Whether this action should animate a completed download's progress row. */
        public boolean contributesToFinalizingProgress() {
            return contributesToFinalizingProgress;
        }

        /** Whether this action runs once after the whole download set is idle. */
        public boolean isGlobal() {
            return global;
        }
    }

    /**
     * Execute the action on the specified download.
     *
     * @param download The completed download
     * @return true if the action was executed successfully, false otherwise
     */
    boolean execute(Download download);

    /**
     * Get the type of this action.
     *
     * @return The action type
     */
    ActionType getType();

    /**
     * Execution priority. Implementations normally inherit their type's
     * priority so the enum declaration remains the single ordering policy.
     */
    default int getPriority() {
        return getType().getPriority();
    }

    /** Whether the action is a once-per-idle-cycle application action. */
    default boolean isGlobal() {
        return getType().isGlobal();
    }

    /** Whether the action should drive indeterminate finalization progress. */
    default boolean contributesToFinalizingProgress() {
        return getType().contributesToFinalizingProgress();
    }

    /**
     * Get a user-friendly description of this action.
     *
     * @return The action description
     */
    String getDescription();

    /** User-facing result text recorded when the action succeeds. */
    default String getResultMessage() {
        return "Completed successfully";
    }

    /** User-facing result text recorded when {@link #execute} returns false. */
    default String getFailureMessage() {
        return "Action did not complete successfully";
    }

    /**
     * Detailed output produced by the most recent execution. This is kept
     * separate from the concise result message so the download list can show
     * a readable outcome while the Actions tab can expose command/scanner
     * logs on demand.
     *
     * @return captured action output, or an empty string when none is available
     */
    default String getOutput() {
        return "";
    }

    /**
     * Get the severity level of this action when it fails.
     * This determines how the user should be notified if execute() returns false.
     *
     * @return The severity level of action failure
     */
    Severity getSeverity();

    /**
     * Cancel the action if it's in progress.
     *
     * @return true if the action was canceled successfully, false otherwise
     */
    boolean cancel();
}
