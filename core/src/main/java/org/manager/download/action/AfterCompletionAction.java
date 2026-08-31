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
        PLAY_SOUND,//implemeted
        MOVE_FILE,//implemeted
        EXTRACT_ARCHIVE,
        SLEEP_COMPUTER,
        SHUTDOWN_COMPUTER,//implemeted
        EXECUTE_COMMAND,
        ANTIVIRUS_CHECK,//implemeted
        CHECKSUM_VALIDATION,
        DOWNLOAD_SUBTITLES
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
     * Get a user-friendly description of this action.
     *
     * @return The action description
     */
    String getDescription();

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
