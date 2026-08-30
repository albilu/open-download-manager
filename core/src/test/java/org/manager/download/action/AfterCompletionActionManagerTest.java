package org.manager.download.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for AfterCompletionActionManager and download completion handling.
 */
@DisplayName("After Completion Action Manager Tests")
class AfterCompletionActionManagerTest {

    @TempDir
    Path tempDir;

    private AfterCompletionActionManager actionManager;
    private Download testDownload;
    private TestAfterCompletionActionListener testListener;

    @BeforeEach
    void setUp() {
        actionManager = new AfterCompletionActionManager();
        testDownload = createTestDownload();
        testListener = new TestAfterCompletionActionListener();
        actionManager.addListener(testListener);
    }

    @Test
    @DisplayName("Should add and retrieve actions for download")
    void shouldAddAndRetrieveActionsForDownload() {
        TestAfterCompletionAction action1 = new TestAfterCompletionAction(AfterCompletionAction.ActionType.PLAY_SOUND, true);
        TestAfterCompletionAction action2 = new TestAfterCompletionAction(AfterCompletionAction.ActionType.MOVE_FILE, true);

        actionManager.addAction(testDownload, action1);
        actionManager.addAction(testDownload, action2);

        List<AfterCompletionAction> actions = actionManager.getActions(testDownload);
        assertEquals(2, actions.size());
        assertTrue(actions.contains(action1));
        assertTrue(actions.contains(action2));
    }

    @Test
    @DisplayName("Should remove actions from download")
    void shouldRemoveActionsFromDownload() {
        TestAfterCompletionAction action1 = new TestAfterCompletionAction(AfterCompletionAction.ActionType.PLAY_SOUND, true);
        TestAfterCompletionAction action2 = new TestAfterCompletionAction(AfterCompletionAction.ActionType.MOVE_FILE, true);

        actionManager.addAction(testDownload, action1);
        actionManager.addAction(testDownload, action2);

        boolean removed = actionManager.removeAction(testDownload, action1);
        assertTrue(removed);

        List<AfterCompletionAction> actions = actionManager.getActions(testDownload);
        assertEquals(1, actions.size());
        assertFalse(actions.contains(action1));
        assertTrue(actions.contains(action2));
    }

    @Test
    @DisplayName("Should return false when removing non-existent action")
    void shouldReturnFalseWhenRemovingNonExistentAction() {
        TestAfterCompletionAction action = new TestAfterCompletionAction(AfterCompletionAction.ActionType.PLAY_SOUND, true);

        boolean removed = actionManager.removeAction(testDownload, action);
        assertFalse(removed);
    }

    @Test
    @DisplayName("Should clear all actions for download")
    void shouldClearAllActionsForDownload() {
        TestAfterCompletionAction action1 = new TestAfterCompletionAction(AfterCompletionAction.ActionType.PLAY_SOUND, true);
        TestAfterCompletionAction action2 = new TestAfterCompletionAction(AfterCompletionAction.ActionType.MOVE_FILE, true);

        actionManager.addAction(testDownload, action1);
        actionManager.addAction(testDownload, action2);

        actionManager.clearActions(testDownload);

        List<AfterCompletionAction> actions = actionManager.getActions(testDownload);
        assertTrue(actions.isEmpty());
    }

    @Test
    @DisplayName("Should execute actions successfully")
    void shouldExecuteActionsSuccessfully() throws Exception {
        TestAfterCompletionAction action1 = new TestAfterCompletionAction(AfterCompletionAction.ActionType.PLAY_SOUND, true);
        TestAfterCompletionAction action2 = new TestAfterCompletionAction(AfterCompletionAction.ActionType.MOVE_FILE, true);

        actionManager.addAction(testDownload, action1);
        actionManager.addAction(testDownload, action2);

        CompletableFuture<Void> future = actionManager.executeActions(testDownload);
        future.get(5, TimeUnit.SECONDS);

        assertTrue(action1.wasExecuted());
        assertTrue(action2.wasExecuted());
        assertEquals(testDownload, action1.getExecutedDownload());
        assertEquals(testDownload, action2.getExecutedDownload());

        // Verify listener was notified
        assertEquals(2, testListener.getActionStartCount());
        assertEquals(2, testListener.getActionCompleteCount());
        assertEquals(0, testListener.getActionErrorCount());
        assertEquals(1, testListener.getAllActionsCompleteCount());
    }

