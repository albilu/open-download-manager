package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.AntivirusCheckAction;
import org.manager.download.action.ExecuteCommandAction;
import org.manager.download.action.PlayNotificationAction;
import org.manager.download.action.ShutdownComputerAction;

/**
 * Plain unit tests for the completion-action radio-choice policy.
 */
class CompletionActionPolicyTest {

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

        assertInstanceOf(PlayNotificationAction.class,
                CompletionActionPolicy.forChoice("notify", settings));
        assertInstanceOf(CompletionActionPolicy.SuspendAction.class,
                CompletionActionPolicy.forChoice("suspend", settings));
        assertInstanceOf(ShutdownComputerAction.class,
                CompletionActionPolicy.forChoice("shutdown", settings));
        assertInstanceOf(AntivirusCheckAction.class,
                CompletionActionPolicy.forChoice("antivirus", settings));
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
    void antivirusDefaultsToClamav() {
        AntivirusCheckAction action = CompletionActionPolicy.buildAntivirusAction(new GlobalSettings());

        assertEquals(AntivirusCheckAction.AntivirusType.CLAMAV, action.getAntivirusType());
    }
}
