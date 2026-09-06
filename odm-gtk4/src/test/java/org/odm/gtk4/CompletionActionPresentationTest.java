package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.manager.download.Download;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.CompletionActionResult;

class CompletionActionPresentationTest {

    @Test
    void recordsWithoutActionsHaveNoStatusPresentation() {
        Download download = download();

        assertEquals(CompletionActionPresentation.Outcome.NONE,
                CompletionActionPresentation.summarize(download).outcome());
        assertNull(CompletionActionPresentation.outcomeIconName(
                CompletionActionPresentation.Outcome.NONE));
        assertNull(CompletionActionPresentation.icon(download));
        assertNull(CompletionActionPresentation.tooltip(download));
    }

    @Test
    void runningBatchDoesNotPublishAPrematureFinalOutcome() {
        Download download = download();
        download.setCompletionActionResults(List.of(
                result("done", CompletionActionResult.Status.SUCCEEDED),
                result("running", CompletionActionResult.Status.RUNNING)));

        CompletionActionPresentation.Summary summary =
                CompletionActionPresentation.summarize(download);
        assertEquals(CompletionActionPresentation.Outcome.RUNNING, summary.outcome());
        assertEquals(1, summary.succeeded());
        assertEquals(1, summary.running());
        assertNull(CompletionActionPresentation.outcomeIconName(summary.outcome()));
        assertNull(CompletionActionPresentation.icon(download));
        assertEquals("After-completion actions running (1/2 finished)",
                CompletionActionPresentation.tooltip(download));
    }

    @Test
    void allSuccessfulActionsUseTheSuccessIcon() {
        Download download = download();
        download.setCompletionActionResults(List.of(
                result("one", CompletionActionResult.Status.SUCCEEDED),
                result("two", CompletionActionResult.Status.SUCCEEDED)));

        CompletionActionPresentation.Summary summary =
                CompletionActionPresentation.summarize(download);
        assertEquals(CompletionActionPresentation.Outcome.SUCCEEDED, summary.outcome());
        assertEquals("checkbox-checked-symbolic",
                CompletionActionPresentation.outcomeIconName(summary.outcome()));
        assertEquals("All after-completion actions succeeded (2/2)",
                CompletionActionPresentation.tooltip(download));
    }

    @Test
    void mixedActionsUseTheWarningIcon() {
        Download download = download();
        download.setCompletionActionResults(List.of(
                result("one", CompletionActionResult.Status.SUCCEEDED),
                result("two", CompletionActionResult.Status.FAILED)));

        CompletionActionPresentation.Summary summary =
                CompletionActionPresentation.summarize(download);
        assertEquals(CompletionActionPresentation.Outcome.PARTIAL, summary.outcome());
        assertEquals("dialog-warning-symbolic",
                CompletionActionPresentation.outcomeIconName(summary.outcome()));
        assertEquals("After-completion actions partially succeeded (1/2)",
                CompletionActionPresentation.tooltip(download));
    }

    @Test
    void failuresAndInterruptionsUseTheFailureIcon() {
        Download download = download();
        download.setCompletionActionResults(List.of(
                result("one", CompletionActionResult.Status.FAILED),
                result("two", CompletionActionResult.Status.INTERRUPTED)));

        CompletionActionPresentation.Summary summary =
                CompletionActionPresentation.summarize(download);
        assertEquals(CompletionActionPresentation.Outcome.FAILED, summary.outcome());
        assertEquals(2, summary.failed());
        assertEquals("dialog-error-symbolic",
                CompletionActionPresentation.outcomeIconName(summary.outcome()));
        assertEquals("All after-completion actions failed (2/2)",
                CompletionActionPresentation.tooltip(download));
    }

    private static Download download() {
        Download download = new Download(URI.create("https://example.com/file.bin"));
        download.setType(Download.Type.ARIA2);
        return download;
    }

    private static CompletionActionResult result(String id,
            CompletionActionResult.Status status) {
        Instant started = Instant.parse("2026-09-05T12:00:00Z");
        return new CompletionActionResult(id,
                AfterCompletionAction.ActionType.EXECUTE_COMMAND,
                "Command", status, status.name(), "",
                AfterCompletionAction.Severity.MEDIUM,
                started, status == CompletionActionResult.Status.RUNNING
                        ? null : started.plusSeconds(1));
    }
}