    @Test
    @DisplayName("Should handle action execution failures")
    void shouldHandleActionExecutionFailures() throws Exception {
        TestAfterCompletionAction successAction = new TestAfterCompletionAction(AfterCompletionAction.ActionType.PLAY_SOUND, true);
        TestAfterCompletionAction failAction = new TestAfterCompletionAction(AfterCompletionAction.ActionType.MOVE_FILE, false);

        actionManager.addAction(testDownload, successAction);
        actionManager.addAction(testDownload, failAction);

        CompletableFuture<Void> future = actionManager.executeActions(testDownload);
        future.get(5, TimeUnit.SECONDS);

        assertTrue(successAction.wasExecuted());
        assertTrue(failAction.wasExecuted());

        // Verify listener was notified of both success and failure
        assertEquals(2, testListener.getActionStartCount());
        assertEquals(1, testListener.getActionCompleteCount());
        assertEquals(1, testListener.getActionErrorCount());
        assertEquals(1, testListener.getAllActionsCompleteCount());

        // Check successful and failed actions
        assertEquals(1, testListener.getSuccessfulActions().size());
        assertEquals(1, testListener.getFailedActions().size());
        assertTrue(testListener.getSuccessfulActions().contains(successAction));
        assertTrue(testListener.getFailedActions().contains(failAction));
    }

    @Test
    @DisplayName("Should handle action execution exceptions")
    void shouldHandleActionExecutionExceptions() throws Exception {
        TestAfterCompletionAction successAction = new TestAfterCompletionAction(AfterCompletionAction.ActionType.PLAY_SOUND, true);
        TestAfterCompletionAction exceptionAction = new TestAfterCompletionAction(AfterCompletionAction.ActionType.MOVE_FILE, true, true);

        actionManager.addAction(testDownload, successAction);
        actionManager.addAction(testDownload, exceptionAction);

        CompletableFuture<Void> future = actionManager.executeActions(testDownload);
        future.get(5, TimeUnit.SECONDS);

        assertTrue(successAction.wasExecuted());
        assertTrue(exceptionAction.wasExecuted());

        // Verify listener was notified of exception
        assertEquals(2, testListener.getActionStartCount());
        assertEquals(1, testListener.getActionCompleteCount());
        assertEquals(1, testListener.getActionErrorCount());
        assertEquals(1, testListener.getAllActionsCompleteCount());
    }

    @Test
    @DisplayName("Should execute no actions when none are registered")
    void shouldExecuteNoActionsWhenNoneRegistered() throws Exception {
        CompletableFuture<Void> future = actionManager.executeActions(testDownload);
        future.get(5, TimeUnit.SECONDS);

        assertEquals(0, testListener.getActionStartCount());
        assertEquals(0, testListener.getActionCompleteCount());
        assertEquals(0, testListener.getActionErrorCount());
        assertEquals(0, testListener.getAllActionsCompleteCount());
    }

    @Test
    @DisplayName("Should handle concurrent action execution")
    void shouldHandleConcurrentActionExecution() throws Exception {
        // Six rendezvous actions must run simultaneously on the bounded
        // pool (bounded to at least 8 workers); the old count of 10 assumed
        // an unbounded cached pool
        int actionCount = 6;
        CountDownLatch startLatch = new CountDownLatch(actionCount);
        CountDownLatch finishLatch = new CountDownLatch(actionCount);

        // Add multiple actions that will execute concurrently
        for (int i = 0; i < actionCount; i++) {
            TestAfterCompletionAction action = new TestAfterCompletionAction(
                AfterCompletionAction.ActionType.PLAY_SOUND,
                true,
                startLatch,
                finishLatch
            );
            actionManager.addAction(testDownload, action);
        }

        CompletableFuture<Void> future = actionManager.executeActions(testDownload);
        future.get(10, TimeUnit.SECONDS);

        assertEquals(actionCount, testListener.getActionStartCount());
        assertEquals(actionCount, testListener.getActionCompleteCount());
        assertEquals(0, testListener.getActionErrorCount());
        assertEquals(1, testListener.getAllActionsCompleteCount());
    }

