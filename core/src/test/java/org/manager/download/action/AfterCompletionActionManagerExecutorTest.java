package org.manager.download.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.download.Download;

/**
 * The action manager's worker pool must be bounded, daemon, named, awaited
 * on shutdown, and closed for new work afterwards: submissions after
 * shutdown used to fall back silently to the common ForkJoin pool.
 */
@DisplayName("AfterCompletionActionManager executor lifecycle")
class AfterCompletionActionManagerExecutorTest {

    private static class RecordingAction implements AfterCompletionAction {

        final AtomicReference<Thread> ranOn = new AtomicReference<>();
        volatile boolean executed;

        @Override
        public boolean execute(Download download) {
            executed = true;
            ranOn.set(Thread.currentThread());
            return true;
        }

        @Override
        public ActionType getType() {
            return ActionType.EXECUTE_COMMAND;
        }

        @Override
        public String getDescription() {
            return "recording";
        }

        @Override
        public Severity getSeverity() {
            return Severity.LOW;
        }

        @Override
        public boolean cancel() {
            return true;
        }
    }

    private static Download newDownload() {
        Download download = new Download();
        download.setName("actions.bin");
        return download;
    }

    @Test
    @DisplayName("pool threads are named daemons")
    void poolThreadsAreNamedDaemons() throws Exception {
        List<String> threadNames = new CopyOnWriteArrayList<>();
        List<Boolean> daemons = new CopyOnWriteArrayList<>();

        AfterCompletionActionManager manager = new AfterCompletionActionManager(2,
                r -> {
                    Thread t = new Thread(r, "odm-after-completion-test");
                    t.setDaemon(true);
                    return t;
                });
        try {
            RecordingAction probe = new RecordingAction() {
                @Override
                public boolean execute(Download download) {                    threadNames.add(Thread.currentThread().getName());
                    daemons.add(Thread.currentThread().isDaemon());
                    return true;
                }
            };
            Download download = newDownload();
            manager.addAction(download, probe);
            manager.executeActions(download).get(10, TimeUnit.SECONDS);

            assertTrue(threadNames.stream().allMatch(name -> name.startsWith("odm-after-completion")),
                    "actions must run on the manager's named pool threads: " + threadNames);
            assertTrue(daemons.stream().allMatch(Boolean::booleanValue),
                    "pool threads must be daemons");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("submissions after shutdown are rejected, not silently executed")
    void postShutdownSubmissionRejected() throws Exception {
        AfterCompletionActionManager manager = new AfterCompletionActionManager();
        Download download = newDownload();
        RecordingAction action = new RecordingAction();
        manager.addAction(download, action);

        manager.shutdown();
        assertTrue(manager.isShutdown(), "shutdown must be observable");

        java.util.concurrent.CompletableFuture<Void> result = manager.executeActions(download);

        ExecutionException rejected = assertThrows(ExecutionException.class,
                () -> result.get(10, TimeUnit.SECONDS));
        assertTrue(rejected.getCause() instanceof java.util.concurrent.RejectedExecutionException,
                "post-shutdown submission must surface rejection, got: " + rejected.getCause());
        assertFalse(action.executed, "the action must NOT run after shutdown");
    }

    @Test
    @DisplayName("a healthy execute/shutdown cycle completes and terminates the pool")
    void healthyCycleWorks() throws Exception {
        AfterCompletionActionManager manager = new AfterCompletionActionManager();
        Download download = newDownload();
        RecordingAction action = new RecordingAction();
        manager.addAction(download, action);

        manager.executeActions(download).get(10, TimeUnit.SECONDS);
        manager.shutdown();

        assertTrue(action.executed);
        assertTrue(manager.awaitTermination(5, TimeUnit.SECONDS),
                "shutdown must await bounded pool termination");
    }
}
