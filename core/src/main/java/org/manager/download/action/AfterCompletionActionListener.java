package org.manager.download.action;

import org.manager.download.Download;

import java.util.List;

/**
 * Interface for listening to after-completion action events.
 */
public interface AfterCompletionActionListener {

    /**
     * Called when an after-completion action starts.
     *
     * @param download The download that completed
     * @param action The action being executed
     */
    void onActionStart(Download download, AfterCompletionAction action);

    /**
     * Called when an after-completion action completes successfully.
     *
     * @param download The download that completed
     * @param action The action that was executed
     */
    void onActionComplete(Download download, AfterCompletionAction action);

    /**
     * Called when an after-completion action fails.
     *
     * @param download The download that completed
     * @param action The action that failed
     * @param errorMessage The error message
     * @param severity The severity level of the failure
     */
    void onActionError(Download download, AfterCompletionAction action, String errorMessage, AfterCompletionAction.Severity severity);

    /**
     * Called when all after-completion actions for a download have been executed.
     *
     * @param download The download that completed
     * @param successfulActions List of actions that were executed successfully
     * @param failedActions List of actions that failed
     */
    void onAllActionsComplete(Download download, List<AfterCompletionAction> successfulActions,
                             List<AfterCompletionAction> failedActions);
}
