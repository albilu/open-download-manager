package org.manager.download.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.download.Download;
import org.manager.download.action.AfterCompletionAction.ActionType;
import org.manager.download.action.AfterCompletionAction.Severity;

/**
 * Safety-first coverage for ShutdownComputerAction: only paths that never
 * invoke a real shutdown command are exercised (cancellation before
 * execution, unsupported platforms, accessors). Deliberately NO test runs
 * the OS shutdown command.
 */
@DisplayName("ShutdownComputerAction cancellation and platform guards")
class ShutdownComputerActionCancelTest {

    private Download anyDownload() {
        Download download = new Download(URI.create("https://example.test/done.zip"));
        download.setName("done.zip");
        return download;
    }

    @Test
    @DisplayName("cancelling during the pre-shutdown delay aborts without running the OS command")
    void cancelDuringDelayAbortsBeforeExecution() throws Exception {
        ShutdownComputerAction action = new ShutdownComputerAction(60);
        java.util.concurrent.atomic.AtomicBoolean cancelSucceeded = new java.util.concurrent.atomic.AtomicBoolean();

        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cancelSucceeded.set(action.cancel());
        });
        canceller.start();

        long start = System.currentTimeMillis();
        boolean result = action.execute(anyDownload());
        long elapsed = System.currentTimeMillis() - start;
        canceller.join(5000);

        assertTrue(cancelSucceeded.get(), "cancel must report success");
        assertFalse(result, "a cancelled shutdown must report failure");
        assertTrue(elapsed < 5000, "cancel must abort the delay promptly, took " + elapsed + "ms");
        assertFalse(action.isShutdownInitiated(), "the OS command must never have been issued");
    }

    @Test
    @DisplayName("cancel before any execution is a no-op success")
    void cancelWithoutExecutionIsNoOp() {
        ShutdownComputerAction action = new ShutdownComputerAction(0);
        assertTrue(action.cancel());
        assertFalse(action.isShutdownInitiated());
    }

    @Test
    @DisplayName("an unsupported operating system is refused before any command runs")
    void unsupportedOsIsRefused() {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Plan9");
            ShutdownComputerAction action = new ShutdownComputerAction(0);
            // no delay => execute proceeds to platform dispatch, which must refuse
            assertFalse(action.execute(anyDownload()));
            assertFalse(action.isShutdownInitiated());
        } finally {
            System.setProperty("os.name", original);
        }
    }

    @Test
    @DisplayName("an elapsed delay issues the OS command, which fails safely without the binary")
    @org.junit.jupiter.api.Timeout(30)
    void elapsedDelayFailsSafelyWithoutShutdownBinary() {
        // the container has no 'shutdown' binary, so the issued command fails
        // with IOException and the action must report failure cleanly
        ShutdownComputerAction action = new ShutdownComputerAction(1);
        long start = System.currentTimeMillis();
        boolean result = action.execute(anyDownload());
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result, "without a shutdown binary the action must fail");
        assertTrue(elapsed >= 900, "the promised delay must elapse first, took " + elapsed + "ms");
        assertFalse(action.isShutdownInitiated(),
                "a failed command must not count as an initiated shutdown");
    }

    @Test
    @DisplayName("configuration accessors expose the effective delay")
    void accessorsExposeConfiguration() {
        ShutdownComputerAction immediate = new ShutdownComputerAction(0);
        assertEquals(0, immediate.getDelayInSeconds());
        assertEquals("Shutdown computer immediately", immediate.getDescription());
        assertEquals(ActionType.SHUTDOWN_COMPUTER, immediate.getType());
        assertEquals(Severity.CRITICAL, immediate.getSeverity());
        assertFalse(immediate.isShutdownInitiated());

        ShutdownComputerAction delayed = new ShutdownComputerAction(300);
        assertEquals(300, delayed.getDelayInSeconds());
        assertEquals("Shutdown computer after 300 seconds", delayed.getDescription());

        ShutdownComputerAction negative = new ShutdownComputerAction(-50);
        assertEquals(0, negative.getDelayInSeconds(), "negative delays clamp to immediate");
    }
}
