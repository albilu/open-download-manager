package org.manager.download.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.download.Download;

/**
 * Manager for executing actions after a download is completed.
 */
public class AfterCompletionActionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AfterCompletionActionManager.class);
    private static final int DEFAULT_POOL_SIZE =
            Math.max(8, Math.min(32, Runtime.getRuntime().availableProcessors() * 2));
    private static final long TERMINATION_AWAIT_SECONDS = 10;

    private final Map<String, List<AfterCompletionAction>> downloadActions;
    private final List<AfterCompletionActionListener> listeners;
    private final ExecutorService executorService;
    private final java.util.Set<String> executedDownloads =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Snapshot of the actions selected in the application completion menu. */
    private volatile List<AfterCompletionAction> configuredActions = List.of();
    /** Ensures power actions execute once for each active-to-idle cycle. */
    private final java.util.concurrent.atomic.AtomicBoolean globalActionsExecuted =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean shutdown =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Creates a new AfterCompletionActionManager with a bounded daemon pool.
     */
    public AfterCompletionActionManager() {
        this(DEFAULT_POOL_SIZE, defaultThreadFactory());
    }

    AfterCompletionActionManager(int poolSize, java.util.concurrent.ThreadFactory threadFactory) {
        // Written from UI threads while completions execute from worker
        // threads; a plain HashMap loses entries or throws CME on resize races.
        this.downloadActions = new java.util.concurrent.ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.executorService = Executors.newFixedThreadPool(
                Math.max(1, poolSize), threadFactory);
    }

    private static java.util.concurrent.ThreadFactory defaultThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "odm-after-completion");
            t.setDaemon(true);
            return t;
        };
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

    public void setGlobalAction(AfterCompletionAction action) {
        setGlobalActions(action == null ? List.of() : List.of(action));
    }

    /** Replaces the settings-backed completion-action policy atomically. */
    public void setGlobalActions(List<AfterCompletionAction> actions) {
        this.configuredActions = sortedCopy(actions);
        // A changed policy should be eligible for the next completed batch.
        globalActionsExecuted.set(false);
    }

    /** Marks the beginning/resumption of work, rearming global power actions. */
    public void markDownloadActivity() {
        globalActionsExecuted.set(false);
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
     * Executes per-download actions in explicit priority order. Settings-backed
     * power actions are deliberately excluded; they are run by
     * {@link #executeGlobalActions(Download)} only after the manager establishes
     * that every download is terminal.
     *
     * @param download The completed download
     * @return CompletableFuture that completes when all actions are done
     */
    public CompletableFuture<Void> executeActions(Download download) {
        String downloadId = download.getId();

        if (!executedDownloads.add(downloadId)) {
            LOGGER.warn("Ignoring duplicate completion-action execution for " + downloadId);
            return CompletableFuture.completedFuture(null);
        }
        List<AfterCompletionAction> registered = downloadActions.remove(downloadId);
        List<AfterCompletionAction> actions = registered != null
                ? new ArrayList<>(registered) : new ArrayList<>();
        for (AfterCompletionAction configured : configuredActions) {
            if (!configured.isGlobal() && !actions.contains(configured)) {
                actions.add(configured);
            }
        }
        actions.removeIf(AfterCompletionAction::isGlobal);
        return executeOrdered(download, actions);
    }

    /**
     * Executes configured global actions once for the current idle cycle.
     * The caller owns the all-downloads-finished check; this method owns the
     * race-proof once-only claim.
     */
    public CompletableFuture<Void> executeGlobalActions(Download contextDownload) {
        List<AfterCompletionAction> actions = configuredActions.stream()
                .filter(AfterCompletionAction::isGlobal)
                .toList();
        if (actions.isEmpty()
                || !globalActionsExecuted.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        return executeOrdered(contextDownload, actions);
    }

    private CompletableFuture<Void> executeOrdered(Download download,
            List<AfterCompletionAction> requestedActions) {
        List<AfterCompletionAction> actions = sortedCopy(requestedActions);
        if (actions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<AfterCompletionAction> successfulActions = new ArrayList<>();
        List<AfterCompletionAction> failedActions = new ArrayList<>();
        CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);

        for (AfterCompletionAction action : actions) {
            sequence = sequence.thenCompose(ignored ->
                    executeOne(download, action, successfulActions, failedActions));
        }

        return sequence.thenRun(() ->
                notifyAllActionsComplete(download, successfulActions, failedActions));
    }

    private CompletableFuture<Void> executeOne(Download download,
            AfterCompletionAction action,
            List<AfterCompletionAction> successfulActions,
            List<AfterCompletionAction> failedActions) {
        String resultId = download.beginCompletionAction(action);
        CompletableFuture<Void> future = submitAsync(() -> {
            // A configured instance is shared across downloads. Synchronizing
            // the instance protects invocation-specific fields while the
            // ordered chain protects priority within one download.
            synchronized (action) {
                try {
                    notifyActionStart(download, action);
                    boolean success = action.execute(download);

                    if (success) {
                        successfulActions.add(action);
                        download.finishCompletionAction(resultId,
                                CompletionActionResult.Status.SUCCEEDED,
                                action.getResultMessage());
                        notifyActionComplete(download, action);
                    } else {
                        failedActions.add(action);
                        String failureMessage = action.getFailureMessage();
                        download.finishCompletionAction(resultId,
                                CompletionActionResult.Status.FAILED, failureMessage);
                        notifyActionError(download, action, failureMessage,
                                action.getSeverity());
                    }
                } catch (Exception e) {
                    failedActions.add(action);
                    LOGGER.warn("Error executing after-completion action: "
                            + action.getDescription(), e);
                    String failureMessage = e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage();
                    download.finishCompletionAction(resultId,
                            CompletionActionResult.Status.FAILED, failureMessage);
                    notifyActionError(download, action, failureMessage, action.getSeverity());
                }
            }
        });
        return future.whenComplete((ignored, error) -> {
            if (error == null) {
                return;
            }
            failedActions.add(action);
            String failureMessage = "Could not start action: "
                    + (error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage());
            download.finishCompletionAction(resultId,
                    CompletionActionResult.Status.FAILED, failureMessage);
            notifyActionError(download, action, failureMessage, action.getSeverity());
        });
    }

    private static List<AfterCompletionAction> sortedCopy(
            List<AfterCompletionAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(AfterCompletionAction::getPriority))
                .toList();
    }

    /**
     * Submits a task to the bounded pool. After shutdown the submission is
     * rejected and logged — no silent common-pool fallback.
     */
    private CompletableFuture<Void> submitAsync(Runnable task) {
        try {
            return CompletableFuture.runAsync(task, executorService);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOGGER.warn("Action executor is shut down; rejecting after-completion action submission");
            CompletableFuture<Void> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(e);
            return rejected;
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
     * Shutdown the action manager and its executor service with a bounded
     * termination wait; submissions afterwards are rejected.
     */
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(TERMINATION_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    LOGGER.warn("Action executor did not terminate within "
                            + TERMINATION_AWAIT_SECONDS + "s; forcing");
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean isShutdown() {
        return shutdown.get() || executorService.isShutdown();
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executorService.awaitTermination(timeout, unit);
    }

    // Notification methods
    private void notifyActionStart(Download download, AfterCompletionAction action) {
        for (AfterCompletionActionListener listener : listeners) {
            try {
                listener.onActionStart(download, action);
            } catch (Exception e) {
                LOGGER.warn("Error notifying listener of action start", e);
            }
        }
    }

    private void notifyActionComplete(Download download, AfterCompletionAction action) {
        for (AfterCompletionActionListener listener : listeners) {
            try {
                listener.onActionComplete(download, action);
            } catch (Exception e) {
                LOGGER.warn("Error notifying listener of action complete", e);
            }
        }
    }

    private void notifyActionError(Download download, AfterCompletionAction action, String errorMessage,
            AfterCompletionAction.Severity severity) {
        for (AfterCompletionActionListener listener : listeners) {
            try {
                listener.onActionError(download, action, errorMessage, severity);
            } catch (Exception e) {
                LOGGER.warn("Error notifying listener of action error", e);
            }
        }
    }

    private void notifyAllActionsComplete(Download download, List<AfterCompletionAction> successfulActions,
            List<AfterCompletionAction> failedActions) {
        for (AfterCompletionActionListener listener : listeners) {
            try {
                listener.onAllActionsComplete(download, successfulActions, failedActions);
            } catch (Exception e) {
                LOGGER.warn("Error notifying listener of all actions complete", e);
            }
        }
    }
}
