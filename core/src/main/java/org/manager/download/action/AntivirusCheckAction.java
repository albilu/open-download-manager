package org.manager.download.action;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.download.Download;

/**
 * After completion action that performs an antivirus check on the downloaded
 * file using ClamAV or a custom file-scanning command.
 */
public class AntivirusCheckAction implements AfterCompletionAction {

    private static final Logger LOGGER = LoggerFactory.getLogger(AntivirusCheckAction.class);
    private static final int MAX_CAPTURED_OUTPUT_CHARS = 1_000_000;
    private static final int OUTPUT_READER_TIMEOUT_SECONDS = 5;

    public enum AntivirusType {
        CLAMAV, // ClamAV scanner
        CUSTOM // Custom command
    }

    private final AntivirusType antivirusType;
    private final String executablePath;
    private String customCommand;
    private volatile Process scanProcess;
    private volatile CompletableFuture<String> scanFuture;
    private final int timeoutSeconds;
    private volatile boolean isScanning;
    private volatile boolean scanCancelled;
    private String unavailableReason;
    private volatile String scanResult;
    private Path scannedFile;
    private Integer scanExitCode;
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

    /** Keeps an obsolete configured scanner visible as a failed action. */
    public static AntivirusCheckAction unavailable(String reason, int timeoutSeconds) {
        AntivirusCheckAction action = new AntivirusCheckAction((String) null, timeoutSeconds);
        action.unavailableReason = java.util.Objects.requireNonNull(reason);
        return action;
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
        scannedFile = null;
        scanExitCode = null;
        scanCancelled = false;
        threatDetected = false;
        outcomeMessage = "Antivirus scan did not complete";

        if (unavailableReason != null) {
            outcomeMessage = unavailableReason;
            return false;
        }

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
        scannedFile = sourceFile;

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

            // Drain merged stdout/stderr concurrently so a verbose scanner
            // cannot fill its pipe and block before the timeout is observed.
            scanFuture = captureOutput(scanProcess);

            // Wait for the scan to complete with timeout
            boolean completed;
            if (timeoutSeconds > 0) {
                completed = scanProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!completed) {
                    LOGGER.warn("Antivirus scan timed out after " + timeoutSeconds + " seconds");
                    scanProcess.destroyForcibly();
                    scanProcess.waitFor(5, TimeUnit.SECONDS);
                    scanExitCode = exitCodeOf(scanProcess);
                    scanResult = awaitOutput(scanFuture);
                    isScanning = false;
                    outcomeMessage = "Antivirus scan timed out after "
                            + timeoutSeconds + " seconds";
                    return false;
                }
            } else {
                scanProcess.waitFor();
                completed = true;
            }

            scanResult = awaitOutput(scanFuture);
            isScanning = false;
            if (scanCancelled) {
                scanExitCode = exitCodeOf(scanProcess);
                outcomeMessage = "Antivirus scan was canceled";
                return false;
            }

            // Check exit value
            int exitValue = scanProcess.exitValue();
            scanExitCode = exitValue;
            boolean success = exitValue == 0 || (antivirusType == AntivirusType.CLAMAV && exitValue == 1);

            threatDetected = antivirusType == AntivirusType.CLAMAV && exitValue == 1;
            if (success) {
                outcomeMessage = antivirusType == AntivirusType.CUSTOM
                        ? scanResult.isBlank()
                                ? "Custom scanner command completed without output"
                                : "Custom scanner command completed; inspect its output"
                        : threatDetected ? "Threats detected" : "No threats detected";
                LOGGER.info("Antivirus scan finished: {}", outcomeMessage);
            } else {
                outcomeMessage = "Antivirus scanner exited with code " + exitValue;
                LOGGER.warn("Antivirus scan failed with exit code: " + exitValue);
            }

            return success;
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.error("Failed to execute antivirus scan: " + e.getMessage(), e);
            isScanning = false;
            outcomeMessage = "Could not run antivirus scanner: " + e.getMessage();
            return false;
        } catch (InterruptedException e) {
            LOGGER.warn("Antivirus scan was interrupted", e);
            if (scanProcess != null && scanProcess.isAlive()) {
                scanProcess.destroyForcibly();
            }
            scanResult = awaitOutput(scanFuture);
            scanExitCode = exitCodeOf(scanProcess);
            isScanning = false;
            outcomeMessage = "Antivirus scan was interrupted";
            Thread.currentThread().interrupt();
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
            case CUSTOM -> {
                if (customCommand != null && !customCommand.isEmpty()) {
                    // Parse the custom command and replace {file} placeholder
                    for (String argument : ExecuteCommandAction.tokenize(customCommand)) {
                        command.add(argument.replace("{file}", sourceFile.toString()));
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
        StringBuilder output = new StringBuilder();
        output.append("Scanner: ").append(getAntivirusName())
                .append("\nFile: ").append(scannedFile == null ? "—" : scannedFile)
                .append("\nExit code: ")
                .append(scanExitCode == null ? "—" : scanExitCode)
                .append("\nProcess output:");
        if (scanResult == null || scanResult.isBlank()) {
            output.append(" (none)");
        } else {
            output.append('\n').append(scanResult);
        }
        startLine(output);
        output.append("Result: ").append(outcomeMessage);
        return output.toString();
    }

    private static CompletableFuture<String> captureOutput(Process process) {
        CompletableFuture<String> captured = new CompletableFuture<>();
        Thread.ofVirtual().name("odm-antivirus-output").start(() -> {
            try (InputStreamReader reader = new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder retained = new StringBuilder();
                boolean truncated = false;
                char[] buffer = new char[8192];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    int remaining = MAX_CAPTURED_OUTPUT_CHARS - retained.length();
                    if (remaining > 0) {
                        retained.append(buffer, 0, Math.min(count, remaining));
                    }
                    truncated |= count > remaining;
                }
                if (truncated) {
                    retained.append("\n\n[Antivirus output truncated by ODM]");
                }
                captured.complete(retained.toString());
            } catch (IOException e) {
                captured.completeExceptionally(e);
            }
        });
        return captured;
    }

    private static String awaitOutput(CompletableFuture<String> output) {
        if (output == null) {
            return "";
        }
        try {
            return output.get(OUTPUT_READER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[Interrupted while collecting antivirus output]";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause == null ? e.getMessage() : cause.getMessage();
            return "[Could not read antivirus output: "
                    + (message == null || message.isBlank()
                            ? e.getClass().getSimpleName() : message) + "]";
        } catch (TimeoutException e) {
            return "[Antivirus output reader did not finish]";
        }
    }

    private static Integer exitCodeOf(Process process) {
        return process == null || process.isAlive() ? null : process.exitValue();
    }

    private static void startLine(StringBuilder output) {
        if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') {
            output.append('\n');
        }
    }

    private String getAntivirusName() {
        return switch (antivirusType) {
            case CLAMAV ->
                "ClamAV";
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
            scanCancelled = true;
            scanProcess.destroyForcibly();
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
