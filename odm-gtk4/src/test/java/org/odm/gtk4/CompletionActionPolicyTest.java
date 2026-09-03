package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.AntivirusCheckAction;
import org.manager.download.action.ExecuteCommandAction;
import org.manager.download.action.PlayNotificationAction;
import org.manager.download.action.ShutdownComputerAction;
import org.manager.download.action.SubtitleDownloadAction;
import org.manager.tools.ToolManagerFactory;

/**
 * Plain unit tests for the completion-action radio-choice policy.
 */
class CompletionActionPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void noneAndUnknownChoicesMapToNoAction() {
        GlobalSettings settings = new GlobalSettings();
        assertNull(CompletionActionPolicy.forChoice("none", settings));
        assertNull(CompletionActionPolicy.forChoice("bogus", settings));
        assertNull(CompletionActionPolicy.forChoice(null, settings));
    }

    @Test
    void knownChoicesMapToTheirActions() {
        GlobalSettings settings = new GlobalSettings();
        settings.setProperty("ytdlp.subtitleLanguages", "fr,it");
        settings.setProperty("antivirus.scanner", "clamav");

        assertInstanceOf(PlayNotificationAction.class,
                CompletionActionPolicy.forChoice("notify", settings));
        assertInstanceOf(CompletionActionPolicy.SuspendAction.class,
                CompletionActionPolicy.forChoice("suspend", settings));
        assertInstanceOf(ShutdownComputerAction.class,
                CompletionActionPolicy.forChoice("shutdown", settings));
        assertInstanceOf(AntivirusCheckAction.class,
                CompletionActionPolicy.forChoice("antivirus", settings));
        SubtitleDownloadAction subtitles = assertInstanceOf(SubtitleDownloadAction.class,
                CompletionActionPolicy.forChoice("subtitles", settings));
        assertEquals(java.util.List.of("fr", "it"), subtitles.getLanguages());
    }

    @Test
    void multipleChoicesAreBuiltInActionTypePriorityOrder() {
        GlobalSettings settings = new GlobalSettings();
        settings.setProperty("ui.completionCommand", "echo {file_path}");
        settings.setProperty("antivirus.scanner", "clamav");

        var actions = CompletionActionPolicy.forChoices(
                java.util.List.of("shutdown", "custom", "notify", "antivirus"), settings);

        assertIterableEquals(java.util.List.of(
                AfterCompletionAction.ActionType.PLAY_SOUND,
                AfterCompletionAction.ActionType.ANTIVIRUS_CHECK,
                AfterCompletionAction.ActionType.EXECUTE_COMMAND,
                AfterCompletionAction.ActionType.SHUTDOWN_COMPUTER),
                actions.stream().map(AfterCompletionAction::getType).toList());
    }

    @Test
    void customRebuildsFromTheStoredCommandOrClears() {
        GlobalSettings settings = new GlobalSettings();
        assertNull(CompletionActionPolicy.customAction(settings));

        settings.setProperty("ui.completionCommand", "mv {file_path} /tmp");
        AfterCompletionAction action = CompletionActionPolicy.customAction(settings);
        assertInstanceOf(ExecuteCommandAction.class, action);

        // and through the choice mapping
        assertInstanceOf(ExecuteCommandAction.class,
                CompletionActionPolicy.forChoice("custom", settings));
    }

    @Test
    void antivirusCustomScannerUsesTheConfiguredCommandAndTimeout() {
        GlobalSettings settings = new GlobalSettings();
        settings.setProperty("antivirus.scanner", "custom");
        settings.setProperty("antivirus.command", "virusscan --quiet {file}");
        settings.setProperty("antivirus.timeout", "120");

        AntivirusCheckAction action = CompletionActionPolicy.buildAntivirusAction(settings);

        assertEquals(AntivirusCheckAction.AntivirusType.CUSTOM, action.getAntivirusType());
        assertEquals("virusscan --quiet {file}", action.getCustomCommand());
        assertEquals(120, action.getTimeoutSeconds());
    }

    @Test
    void automaticAntivirusUsesTheFirstValidatedScanner() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        ToolManagerFactory factory = new ToolManagerFactory(settings, tempDir);
        try {
            Path valid = tempDir.resolve("clamscan");
            Files.writeString(valid, "#!/bin/sh\necho 'ClamAV 1.4.2'\nexit 0\n");
            Files.setPosixFilePermissions(valid,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            factory.getAntivirusManager("clamav").setToolPath(valid.toString());

            AntivirusCheckAction action = CompletionActionPolicy
                    .automaticAntivirusAction(factory, 42);

            assertEquals(AntivirusCheckAction.AntivirusType.CLAMAV,
                    action.getAntivirusType());
            assertEquals(42, action.getTimeoutSeconds());
        } finally {
            factory.cleanup();
        }
    }
}