    @Test
    @DisplayName("Should handle listener management")
    void shouldHandleListenerManagement() throws Exception {
        TestAfterCompletionActionListener listener2 = new TestAfterCompletionActionListener();
        actionManager.addListener(listener2);

        TestAfterCompletionAction action = new TestAfterCompletionAction(AfterCompletionAction.ActionType.PLAY_SOUND, true);
        actionManager.addAction(testDownload, action);

        // Wait for execution: executeActions is asynchronous, so asserting
        // listener counts immediately would race the executor thread
        actionManager.executeActions(testDownload).get(5, TimeUnit.SECONDS);

        // Both listeners should be notified
        assertTrue(testListener.getActionStartCount() > 0);
        assertTrue(listener2.getActionStartCount() > 0);

        // Remove one listener
        actionManager.removeListener(listener2);
        listener2.reset();
        testListener.reset();

        // Completion actions are exactly-once per download. Use a distinct
        // completion to verify listener removal instead of attempting to
        // execute the first download's actions a second time.
        Download secondDownload = createTestDownload("listener-removal");
        TestAfterCompletionAction secondAction = new TestAfterCompletionAction(
                AfterCompletionAction.ActionType.PLAY_SOUND, true);
        actionManager.addAction(secondDownload, secondAction);
        actionManager.executeActions(secondDownload).get(5, TimeUnit.SECONDS);

        // Only the remaining listener should be notified
        assertTrue(testListener.getActionStartCount() > 0);
        assertEquals(0, listener2.getActionStartCount());
    }

    @Test
    @DisplayName("Should handle listener exceptions gracefully")
    void shouldHandleListenerExceptionsGracefully() throws Exception {
        TestAfterCompletionActionListener faultyListener = new TestAfterCompletionActionListener(true);
        actionManager.addListener(faultyListener);

        TestAfterCompletionAction action = new TestAfterCompletionAction(AfterCompletionAction.ActionType.PLAY_SOUND, true);
        actionManager.addAction(testDownload, action);

        // Should not throw exception even though listener throws
        assertDoesNotThrow(() -> {
            CompletableFuture<Void> future = actionManager.executeActions(testDownload);
            future.get(5, TimeUnit.SECONDS);
        });

        // Action should still execute successfully
        assertTrue(action.wasExecuted());
    }

    @Test
    @DisplayName("Should handle multiple downloads independently")
    void shouldHandleMultipleDownloadsIndependently() throws Exception {
        Download download1 = createTestDownload("download1");
        Download download2 = createTestDownload("download2");

        TestAfterCompletionAction action1 = new TestAfterCompletionAction(AfterCompletionAction.ActionType.PLAY_SOUND, true);
        TestAfterCompletionAction action2 = new TestAfterCompletionAction(AfterCompletionAction.ActionType.MOVE_FILE, true);

        actionManager.addAction(download1, action1);
        actionManager.addAction(download2, action2);

        // Execute actions for first download
        CompletableFuture<Void> future1 = actionManager.executeActions(download1);
        future1.get(5, TimeUnit.SECONDS);

        assertTrue(action1.wasExecuted());
        assertFalse(action2.wasExecuted());

        // Execute actions for second download
        CompletableFuture<Void> future2 = actionManager.executeActions(download2);
        future2.get(5, TimeUnit.SECONDS);

        assertTrue(action2.wasExecuted());
    }

    @Test
    @DisplayName("Should shutdown gracefully")
    void shouldShutdownGracefully() {
        TestAfterCompletionAction action = new TestAfterCompletionAction(AfterCompletionAction.ActionType.PLAY_SOUND, true);
        actionManager.addAction(testDownload, action);

        assertDoesNotThrow(() -> {
            actionManager.shutdown();
        });

        // After shutdown, executing actions should still work but may use different thread pool
        assertDoesNotThrow(() -> {
            actionManager.executeActions(testDownload);
        });
    }

    @Test
    @DisplayName("Should handle action cancellation")
    void shouldHandleActionCancellation() {
        TestAfterCompletionAction cancellableAction = new TestAfterCompletionAction(AfterCompletionAction.ActionType.SHUTDOWN_COMPUTER, true);
        actionManager.addAction(testDownload, cancellableAction);

        boolean cancelled = cancellableAction.cancel();
        assertTrue(cancelled);
        assertTrue(cancellableAction.wasCancelled());
    }

    // Helper methods and test classes

    private Download createTestDownload() {
        return createTestDownload("test-download-id");
    }

    private Download createTestDownload(String id) {
        Download download = new Download(URI.create("https://example.com/test-file.zip"));
        download.setDestination(tempDir);
        return download;
    }

