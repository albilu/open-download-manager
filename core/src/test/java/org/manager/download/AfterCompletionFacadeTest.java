package org.manager.download;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.AfterCompletionActionListener;

/**
 * Contract for the after-completion facade on DownloadManager: actions
 * registered through the manager execute exactly once per execute call,
 * listeners attached through the manager observe the action lifecycle, and
 * duplicate registration is a no-op. The UI routes through this facade —
 * there is exactly ONE completion-action mechanism.
 */
@DisplayName("DownloadManager after-completion facade")
class AfterCompletionFacadeTest {

    private static final class CountingAction implements AfterCompletionAction {
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public boolean execute(Download download) {
            executions.incrementAndGet();
            return true;
        }

        @Override
        public boolean cancel() {
            return true;
        }

        @Override
        public String getDescription() {
            return "counting";
        }

        @Override
        public Severity getSeverity() {
            return Severity.LOW;
        }

        @Override
        public ActionType getType() {
            return ActionType.EXECUTE_COMMAND;
        }
    }

    @Test
    @Timeout(30)
    void archivedOnlyCompletionDoesNotRunFileActions() throws Exception {
        DownloadManager manager = DownloadManagerFactory.getInstance();
        Download download = new Download(URI.create("https://example.test/archived"));
        download.setStatus(Download.Status.COMPLETED);
        download.setArchiveOnlyCompletion(true);
        CountingAction action = new CountingAction();
        manager.addAfterCompletionAction(download, action);
        try {
            manager.executeAfterCompletionActions(download).get(10, TimeUnit.SECONDS);
            assertEquals(0, action.executions.get());
            download.setArchiveOnlyCompletion(false);
            manager.executeAfterCompletionActions(download).get(10, TimeUnit.SECONDS);
            assertEquals(1, action.executions.get());
        } finally { manager.removeAfterCompletionAction(download, action); }
    }

    @Test
    @Timeout(30)
    @DisplayName("Registered action executes once; listener observes; duplicate add is a no-op")
    void facadeExecutesAndNotifies() throws Exception {
        DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        Download download = new Download(new URI("http://example.test/facade.bin"));

        CountingAction action = new CountingAction();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch allComplete = new CountDownLatch(1);

        AfterCompletionActionListener listener = new AfterCompletionActionListener() {
            @Override
            public void onActionStart(Download d, AfterCompletionAction a) {
                started.countDown();
            }

            @Override
            public void onActionComplete(Download d, AfterCompletionAction a) {
            }

            @Override
            public void onActionError(Download d, AfterCompletionAction a, String errorMessage,
                    AfterCompletionAction.Severity severity) {
            }

            @Override
            public void onAllActionsComplete(Download d,
                    List<AfterCompletionAction> successful,
                    List<AfterCompletionAction> failed) {
                allComplete.countDown();
            }
        };
        manager.addAfterCompletionActionListener(listener);
        try {
            manager.addAfterCompletionAction(download, action);
            // Duplicate registration must not double-execute
            manager.addAfterCompletionAction(download, action);
            assertEquals(1, manager.getAfterCompletionActions(download).size(),
                    "the same action instance registers once");

            manager.executeAfterCompletionActions(download).get(15, TimeUnit.SECONDS);

            assertTrue(started.await(5, TimeUnit.SECONDS), "listener must observe action start");
            assertTrue(allComplete.await(5, TimeUnit.SECONDS), "listener must observe completion");
            assertEquals(1, action.executions.get(), "action executes exactly once");
        } finally {
            manager.removeAfterCompletionActionListener(listener);
            manager.removeAfterCompletionAction(download, action);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("Explicit facade action can run after the automatic pass")
    void facadeExecutesExplicitActionAfterAutomaticPass() throws Exception {
        DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        Download download = new Download(new URI("http://example.test/manual-action.bin"));
        CountingAction action = new CountingAction();
        manager.addAfterCompletionAction(download, action);

        manager.executeAfterCompletionActions(download).get(15, TimeUnit.SECONDS);
        assertTrue(manager.executeAfterCompletionAction(download, action)
                .get(15, TimeUnit.SECONDS));

        assertEquals(2, action.executions.get());
        assertEquals(2, download.getCompletionActionResults().size());
    }
}
