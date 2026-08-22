package org.manager.download.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.manager.download.Download;

/**
 * Manager for executing actions after a download is completed.
 */
public class AfterCompletionActionManager {

    private static final Logger LOGGER = Logger.getLogger(AfterCompletionActionManager.class.getName());

    private final Map<String, List<AfterCompletionAction>> downloadActions;
    private final List<AfterCompletionActionListener> listeners;
    private final ExecutorService executorService;

    /**
     * Creates a new AfterCompletionActionManager.
     */
    public AfterCompletionActionManager() {
        // Written from UI threads while completions execute from worker
        // threads; a plain HashMap loses entries or throws CME on resize races.
        this.downloadActions = new java.util.concurrent.ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * Add an action to be executed after a download completes.
     *
     * @param download The download
     * @param action   The action to be executed
     */
    public void addAction(Download download, AfterCompletionAction action) {
        String downloadId = download.getId();

        List<AfterCompletionAction> actions = downloadActions.computeIfAbsent(downloadId,
                k -> Collections.synchronizedList(new ArrayList<>()));
        // Idempotent per instance: registering the same action twice (e.g. a
        // UI re-adding its global completion action on a repeated event)
        // must not double-execute it
        synchronized (actions) {
            if (!actions.contains(action)) {
                actions.add(action);
            }
        }
    }

    /**
     * Remove an action from a download.
     *
     * @param download The download
     * @param action   The action to be removed
     * @return true if the action was removed, false otherwise
     */
    public boolean removeAction(Download download, AfterCompletionAction action) {
        String downloadId = download.getId();

        if (downloadActions.containsKey(downloadId)) {
            return downloadActions.get(downloadId).remove(action);
        }

        return false;
    }

    /**
     * Get all actions associated with a download.
     *
     * @param download The download
     * @return List of actions for the download
     */
    public List<AfterCompletionAction> getActions(Download download) {
        String downloadId = download.getId();

        return downloadActions.getOrDefault(downloadId, new ArrayList<>());
    }

    /**
     * Clear all actions for a download.
     *
     * @param download The download
     */
    public void clearActions(Download download) {
        String downloadId = download.getId();

        downloadActions.remove(downloadId);
    }

    /**
     * Execute all actions for a download that has completed. Actions run
     * concurrently (one task each, joined with allOf) so independent actions
     * such as notification, antivirus scan, and file moves do not serialize
     * behind the slowest one. onAllActionsComplete fires once, after every
     * action has settled.
     *
     * @param download The completed download
     * @return CompletableFuture that completes when all actions are done
     */
    public CompletableFuture<Void> executeActions(Download download) {
        String downloadId = download.getId();

        List<AfterCompletionAction> actions = downloadActions.get(downloadId);
        if (actions == null || actions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<AfterCompletionAction> successfulActions = Collections.synchronizedList(new ArrayList<>());
        List<AfterCompletionAction> failedActions = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (AfterCompletionAction action : new ArrayList<>(actions)) {
            futures.add(submitAsync(() -> {
                try {
                    // Notify listeners that action is starting
                    notifyActionStart(download, action);

                    // Execute the action
                    boolean success = action.execute(download);

                    if (success) {
                        successfulActions.add(action);
                        notifyActionComplete(download, action);
                    } else {
                        failedActions.add(action);
                        notifyActionError(download, action, "Action returned false", action.getSeverity());
                    }
                } catch (Exception e) {
                    failedActions.add(action);
                    LOGGER.log(Level.WARNING, "Error executing after-completion action: "
                            + action.getDescription(), e);
                    notifyActionError(download, action, e.getMessage(), action.getSeverity());
                }
            }));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> notifyAllActionsComplete(download, successfulActions, failedActions));
    }

    /**
     * Submits a task to the executor, falling back to the common pool when
     * the executor has already been shut down.
     */
    private CompletableFuture<Void> submitAsync(Runnable task) {
        try {
            return CompletableFuture.runAsync(task, executorService);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOGGER.warning("Action executor is shut down; falling back to common pool");
            return CompletableFuture.runAsync(task);
        }
    }

    /**
     * Add a listener for after-completion action events.
     *
     * @param listener The listener to add
     */
    public void addListener(AfterCompletionActionListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener for after-completion action events.
     *
     * @param listener The listener to remove
     */
    public void removeListener(AfterCompletionActionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Shutdown the action manager and its executor service.
     */
    public void shutdown() {
        executorService.shutdown();
    }

    // Notification methods
    private void notifyActionStart(Download download, AfterCompletionAction action) {
        for (AfterCompletionActionListener listener : listeners) {
            try {
                listener.onActionStart(download, action);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of action start", e);
            }
        }
    }

    private void notifyActionComplete(Download download, AfterCompletionAction action) {
        for (AfterCompletionActionListener listener : listeners) {
            try {
                listener.onActionComplete(download, action);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of action complete", e);
            }
        }
    }

    private void notifyActionError(Download download, AfterCompletionAction action, String errorMessage,
            AfterCompletionAction.Severity severity) {
        for (AfterCompletionActionListener listener : listeners) {
            try {
                listener.onActionError(download, action, errorMessage, severity);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of action error", e);
            }
        }
    }

    private void notifyAllActionsComplete(Download download, List<AfterCompletionAction> successfulActions,
            List<AfterCompletionAction> failedActions) {
        for (AfterCompletionActionListener listener : listeners) {
            try {
                listener.onAllActionsComplete(download, successfulActions, failedActions);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of all actions complete", e);
            }
        }
    }
}
