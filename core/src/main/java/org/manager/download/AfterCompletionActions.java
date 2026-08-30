package org.manager.download;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.AfterCompletionActionListener;

/**
 * After-completion action management: registration, inspection, listener
 * subscription, and execution. Consumers register exclusively through this
 * facade (never through the underlying action manager directly).
 */
public interface AfterCompletionActions {

    /**
     * Adds an after-completion action to be executed when a download completes.
     *
     * @param download The download to add the action for
     * @param action The action to be executed after completion
     */
    void addAfterCompletionAction(Download download, AfterCompletionAction action);

    /** Sets the one application-wide action included in every completion. */
    void setGlobalAfterCompletionAction(AfterCompletionAction action);

    /**
     * Removes an after-completion action from a download.
     *
     * @param download The download to remove the action from
     * @param action The action to remove
     * @return true if the action was removed, false otherwise
     */
    boolean removeAfterCompletionAction(Download download, AfterCompletionAction action);

    /**
     * Gets all after-completion actions for a download.
     *
     * @param download The download to get actions for
     * @return List of after-completion actions
     */
    List<AfterCompletionAction> getAfterCompletionActions(Download download);

    /**
     * Adds a listener for after-completion action events.
     *
     * @param listener The listener to add
     */
    void addAfterCompletionActionListener(AfterCompletionActionListener listener);

    /**
     * Removes a listener for after-completion action events.
     *
     * @param listener The listener to remove
     */
    void removeAfterCompletionActionListener(AfterCompletionActionListener listener);

    /**
     * Executes all after-completion actions for a download.
     *
     * @param download The download to execute actions for
     * @return A future that completes when all actions are done
     */
    CompletableFuture<Void> executeAfterCompletionActions(Download download);
}