    /**
     * Test implementation of AfterCompletionAction for testing purposes.
     */
    private static class TestAfterCompletionAction implements AfterCompletionAction {
        private final ActionType type;
        private final boolean shouldSucceed;
        private final boolean shouldThrowException;
        private boolean executed = false;
        private boolean cancelled = false;
        private Download executedDownload = null;
        private CountDownLatch startLatch;
        private CountDownLatch finishLatch;

        public TestAfterCompletionAction(ActionType type, boolean shouldSucceed) {
            this(type, shouldSucceed, false);
        }

        public TestAfterCompletionAction(ActionType type, boolean shouldSucceed, boolean shouldThrowException) {
            this.type = type;
            this.shouldSucceed = shouldSucceed;
            this.shouldThrowException = shouldThrowException;
        }

        public TestAfterCompletionAction(ActionType type, boolean shouldSucceed,
                                       CountDownLatch startLatch, CountDownLatch finishLatch) {
            this.type = type;
            this.shouldSucceed = shouldSucceed;
            this.shouldThrowException = false;
            this.startLatch = startLatch;
            this.finishLatch = finishLatch;
        }

        @Override
        public boolean execute(Download download) {
            if (shouldThrowException) {
                executed = true;
                executedDownload = download;
                throw new RuntimeException("Test exception during action execution");
            }

            if (startLatch != null) {
                startLatch.countDown();
                try {
                    finishLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            executed = true;
            executedDownload = download;
            return shouldSucceed;
        }

        @Override
        public ActionType getType() {
            return type;
        }

        @Override
        public String getDescription() {
            return "Test action: " + type;
        }

        @Override
        public Severity getSeverity() {
            return Severity.MEDIUM;
        }

        @Override
        public boolean cancel() {
            cancelled = true;
            return true;
        }

        public boolean wasExecuted() {
            return executed;
        }

        public boolean wasCancelled() {
            return cancelled;
        }

        public Download getExecutedDownload() {
            return executedDownload;
        }
    }

    /**
     * Test implementation of AfterCompletionActionListener for testing purposes.
     */
    private static class TestAfterCompletionActionListener implements AfterCompletionActionListener {
        private final AtomicInteger actionStartCount = new AtomicInteger(0);
        private final AtomicInteger actionCompleteCount = new AtomicInteger(0);
        private final AtomicInteger actionErrorCount = new AtomicInteger(0);
        private final AtomicInteger allActionsCompleteCount = new AtomicInteger(0);
        private final boolean shouldThrowException;
        private List<AfterCompletionAction> successfulActions;
        private List<AfterCompletionAction> failedActions;

        public TestAfterCompletionActionListener() {
            this(false);
        }

        public TestAfterCompletionActionListener(boolean shouldThrowException) {
            this.shouldThrowException = shouldThrowException;
        }

        @Override
        public void onActionStart(Download download, AfterCompletionAction action) {
            if (shouldThrowException) {
                throw new RuntimeException("Test listener exception");
            }
            actionStartCount.incrementAndGet();
        }

        @Override
        public void onActionComplete(Download download, AfterCompletionAction action) {
            if (shouldThrowException) {
                throw new RuntimeException("Test listener exception");
            }
            actionCompleteCount.incrementAndGet();
        }

        @Override
        public void onActionError(Download download, AfterCompletionAction action, String errorMessage, AfterCompletionAction.Severity severity) {
            if (shouldThrowException) {
                throw new RuntimeException("Test listener exception");
            }
            actionErrorCount.incrementAndGet();
        }

        @Override
        public void onAllActionsComplete(Download download, List<AfterCompletionAction> successfulActions,
                                       List<AfterCompletionAction> failedActions) {
            if (shouldThrowException) {
                throw new RuntimeException("Test listener exception");
            }
            allActionsCompleteCount.incrementAndGet();
            this.successfulActions = successfulActions;
            this.failedActions = failedActions;
        }

        public int getActionStartCount() { return actionStartCount.get(); }
        public int getActionCompleteCount() { return actionCompleteCount.get(); }
        public int getActionErrorCount() { return actionErrorCount.get(); }
        public int getAllActionsCompleteCount() { return allActionsCompleteCount.get(); }
        public List<AfterCompletionAction> getSuccessfulActions() { return successfulActions; }
        public List<AfterCompletionAction> getFailedActions() { return failedActions; }

        public void reset() {
            actionStartCount.set(0);
            actionCompleteCount.set(0);
            actionErrorCount.set(0);
            allActionsCompleteCount.set(0);
            successfulActions = null;
            failedActions = null;
        }
    }
}
