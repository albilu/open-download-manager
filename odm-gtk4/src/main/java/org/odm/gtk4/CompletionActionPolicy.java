package org.odm.gtk4;

import java.util.logging.Logger;

import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.AntivirusCheckAction;
import org.manager.download.action.ExecuteCommandAction;
import org.manager.download.action.PlayNotificationAction;
import org.manager.download.action.ShutdownComputerAction;
import org.manager.download.action.SubtitleDownloadAction;
import org.subliminal.SubliminalSettings;

/**
 * Maps the completion-action radio choice to the concrete
 * {@link AfterCompletionAction}, including the persisted custom command
 * and antivirus scanner configuration. Plain policy, no GTK; the
 * interactive custom-command prompt stays in the window.
 */
final class CompletionActionPolicy {

    private static final Logger LOGGER = Logger.getLogger(CompletionActionPolicy.class.getName());

    private CompletionActionPolicy() {
    }

    /**
     * The action for a persisted/selected choice key. {@code custom}
     * rebuilds from {@code ui.completionCommand} (null when blank); the
     * interactive prompt path is handled by the window before calling
     * this.
     */
    static AfterCompletionAction forChoice(String choice, GlobalSettings settings) {
        return switch (choice == null ? "none" : choice) {
            case "notify" -> new PlayNotificationAction(
                    PlayNotificationAction.NotificationSound.SUCCESS);
            case "antivirus" -> buildAntivirusAction(settings);
            case "subtitles" -> buildSubtitleAction(settings);
            case "suspend" -> new SuspendAction();
            case "shutdown" -> new ShutdownComputerAction(30);
            case "custom" -> customAction(settings);
            default -> null;
        };
    }

    /** Custom completion action from the stored command, or null when blank. */
    static AfterCompletionAction customAction(GlobalSettings settings) {
        String saved = settings.getProperty("ui.completionCommand", "");
        return saved.isBlank() ? null : new ExecuteCommandAction(saved);
    }

    /**
     * Builds the antivirus completion action from persisted settings:
     * {@code antivirus.scanner} (clamav|chkrootkit|rkhunter|custom, default
     * clamav), {@code antivirus.command} for the custom scanner, and
     * {@code antivirus.timeout} seconds (default 600, 0 = no timeout).
     */
    static AntivirusCheckAction buildAntivirusAction(GlobalSettings settings) {
        String scanner = settings.getProperty("antivirus.scanner", "clamav");
        int timeout = settings.getIntProperty("antivirus.timeout", 600);
        AntivirusCheckAction.AntivirusType type = switch (scanner.toLowerCase()) {
            case "chkrootkit" -> AntivirusCheckAction.AntivirusType.CHKROOTKIT;
            case "rkhunter" -> AntivirusCheckAction.AntivirusType.RKHUNTER;
            case "custom" -> AntivirusCheckAction.AntivirusType.CUSTOM;
            default -> AntivirusCheckAction.AntivirusType.CLAMAV;
        };
        if (type == AntivirusCheckAction.AntivirusType.CUSTOM) {
            return new AntivirusCheckAction(
                    settings.getProperty("antivirus.command", "clamscan --no-summary {file}"), timeout);
        }
        return new AntivirusCheckAction(type, timeout);
    }

    /** Subtitle action using the shared yt-dlp language preference. */
    static SubtitleDownloadAction buildSubtitleAction(GlobalSettings settings) {
        SubliminalSettings subtitleSettings = new SubliminalSettings();
        try {
            subtitleSettings.setLanguages(SubliminalSettings.parseLanguages(
                    settings.getProperty("ytdlp.subtitleLanguages", "en")));
        } catch (IllegalArgumentException invalidLanguages) {
            LOGGER.warning(invalidLanguages.getMessage() + "; using English");
            subtitleSettings.setLanguages(java.util.List.of("en"));
        }
        int timeoutSeconds = Math.max(1,
                settings.getIntProperty("subtitles.timeoutSeconds", 300));
        subtitleSettings.setTimeout(java.time.Duration.ofSeconds(timeoutSeconds));
        return new SubtitleDownloadAction(subtitleSettings);
    }

    /** Suspends the machine on download completion (systemctl suspend). */
    static final class SuspendAction implements AfterCompletionAction {
        private volatile boolean canceled;

        @Override
        public boolean execute(Download download) {
            if (canceled) {
                return false;
            }
            try {
                new ProcessBuilder("systemctl", "suspend").inheritIO().start();
                return true;
            } catch (Exception e) {
                LOGGER.warning("Failed to suspend: " + e.getMessage());
                return false;
            }
        }

        @Override
        public ActionType getType() {
            return ActionType.SLEEP_COMPUTER;
        }

        @Override
        public String getDescription() {
            return "Suspend the computer when the download completes";
        }

        @Override
        public Severity getSeverity() {
            return Severity.HIGH;
        }

        @Override
        public boolean cancel() {
            canceled = true;
            return true;
        }
    }
}
