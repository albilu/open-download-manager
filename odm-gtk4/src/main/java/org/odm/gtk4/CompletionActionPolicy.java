package org.odm.gtk4;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.AntivirusCheckAction;
import org.manager.download.action.ExecuteCommandAction;
import org.manager.download.action.PlayNotificationAction;
import org.manager.download.action.ShutdownComputerAction;
import org.manager.download.action.SubtitleDownloadAction;
import org.manager.tools.ToolPaths;
import org.subliminal.SubliminalClient;
import org.subliminal.SubliminalSettings;
import org.ytdlp.YtDlpClient;

/**
 * Maps persisted completion-action checkbox keys to concrete actions. Plain
 * policy, no GTK; the interactive custom-command prompt stays in the window.
 */
final class CompletionActionPolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompletionActionPolicy.class);

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

    /** Builds every selected action and returns them in declared priority order. */
    static java.util.List<AfterCompletionAction> forChoices(
            java.util.Collection<String> choices, GlobalSettings settings) {
        if (choices == null || choices.isEmpty()) {
            return java.util.List.of();
        }
        return choices.stream()
                .map(choice -> forChoice(choice, settings))
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(AfterCompletionAction::getPriority))
                .toList();
    }

    /** Custom completion action from the stored command, or null when blank. */
    static AfterCompletionAction customAction(GlobalSettings settings) {
        String saved = settings.getProperty("ui.completionCommand", "");
        return saved.isBlank() ? null : new ExecuteCommandAction(saved);
    }

    /**
     * Builds the antivirus completion action from persisted settings:
     * {@code antivirus.scanner} (auto|clamav|chkrootkit|rkhunter|custom,
     * default auto), {@code antivirus.command} for the custom scanner, and
     * {@code antivirus.timeout} seconds (default 600, 0 = no timeout).
     */
    static AntivirusCheckAction buildAntivirusAction(GlobalSettings settings) {
        String scanner = settings.getProperty("antivirus.scanner", "auto");
        int timeout = settings.getIntProperty("antivirus.timeout", 600);
        if ("auto".equalsIgnoreCase(scanner)) {
            return automaticAntivirusAction(timeout);
        }
        AntivirusCheckAction.AntivirusType type = switch (scanner.toLowerCase()) {
            case "clamav" -> AntivirusCheckAction.AntivirusType.CLAMAV;
            case "chkrootkit" -> AntivirusCheckAction.AntivirusType.CHKROOTKIT;
            case "rkhunter" -> AntivirusCheckAction.AntivirusType.RKHUNTER;
            case "custom" -> AntivirusCheckAction.AntivirusType.CUSTOM;
            default -> null;
        };
        if (type == null) {
            LOGGER.warn("No usable antivirus scanner is configured");
            return null;
        }
        if (type == AntivirusCheckAction.AntivirusType.CUSTOM) {
            String command = settings.getProperty("antivirus.command", "");
            if (command.isBlank()) {
                LOGGER.warn("The custom antivirus command is empty");
                return null;
            }
            return new AntivirusCheckAction(command, timeout);
        }
        String executable = switch (type) {
            case CLAMAV -> ToolPaths.resolve("antivirus-clamav", "clamscan");
            case CHKROOTKIT -> ToolPaths.resolve("antivirus-chkrootkit", "chkrootkit");
            case RKHUNTER -> ToolPaths.resolve("antivirus-rkhunter", "rkhunter");
            case CUSTOM -> throw new IllegalStateException("Handled above");
        };
        return new AntivirusCheckAction(type, executable, timeout);
    }

    private static AntivirusCheckAction automaticAntivirusAction(int timeout) {
        try {
            return automaticAntivirusAction(
                    org.manager.ApplicationContext.getToolManagerFactory(), timeout);
        } catch (RuntimeException unavailableContext) {
            LOGGER.warn("Antivirus scanner discovery is unavailable", unavailableContext);
            return null;
        }
    }

    static AntivirusCheckAction automaticAntivirusAction(
            org.manager.tools.ToolManagerFactory factory, int timeout) {
        if (factory == null) {
            return null;
        }
        for (org.antivirus.AntivirusToolManager manager
                : factory.getAntivirusManagers()) {
            try {
                manager.validateTool();
                String executable = manager.getToolPath();
                if (executable == null || executable.isBlank()) {
                    continue;
                }
                AntivirusCheckAction.AntivirusType type = switch (manager.getScanner()) {
                    case CLAMAV -> AntivirusCheckAction.AntivirusType.CLAMAV;
                    case CHKROOTKIT -> AntivirusCheckAction.AntivirusType.CHKROOTKIT;
                    case RKHUNTER -> AntivirusCheckAction.AntivirusType.RKHUNTER;
                };
                return new AntivirusCheckAction(type, executable, timeout);
            } catch (org.manager.tools.ToolManager.ToolException
                    | RuntimeException validationFailure) {
                LOGGER.debug("Antivirus scanner did not pass validation: "
                        + manager.getScanner().label(), validationFailure);
            }
        }
        LOGGER.warn("No validated antivirus scanner is available");
        return null;
    }

    /** Subtitle action defaults to English; media records carry their own languages. */
    static SubtitleDownloadAction buildSubtitleAction(GlobalSettings settings) {
        SubliminalSettings subtitleSettings = new SubliminalSettings();
        int timeoutSeconds = Math.max(1,
                settings.getIntProperty("subtitles.timeoutSeconds", 300));
        subtitleSettings.setTimeout(java.time.Duration.ofSeconds(timeoutSeconds));
        String ytDlpPath = settings.getYtDlpPath();
        return new SubtitleDownloadAction(subtitleSettings,
                new SubliminalClient(),
                new YtDlpClient(
                        ytDlpPath == null || ytDlpPath.isBlank()
                                ? ToolPaths.ytDlp() : ytDlpPath,
                        settings.isHonorExternalYtDlpConfiguration(),
                        settings.isHonorExternalAria2Configuration()));
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
                LOGGER.warn("Failed to suspend: " + e.getMessage());
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
