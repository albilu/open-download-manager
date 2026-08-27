package org.manager.download;

import java.util.concurrent.CompletableFuture;

/**
 * Download-state persistence across restarts: snapshot the live download
 * list and restore it (including auto-resume of previously active
 * downloads).
 */
public interface SessionPersistence {

    /**
     * Saves the current download state to be resumed after restart.
     *
     * @return A future that completes when the state is saved
     */
    CompletableFuture<Void> saveState();

    /**
     * Loads saved download state.
     *
     * @return A future that completes when the state is loaded
     */
    CompletableFuture<Void> loadState();
}
