package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.CompletionActionResult;

class GlobalCompletionActionGateTest {

    private Download download(String name, Download.Status status) {
        Download download = new Download(URI.create("https://example.test/" + name));
        download.setStatus(status);
        return download;
    }

    @Test
    void onlyTerminalItemsWithSettledPostProcessingOpenTheGlobalGate() {
        Download completed = download("complete", Download.Status.COMPLETED);
        Download failed = download("failed", Download.Status.ERROR);
        Download canceled = download("canceled", Download.Status.CANCELED);
        assertTrue(DownloadManagerImpl.readyForGlobalCompletionActions(
                List.of(completed, failed, canceled)));

        for (Download.Status pending : List.of(
                Download.Status.CREATED, Download.Status.STARTING,
                Download.Status.CONNECTING, Download.Status.DOWNLOADING,
                Download.Status.SEEDING, Download.Status.QUEUED,
                Download.Status.PAUSED)) {
            assertFalse(DownloadManagerImpl.readyForGlobalCompletionActions(
                    List.of(completed, download("pending-" + pending, pending))),
                    pending + " must hold global power actions");
        }

        completed.setCompletionActionResults(List.of(new CompletionActionResult(
                "running", AfterCompletionAction.ActionType.ANTIVIRUS_CHECK,
                "Antivirus", CompletionActionResult.Status.RUNNING, "Running…",
                AfterCompletionAction.Severity.HIGH, Instant.now(), null)));
        assertFalse(DownloadManagerImpl.readyForGlobalCompletionActions(List.of(completed)));
        completed.finishCompletionAction("running",
                CompletionActionResult.Status.SUCCEEDED, "Clean");
        assertTrue(DownloadManagerImpl.readyForGlobalCompletionActions(List.of(completed)));
    }

    @Test
    void anEmptyManagerDoesNotTriggerPowerActions() {
        assertFalse(DownloadManagerImpl.readyForGlobalCompletionActions(List.of()));
    }
}
