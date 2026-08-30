package org.manager.download.action;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.manager.download.Download;

/**
 * After completion action that shuts down the computer.
 */
public class ShutdownComputerAction implements AfterCompletionAction {

    private static final Logger LOGGER = Logger.getLogger(ShutdownComputerAction.class.getName());

    private final int delayInSeconds;
    private volatile Process shutdownProcess;
    private volatile boolean shutdownInitiated;
    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.CountDownLatch delayCancelled =
            new java.util.concurrent.CountDownLatch(1);

    /**
     * Creates a new ShutdownComputerAction with the specified delay.
     *
     * @param delayInSeconds Delay in seconds before shutdown (0 for immediate)
     */
    public ShutdownComputerAction(int delayInSeconds) {
        this.delayInSeconds = Math.max(0, delayInSeconds);
        this.shutdownInitiated = false;
    }

    @Override
    public boolean execute(Download download) {
        String osName = System.getProperty("os.name").toLowerCase();
        String[] command;

        try {
            // Unix shutdown accepts a minute count, so converting 30 seconds
            // produced +0 (immediate). Keep the advertised delay in-process,
            // where it is exact and cancellable, then issue an immediate OS
            // command.
            if (delayInSeconds > 0
                    && delayCancelled.await(delayInSeconds, TimeUnit.SECONDS)) {
                return false;
            }
            if (cancelled.get()) {
                return false;
            }

            // Different shutdown commands based on operating system
            if (osName.contains("linux") || osName.contains("unix")) {
                command = new String[] { "shutdown", "-h", "now" };
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                command = new String[] { "shutdown", "-h", "now" };
            } else if (osName.contains("windows")) {
                command = new String[] { "shutdown", "/s", "/t", "0" };
            } else {
                LOGGER.severe("Unsupported operating system for shutdown: " + osName);
                return false;
            }

            // Execute the shutdown command
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            shutdownProcess = processBuilder.start();
            shutdownInitiated = true;

            // Log that shutdown has been initiated
            LOGGER.info("Shutdown initiated. System will shut down"
                    + (delayInSeconds > 0 ? " in " + delayInSeconds + " seconds" : " immediately"));

            // Wait for the process to complete (with timeout)
            boolean completed = shutdownProcess.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                LOGGER.warning("Shutdown command did not complete within timeout period");
                shutdownProcess.destroyForcibly();
                return false;
            }

            // Check exit value
            int exitValue = shutdownProcess.exitValue();
            if (exitValue != 0) {
                LOGGER.warning("Shutdown command returned non-zero exit value: " + exitValue);
                return false;
            }

            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to execute shutdown command: " + e.getMessage(), e);
            return false;
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Shutdown process was interrupted", e);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public ActionType getType() {
        return ActionType.SHUTDOWN_COMPUTER;
    }

    @Override
    public String getDescription() {
        return "Shutdown computer" + (delayInSeconds > 0 ? " after " + delayInSeconds + " seconds" : " immediately");
    }

    @Override
    public Severity getSeverity() {
        return Severity.CRITICAL;
    }

    @Override
    public boolean cancel() {
        cancelled.set(true);
        delayCancelled.countDown();
        if (!shutdownInitiated) {
            return true; // Nothing to cancel
        }

        try {
            String osName = System.getProperty("os.name").toLowerCase();
            String[] command;

            // Different cancel commands based on operating system
            if (osName.contains("linux") || osName.contains("unix") || osName.contains("mac")
                    || osName.contains("darwin")) {
                // Linux/Unix/macOS cancel shutdown command
                command = new String[] { "shutdown", "-c" };
            } else if (osName.contains("windows")) {
                // Windows cancel shutdown command
                command = new String[] { "shutdown", "/a" };
            } else {
                LOGGER.severe("Unsupported operating system for canceling shutdown: " + osName);
                return false;
            }

            // Execute the cancel command
            Process process = new ProcessBuilder(command).start();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);

            if (!completed) {
                LOGGER.warning("Cancel shutdown command did not complete within timeout period");
                process.destroyForcibly();
            }

            int exitValue = process.exitValue();
            if (exitValue != 0) {
                LOGGER.warning("Cancel shutdown command returned non-zero exit value: " + exitValue);
                return false;
            }

            shutdownInitiated = false;
            LOGGER.info("Shutdown canceled successfully");
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to cancel shutdown: " + e.getMessage(), e);
            return false;
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Cancel shutdown process was interrupted", e);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Get the delay in seconds before shutdown.
     *
     * @return The delay in seconds
     */
    public int getDelayInSeconds() {
        return delayInSeconds;
    }

    /**
     * Check if shutdown has been initiated.
     *
     * @return true if shutdown has been initiated, false otherwise
     */
    public boolean isShutdownInitiated() {
        return shutdownInitiated;
    }
}
