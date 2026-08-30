package org.manager.download.action;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.download.Download;
import org.aria2.Aria2Settings;

/**
 * Concurrency contracts for action registration: the manager's action map
 * is written from UI threads while completions execute actions from worker
 * threads. A plain HashMap loses entries when concurrent inserts race a
 * resize — registered after-completion actions would silently vanish.
 */
@DisplayName("AfterCompletionActionManager survives concurrent registration")
class AfterCompletionActionManagerConcurrencyTest {

    private static class NoOpAction implements AfterCompletionAction {

        @Override
        public boolean execute(Download download) {
            return true;
        }

        @Override
        public boolean cancel() {
            return true;
        }

        @Override
        public ActionType getType() {
            return ActionType.EXECUTE_COMMAND;
        }

        @Override
        public String getDescription() {
            return "no-op";
        }

        @Override
        public Severity getSeverity() {
            return Severity.LOW;
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("Concurrent addAction for distinct downloads keeps every entry")
    void concurrentAddKeepsEveryEntry() throws Exception {
        AfterCompletionActionManager manager = new AfterCompletionActionManager();
        try {
            int threads = 8;
            int perThread = 500;
            List<Download> registered = java.util.Collections.synchronizedList(new ArrayList<>());

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Void>> workers = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int threadIndex = t;
                    workers.add(pool.submit(() -> {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            Download download = new Download(
                                    new URI("http://example.test/action-" + threadIndex + "-" + i + ".bin"));
                            manager.addAction(download, new NoOpAction());
                            registered.add(download);
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<Void> worker : workers) {
                    worker.get(20, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertEquals(threads * perThread, registered.size());
            int present = 0;
            synchronized (registered) {
                for (Download download : registered) {
                    present += manager.getActions(download).isEmpty() ? 0 : 1;
                }
            }
            assertEquals(threads * perThread, present,
                    "every registered action must survive concurrent registration (lost map entries)");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("Concurrent setOption/toMap on settings loses no option")
    void downloadOptionsSurviveConcurrentAccess() throws Exception {
        Aria2Settings settings = new Aria2Settings();
        int threads = 6;
        int perThread = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                workers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        settings.setOption("opt-" + seed + "-" + i, "v");
                        settings.toMap();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> worker : workers) {
                worker.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // No setOption call may be lost to a concurrent toMap iteration
        assertTrue(settings.getAdditionalOptions().size() >= threads * perThread,
                "every concurrently set option must be present in the options map");
    }

    @Test
    @Timeout(30)
    @DisplayName("A shared global action instance never executes concurrently")
    void globalActionInstanceIsSerializedAcrossDownloads() throws Exception {
        AfterCompletionActionManager manager = new AfterCompletionActionManager();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AfterCompletionAction shared = new NoOpAction() {
            @Override
            public boolean execute(Download download) {
                int invocation = invocations.incrementAndGet();
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                try {
                    if (invocation == 1) {
                        firstEntered.countDown();
                        releaseFirst.await(5, TimeUnit.SECONDS);
                    }
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        manager.setGlobalAction(shared);
        try {
            Download first = new Download(new URI("https://example.test/first"));
            Download second = new Download(new URI("https://example.test/second"));
            var firstFuture = manager.executeActions(first);
            var secondFuture = manager.executeActions(second);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Thread.sleep(150);
            releaseFirst.countDown();

            CompletableFuture.allOf(firstFuture, secondFuture).get(10, TimeUnit.SECONDS);
            assertEquals(2, invocations.get());
            assertEquals(1, maxActive.get(),
                    "mutable global action state must be isolated between downloads");
        } finally {
            releaseFirst.countDown();
            manager.shutdown();
        }
    }
}
