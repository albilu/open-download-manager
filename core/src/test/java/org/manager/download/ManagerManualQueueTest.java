package org.manager.download;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.ApplicationContext;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/** Regression coverage for explicit and background automatic-start queue holds. */
class ManagerManualQueueTest {

    private static final long TIMEOUT_SECONDS = 8;

    private static final class CountingHandler extends AbstractDownloadHandler {

        private final AtomicInteger attempts = new AtomicInteger();

        CountingHandler() {
            super(null, null, null);
        }

        @Override
        public Download.Type getSupportedType() {
            return Download.Type.ARIA2;
        }

        @Override
        protected void doInitialize() {
        }

        @Override
        protected void doShutdown() {
        }

        @Override
        public CompletableFuture<String> startDownload(Download download) {
            return CompletableFuture.completedFuture("manual-gid-" + attempts.incrementAndGet());
        }

        @Override
        public CompletableFuture<Void> pauseDownload(Download download) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> resumeDownload(Download download) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> changeSettings(Download download) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
            notifyDownloadCanceled(download);
            return CompletableFuture.completedFuture(null);
        }

        void fireComplete(Download download) {
            notifyDownloadComplete(download);
        }
    }

    private DownloadManagerImpl manager;
    private CountingHandler handler;

    private void setUp() {
        manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        handler = new CountingHandler();
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.ARIA2, handler);
        ApplicationContext.getGlobalSettings().setMaxConcurrentDownloads(1);
        ApplicationContext.getGlobalSettings().setGlobalProxyEnabled(false);
        ApplicationContext.getGlobalSettings().setGlobalProxyAddress(null);
        ApplicationContext.getGlobalSettings().setProperty("ui.startAutomatically", "true");
    }

    @AfterEach
    void cleanUp() {
        if (manager != null) {
            manager.getAllDownloads().forEach(download -> manager.cancelDownload(download, false).join());
        }
    }

    private Download newDownload(String name) throws Exception {
        Download download = manager.createDownload(
                new URI("http://example.test/" + name + ".bin"), null);
        download.setName(name);
        return download;
    }

    private static boolean awaitTrue(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("A manual-start queue item is published but never auto-admitted")
    void manualQueuePublishesAndWaitsForExplicitStart() throws Exception {
        setUp();
        Download running = newDownload("already-running");
        Download held = newDownload("clipboard-held");

        manager.startDownload(running).join();
        assertTrue(awaitTrue(() -> running.getStatus() == Download.Status.DOWNLOADING));

        CountDownLatch visibleQueueEvent = new CountDownLatch(1);
        manager.addDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                if (download.getId().equals(held.getId())) {
                    visibleQueueEvent.countDown();
                }
            }

            @Override public void onDownloadProgress(Download d, float p, long done, long total, float speed) { }
            @Override public void onDownloadPause(Download d) { }
            @Override public void onDownloadResume(Download d) { }
            @Override public void onDownloadComplete(Download d) { }
            @Override public void onDownloadError(Download d, String error) { }
            @Override public void onDownloadCanceled(Download d) { }
        });

        manager.queueDownloadForManualStart(held).join();

        assertTrue(visibleQueueEvent.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the queued event must reach listeners that refresh on start events");
        assertEquals(Download.Status.QUEUED, held.getStatus());
        assertTrue(held.isManualStartRequired());
        assertEquals(1, handler.attempts.get());

        handler.fireComplete(running);
        assertTrue(awaitTrue(() -> running.getStatus() == Download.Status.COMPLETED
                && manager.getRunningDownloadCount() == 0));
        Thread.sleep(250);
        assertEquals(Download.Status.QUEUED, held.getStatus(),
                "freeing a slot must not auto-start a silently detected URL");
        assertEquals(1, handler.attempts.get());

        manager.startDownload(held).join();
        assertTrue(awaitTrue(() -> held.getStatus() == Download.Status.DOWNLOADING));
        assertFalse(held.isManualStartRequired());
        assertEquals(2, handler.attempts.get());
    }

    @Test
    @DisplayName("The global automatic-start switch holds background-discovered items")
    void globalAutomaticStartPolicyHoldsBackgroundItems() throws Exception {
        setUp();
        ApplicationContext.getGlobalSettings().setProperty("ui.startAutomatically", "false");
        Download held = newDownload("global-policy-held");

        manager.queueDownloadFromBackgroundSource(held).join();

        assertEquals(Download.Status.QUEUED, held.getStatus());
        assertTrue(held.isManualStartRequired());
        assertEquals(0, handler.attempts.get(),
                "background ingestion must not start network activity while the policy is off");

        manager.startDownload(held).join();
        assertTrue(awaitTrue(() -> held.getStatus() == Download.Status.DOWNLOADING));
        assertFalse(held.isManualStartRequired());
        assertEquals(1, handler.attempts.get());
    }

    @Test
    @DisplayName("Automatic-start remains enabled by default for background-discovered items")
    void globalAutomaticStartPolicyDefaultsToEnabled() throws Exception {
        setUp();
        Download automatic = newDownload("global-policy-automatic");

        manager.queueDownloadFromBackgroundSource(automatic).join();

        assertTrue(awaitTrue(() -> automatic.getStatus() == Download.Status.DOWNLOADING));
        assertFalse(automatic.isManualStartRequired());
        assertEquals(1, handler.attempts.get());
    }

    @Test
    @DisplayName("Explicit user queue actions ignore the background automatic-start switch")
    void explicitQueueStartsWhenBackgroundAutomaticStartIsDisabled() throws Exception {
        setUp();
        ApplicationContext.getGlobalSettings().setProperty("ui.startAutomatically", "false");
        Download explicit = newDownload("explicit-user-action");

        manager.queueDownload(explicit).join();

        assertTrue(awaitTrue(() -> explicit.getStatus() == Download.Status.DOWNLOADING));
        assertFalse(explicit.isManualStartRequired());
        assertEquals(1, handler.attempts.get());
    }
}
