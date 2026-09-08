package org.manager.download.action;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.download.Download;

@Timeout(10)
class DesktopNotificationActionTest {
    @Test
    void selectedNotificationRunsOncePerDownloadAndRecordsTheResult() throws Exception {
        List<Download> notified = new CopyOnWriteArrayList<>();
        DesktopNotificationAction action = new DesktopNotificationAction(download -> {
            notified.add(download);
            return CompletableFuture.completedFuture(null);
        });
        AfterCompletionActionManager manager = new AfterCompletionActionManager();
        Download first = new Download(URI.create("https://example.com/first.zip"));
        Download second = new Download(URI.create("https://example.com/second.zip"));
        try {
            manager.setGlobalActions(List.of(action));
            manager.executeActions(first).get(5, TimeUnit.SECONDS);
            manager.executeActions(first).get(5, TimeUnit.SECONDS);
            manager.executeActions(second).get(5, TimeUnit.SECONDS);
            manager.executeGlobalActions(second).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(first, second), notified);
            for (Download download : List.of(first, second)) {
                var result = download.getCompletionActionResults().getFirst();
                assertEquals(AfterCompletionAction.ActionType.DESKTOP_NOTIFICATION, result.actionType());
                assertEquals(CompletionActionResult.Status.SUCCEEDED, result.status());
                assertEquals("Desktop notification sent", result.message());
            }
            assertFalse(action.isGlobal());
            assertFalse(action.contributesToFinalizingProgress());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void failedDeliveryIsALowSeverityActionFailure() throws Exception {
        DesktopNotificationAction action = new DesktopNotificationAction(download ->
                CompletableFuture.failedFuture(new IllegalStateException("No desktop session bus")));
        AfterCompletionActionManager manager = new AfterCompletionActionManager();
        Download download = new Download(URI.create("https://example.com/file.zip"));
        try {
            assertFalse(manager.executeAction(download, action).get(5, TimeUnit.SECONDS));
            var result = download.getCompletionActionResults().getFirst();
            assertEquals(CompletionActionResult.Status.FAILED, result.status());
            assertEquals(AfterCompletionAction.Severity.LOW, result.severity());
            assertEquals("Could not send desktop notification", result.message());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void cancellationReleasesPendingDeliveryWithoutDisablingFutureNotifications() throws Exception {
        CompletableFuture<Void> delivery = new CompletableFuture<>();
        DesktopNotificationAction action = new DesktopNotificationAction(download -> delivery);
        Download download = new Download(URI.create("https://example.com/file.zip"));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var execution = executor.submit(() -> action.execute(download));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!execution.isDone() && System.nanoTime() < deadline) {
                action.cancel();
                Thread.sleep(5);
            }
            assertFalse(execution.get(1, TimeUnit.SECONDS));
            assertTrue(delivery.isCancelled());
        }
        DesktopNotificationAction reusable = new DesktopNotificationAction(
                ignored -> CompletableFuture.completedFuture(null));
        reusable.cancel();
        assertTrue(reusable.execute(download));
    }
}
