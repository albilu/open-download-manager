package org.manager.download.action;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.download.Download;

/**
 * After completion action that performs an antivirus check on the downloaded
 * file. This action supports multiple Linux antivirus tools like ClamAV,
 * chkrootkit, and rkhunter.
 */
public class AntivirusCheckAction implements AfterCompletionAction {

    private static final Logger LOGGER = LoggerFactory.getLogger(AntivirusCheckAction.class);

    public enum AntivirusType {
        CLAMAV, // ClamAV scanner
        CHKROOTKIT, // chkrootkit rootkit scanner
        RKHUNTER, // rkhunter rootkit scanner
        CUSTOM // Custom command
    }

    private final AntivirusType antivirusType;
    private final String executablePath;
    private String customCommand;
    private Process scanProcess;
    private CompletableFuture<Void> scanFuture;
    private final int timeoutSeconds;
    private boolean isScanning;
    private String scanResult;
    private boolean threatDetected;
    private String outcomeMessage = "Antivirus scan did not complete";

    /**
     * Creates a new AntivirusCheckAction with the specified antivirus type.
     *
     * @param antivirusType  The type of antivirus to use
     * @param timeoutSeconds Timeout in seconds for the scan (0 for no timeout)
     */
    public AntivirusCheckAction(AntivirusType antivirusType, int timeoutSeconds) {
        this(antivirusType, defaultExecutable(antivirusType), timeoutSeconds);
    }

    /**
     * Creates an action using a discovered and validated scanner executable.
     */
    public AntivirusCheckAction(AntivirusType antivirusType, String executablePath,
            int timeoutSeconds) {
        if (antivirusType == AntivirusType.CUSTOM) {
            throw new IllegalArgumentException(
                    "Use the custom-command constructor for CUSTOM antivirus type");
        }
        this.antivirusType = java.util.Objects.requireNonNull(antivirusType, "antivirusType");
        this.executablePath = executablePath == null || executablePath.isBlank()
                ? defaultExecutable(antivirusType) : executablePath;
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
        this.isScanning = false;
        this.threatDetected = false;
    }

    /**
     * Creates a new AntivirusCheckAction with a custom command.
     *
     * @param customCommand  The custom command to execute (should include {file}
     *                       placeholder)
     * @param timeoutSeconds Timeout in seconds for the scan (0 for no timeout)
     */
    public AntivirusCheckAction(String customCommand, int timeoutSeconds) {
        this.antivirusType = AntivirusType.CUSTOM;
        this.executablePath = null;
        this.customCommand = customCommand;
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
        this.isScanning = false;
        this.threatDetected = false;
    }

