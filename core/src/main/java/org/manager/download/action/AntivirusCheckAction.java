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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.manager.download.Download;

/**
 * After completion action that performs an antivirus check on the downloaded
 * file. This action supports multiple Linux antivirus tools like ClamAV,
 * chkrootkit, and rkhunter.
 */
public class AntivirusCheckAction implements AfterCompletionAction {

    private static final Logger LOGGER = Logger.getLogger(AntivirusCheckAction.class.getName());

    public enum AntivirusType {
        CLAMAV, // ClamAV scanner
        CHKROOTKIT, // chkrootkit rootkit scanner
        RKHUNTER, // rkhunter rootkit scanner
        CUSTOM // Custom command
    }

    private final AntivirusType antivirusType;
    private String customCommand;
    private Process scanProcess;
    private CompletableFuture<Void> scanFuture;
    private final int timeoutSeconds;
    private boolean isScanning;
    private String scanResult;
    private boolean threatDetected;

    /**
     * Creates a new AntivirusCheckAction with the specified antivirus type.
     *
     * @param antivirusType  The type of antivirus to use
     * @param timeoutSeconds Timeout in seconds for the scan (0 for no timeout)
     */
    public AntivirusCheckAction(AntivirusType antivirusType, int timeoutSeconds) {
        this.antivirusType = antivirusType;
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
        this.customCommand = customCommand;
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
        this.isScanning = false;
        this.threatDetected = false;
    }

    @Override
    public boolean execute(Download download) {
        // If no download destination is set, we can't scan the file
        if (download.getDestination() == null) {
            LOGGER.warning("Cannot scan file: download destination is not set");
            return false;
        }

        // Get the source file path (download destination + filename)
        Path sourceFile = download.getDestination().resolve(download.getName());

        // Check if source file exists
        if (!Files.exists(sourceFile)) {
            LOGGER.warning("Cannot scan file: source file does not exist: " + sourceFile);
            return false;
        }

        try {
            List<String> command = buildCommand(sourceFile);
            if (command.isEmpty()) {
                LOGGER.severe("Failed to build command for antivirus scan");
                return false;
            }

            LOGGER.info("Starting configured antivirus scan");

            // Execute the scan command
            ProcessBuilder processBuilder = new ProcessBuilder(command);
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
                    LOGGER.log(Level.WARNING, "Error reading scan output", e);
                }
            });

            // Wait for the scan to complete with timeout
            boolean completed;
            if (timeoutSeconds > 0) {
                completed = scanProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!completed) {
                    LOGGER.warning("Antivirus scan timed out after " + timeoutSeconds + " seconds");
                    scanProcess.destroyForcibly();
                    scanFuture.cancel(true);
                    isScanning = false;
                    return false;
                }
            } else {
                scanProcess.waitFor();
                completed = true;
            }

            // Wait for output collection to complete
            scanFuture.join();
            isScanning = false;

            // Store the scan result
            scanResult = output.toString();

            // Check exit value
            int exitValue = scanProcess.exitValue();
            boolean success = exitValue == 0 || (antivirusType == AntivirusType.CLAMAV && exitValue == 1);

            if (success) {
                LOGGER.info("Antivirus scan completed successfully"
                        + (threatDetected ? ". THREATS DETECTED!" : ". No threats detected."));
            } else {
                LOGGER.warning("Antivirus scan failed with exit code: " + exitValue);
            }

            return success;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to execute antivirus scan: " + e.getMessage(), e);
            isScanning = false;
            return false;
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Antivirus scan was interrupted", e);
            Thread.currentThread().interrupt();
            isScanning = false;
            return false;
        }
    }

    private List<String> buildCommand(Path sourceFile) {
        List<String> command = new ArrayList<>();

        switch (antivirusType) {
            case CLAMAV -> {
                command.add("clamscan");
                command.add("--no-summary");
                command.add(sourceFile.toString());
            }
            case CHKROOTKIT -> {
                command.add("chkrootkit");
                command.add("-p");
                command.add(sourceFile.getParent().toString());
            }
            case RKHUNTER -> {
                command.add("rkhunter");
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
                LOGGER.warning("Unknown antivirus type: " + antivirusType);
                return new ArrayList<>();
            }
        }

        return command;
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
            LOGGER.log(Level.SEVERE, "Failed to cancel antivirus scan: " + e.getMessage(), e);
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