    @Override
    public boolean execute(Download download) {
        // One settings-backed action instance may be reused for multiple
        // downloads. Reset invocation-specific state before every scan so a
        // threat or result from an earlier file cannot leak into this row's
        // Actions result.
        isScanning = false;
        scanProcess = null;
        scanFuture = null;
        scanResult = null;
        threatDetected = false;
        outcomeMessage = "Antivirus scan did not complete";

        // If no download destination is set, we can't scan the file
        if (download.getDestination() == null) {
            LOGGER.warn("Cannot scan file: download destination is not set");
            outcomeMessage = "Download destination is not set";
            return false;
        }

        Path sourceFile = download.getPrimaryOutputPath();
        if (sourceFile == null) {
            LOGGER.warn("Cannot scan file: output path is unknown");
            outcomeMessage = "Downloaded file path is unknown";
            return false;
        }

        // Check if source file exists
        if (!Files.exists(sourceFile)) {
            LOGGER.warn("Cannot scan file: source file does not exist: " + sourceFile);
            outcomeMessage = "Downloaded file does not exist: " + sourceFile;
            return false;
        }

        try {
            List<String> command = buildCommand(sourceFile);
            if (command.isEmpty()) {
                LOGGER.error("Failed to build command for antivirus scan");
                outcomeMessage = "Could not build the antivirus command";
                return false;
            }

            LOGGER.info("Starting configured antivirus scan");

            // Execute the scan command
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            scanProcess = processBuilder.start();
            isScanning = true;

            // Collect output in a separate thread
            StringBuilder output = new StringBuilder();
            scanFuture = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(scanProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");

                        // Check for threat indicators in the output
                        if (line.toLowerCase().contains("found")
                                || line.toLowerCase().contains("infected")
                                || line.toLowerCase().contains("virus")
                                || line.toLowerCase().contains("malware")
                                || line.toLowerCase().contains("trojan")
                                || line.toLowerCase().contains("rootkit")
                                || line.toLowerCase().contains("suspicious")) {
                            threatDetected = true;
                        }
                    }
                } catch (IOException e) {
                    LOGGER.warn("Error reading scan output", e);
                }
            });

            // Wait for the scan to complete with timeout
            boolean completed;
            if (timeoutSeconds > 0) {
                completed = scanProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!completed) {
                    LOGGER.warn("Antivirus scan timed out after " + timeoutSeconds + " seconds");
                    scanProcess.destroyForcibly();
                    scanFuture.cancel(true);
                    isScanning = false;
                    outcomeMessage = "Antivirus scan timed out after "
                            + timeoutSeconds + " seconds";
                    return false;
                }
            } else {
                scanProcess.waitFor();
                completed = true;
            }

            // Wait for output collection to complete
            try {
                scanFuture.join();
            } catch (java.util.concurrent.CancellationException e) {
                isScanning = false;
                outcomeMessage = "Antivirus scan was canceled";
                return false;
            }
            isScanning = false;

            // Store the scan result
            scanResult = output.toString();

            // Check exit value
            int exitValue = scanProcess.exitValue();
            boolean success = exitValue == 0 || (antivirusType == AntivirusType.CLAMAV && exitValue == 1);

            if (success) {
                outcomeMessage = threatDetected ? "Threats detected" : "No threats detected";
                LOGGER.info("Antivirus scan completed successfully"
                        + (threatDetected ? ". THREATS DETECTED!" : ". No threats detected."));
            } else {
                outcomeMessage = "Antivirus scanner exited with code " + exitValue;
                LOGGER.warn("Antivirus scan failed with exit code: " + exitValue);
            }

            return success;
        } catch (IOException e) {
            LOGGER.error("Failed to execute antivirus scan: " + e.getMessage(), e);
            isScanning = false;
            outcomeMessage = "Could not run antivirus scanner: " + e.getMessage();
            return false;
        } catch (InterruptedException e) {
            LOGGER.warn("Antivirus scan was interrupted", e);
            Thread.currentThread().interrupt();
            isScanning = false;
            outcomeMessage = "Antivirus scan was interrupted";
            return false;
        }
    }

    private List<String> buildCommand(Path sourceFile) {
        List<String> command = new ArrayList<>();

        switch (antivirusType) {
            case CLAMAV -> {
                command.add(executablePath);
                command.add("--no-summary");
                command.add(sourceFile.toString());
            }
            case CHKROOTKIT -> {
                command.add(executablePath);
                command.add("-p");
                command.add(sourceFile.getParent().toString());
            }
            case RKHUNTER -> {
                command.add(executablePath);
                command.add("--checkall");
                command.add("--skip-keypress");
                command.add("--no-mail-on-warning");
                command.add("--pkgmgr");
                command.add(sourceFile.toString());
            }
            case CUSTOM -> {
                if (customCommand != null && !customCommand.isEmpty()) {
                    // Parse the custom command and replace {file} placeholder
                    String[] parts = customCommand.replace("{file}", sourceFile.toString()).split("\\s+");
                    for (String part : parts) {
                        command.add(part);
                    }
                }
            }
            default -> {
                LOGGER.warn("Unknown antivirus type: " + antivirusType);
                return new ArrayList<>();
            }
        }

        return command;
    }

    private static String defaultExecutable(AntivirusType antivirusType) {
        return switch (antivirusType) {
            case CLAMAV -> "clamscan";
            case CHKROOTKIT -> "chkrootkit";
            case RKHUNTER -> "rkhunter";
            case CUSTOM -> throw new IllegalArgumentException(
                    "CUSTOM antivirus type has no fixed executable");
        };
    }

    @Override
    public ActionType getType() {
        return ActionType.ANTIVIRUS_CHECK;
    }

    @Override
    public String getDescription() {
        return "Antivirus check using " + getAntivirusName()
                + (timeoutSeconds > 0 ? " (timeout: " + timeoutSeconds + "s)" : "");
    }

    @Override
    public Severity getSeverity() {
        return Severity.HIGH;
    }

    @Override
    public String getResultMessage() {
        return outcomeMessage;
    }

    @Override
    public String getFailureMessage() {
        return outcomeMessage;
    }

    @Override
    public String getOutput() {
        return scanResult == null ? "" : scanResult;
    }

    private String getAntivirusName() {
        return switch (antivirusType) {
            case CLAMAV ->
                "ClamAV";
            case CHKROOTKIT ->
                "chkrootkit";
            case RKHUNTER ->
                "rkhunter";
            case CUSTOM ->
                "custom command";
            default ->
                "unknown";
        };
    }

    @Override
    public boolean cancel() {
        if (!isScanning || scanProcess == null) {
            return true; // Nothing to cancel
        }

        try {
            scanProcess.destroyForcibly();
            if (scanFuture != null) {
                scanFuture.cancel(true);
            }
            isScanning = false;
            LOGGER.info("Antivirus scan canceled");
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to cancel antivirus scan: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get the scan result output.
     *
     * @return The scan result output, or null if no scan has completed
     */
    public String getScanResult() {
        return scanResult;
    }

    /**
     * Check if a threat was detected during the scan.
     *
     * @return true if a threat was detected, false otherwise
     */
    public boolean isThreatDetected() {
        return threatDetected;
    }

    /**
     * Get the type of antivirus being used.
     *
     * @return The antivirus type
     */
    public AntivirusType getAntivirusType() {
        return antivirusType;
    }

    /**
     * Get the custom command (if using CUSTOM type).
     *
     * @return The custom command
     */
    public String getCustomCommand() {
        return customCommand;
    }

    /** Discovered executable used for built-in scanners, or null for custom. */
    public String getExecutablePath() {
        return executablePath;
    }

    /**
     * Get the timeout in seconds.
     *
     * @return The timeout in seconds (0 for no timeout)
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Check if a scan is currently in progress.
     *
     * @return true if scanning is in progress, false otherwise
     */
    public boolean isScanning() {
        return isScanning;
    }
}
