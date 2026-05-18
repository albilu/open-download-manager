package org.odm;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.jgtk.core.GtkNativeLibraries;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.StartupCoordinator;
import org.manager.download.DownloadManager;
import org.manager.tools.ToolManagerFactory;
import org.odm.ui.controller.MainWindowController;
import org.odm.ui.controller.StartShutdownController;
import org.odm.ui.service.core.DownloadCoordinatorService;
import org.odm.ui.service.state.SessionStateService;
import org.tor.TorService;

/**
 * Main application class for Open Download Manager GTK GUI.
 *
 * This is the entry point for the GTK-based user interface of the Open Download
 * Manager. It integrates the core download functionality with the JGTK GUI
 * framework.
 */
public class OpenDownloadManager {

    private static final Logger LOGGER = Logger.getLogger(OpenDownloadManager.class.getName());

    private MainWindowController mainController;
    private DownloadManager downloadManager;
    private static ToolManagerFactory toolManagerFactory;
    private GlobalSettings settings;
    private SessionStateService uiStateService;
    private DownloadCoordinatorService downloadUIService;
    private TorService torService;
    private StartShutdownController startupShutdownDialogController;
    private Thread progressDialogThread;

    /**
     * Main entry point for the application.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        LOGGER.info("Starting Open Download Manager...");

        // Initialize GTK will be done when creating GladeUI instance
        // Create and run the application
        OpenDownloadManager app = new OpenDownloadManager();

        // Install JVM shutdown hook for emergency cleanup
        app.installShutdownHook();

        try {
            app.showStartupDialogAsync();
            app.initialize();
            app.hideStartupShutdownDialog();
            app.run();
        } catch (Exception e) {
            LOGGER.severe("Application failed to start: " + e.getMessage());
            e.printStackTrace();

            // Attempt cleanup before exit
            try {
                app.setStartupDialogMessageWithProgress("Shutting down due to startup failure...", 0.0);
                app.shutdown();
                app.hideStartupShutdownDialog();
            } catch (Exception shutdownError) {
                LOGGER.severe("Error during emergency shutdown: " + shutdownError.getMessage());
            }

            System.exit(1);
        }
    }

    /**
     * Initializes the application components.
     */
    private void initialize() throws Exception {
        long startTime = System.currentTimeMillis();
        LOGGER.info("Initializing application components with startup coordination...");
        // showStartupDialogAsync();
        setStartupDialogMessage("Initializing core components...");
        setStartupDialogProgress(0.1);

        // Get startup coordinator for optimization
        StartupCoordinator coordinator = ApplicationContext.getStartupCoordinator();

        // Initialize the centralized service container with default settings
        ApplicationContext.initialize();

        // Get core services from the optimized factory (fast path - no locking if
        // already created)
        settings = ApplicationContext.getGlobalSettings();
        toolManagerFactory = ApplicationContext.getToolManagerFactory();
        downloadManager = ApplicationContext.getDownloadManager();

        // Check optimization hints before proceeding
        StartupCoordinator.StartupOptimizationHints hints = ApplicationContext.getOptimizationHints();
        if (hints.isReadyForUIInitialization()) {
            LOGGER.info("Core components ready, proceeding with UI service initialization");
        }

        // Create UI services with coordination
        if (coordinator.beginComponentInitialization(StartupCoordinator.UI_STATE_SERVICE)) {
            try {
                setStartupDialogMessage("Initializing UI state service...");
                setStartupDialogProgress(0.3);
                uiStateService = new SessionStateService(settings);
                coordinator.completeComponentInitialization(StartupCoordinator.UI_STATE_SERVICE);
            } catch (Exception e) {
                coordinator.failComponentInitialization(StartupCoordinator.UI_STATE_SERVICE, e);
                throw e;
            }
        }

        if (coordinator.beginComponentInitialization(StartupCoordinator.DOWNLOAD_UI_SERVICE)) {
            try {
                setStartupDialogMessage("Initializing download UI service...");
                setStartupDialogProgress(0.5);
                downloadUIService = new DownloadCoordinatorService(downloadManager, settings);
                coordinator.completeComponentInitialization(StartupCoordinator.DOWNLOAD_UI_SERVICE);
            } catch (Exception e) {
                coordinator.failComponentInitialization(StartupCoordinator.DOWNLOAD_UI_SERVICE, e);
                throw e;
            }
        }

        // Initialize TorService
        try {
            setStartupDialogMessage("Initializing Tor service...");
            setStartupDialogProgress(0.55);
            // Use embedded Tor binary or system Tor
            String torPath = toolManagerFactory.getTorManager().getToolPath();
            if (torPath != null) {
                torService = new TorService(torPath);
                LOGGER.info("TorService initialized with path: " + torPath);
            } else {
                // Create a minimal TorService instance even if Tor is not available
                torService = new TorService("tor"); // Will use system PATH
                LOGGER.warning("Tor executable not found, using system PATH fallback");
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to initialize TorService: " + e.getMessage());
            // Create a no-op TorService to prevent null pointer exceptions
            torService = new TorService("tor");
        }

        // Register UI services with the centralized factory for lifecycle management
        ApplicationContext.registerUIStateService(uiStateService);
        ApplicationContext.registerDownloadUIService(downloadUIService);

        // Check dependencies (skip if already done)
        if (!hints.canSkipToolDiscovery()) {
            checkDependencies();
        } else {
            LOGGER.info("Tool discovery already complete, skipping dependency check");
        }

        // Initialize services that need explicit initialization
        setStartupDialogMessage("Finalizing initialization...");
        setStartupDialogProgress(0.8);
        downloadManager.initialize().join();
        uiStateService.initialize();
        downloadUIService.initialize();

        long duration = System.currentTimeMillis() - startTime;
        LOGGER.info("Core components initialized successfully in " + duration + "ms");

        // Log startup statistics
        if (ApplicationContext.isStartupComplete()) {
            long totalStartup = ApplicationContext.getStartupDuration();
            LOGGER.info("Total application startup completed in " + totalStartup + "ms");
        }
        setStartupDialogMessage("Startup complete!");
        setStartupDialogProgress(1.0);
    }

    /**
     * Checks if all required dependencies are available. Uses startup
     * coordination to avoid duplicate checks.
     */
    private void checkDependencies() {
        StartupCoordinator coordinator = ApplicationContext.getStartupCoordinator();

        // Check if tool discovery is already in progress or complete
        if (coordinator.isComponentInitialized(StartupCoordinator.TOOL_DISCOVERY)
                || coordinator.isComponentInitializing(StartupCoordinator.TOOL_DISCOVERY)) {
            LOGGER.info("Tool discovery already handled, skipping dependency check");
            return;
        }

        if (coordinator.beginComponentInitialization(StartupCoordinator.TOOL_DISCOVERY)) {
            try {
                LOGGER.info("Checking system dependencies with coordination...");

                // Check required dependencies asynchronously
                toolManagerFactory.checkAllToolsAsync()
                        .thenAccept(report -> {
                            boolean allAvailable = report.values().stream().allMatch(Boolean::booleanValue);
                            if (!allAvailable) {
                                LOGGER.warning(
                                        "Some dependencies are missing. Application may have limited functionality.");
                                // In a full implementation, show a warning dialog here
                            } else {
                                LOGGER.info("All dependencies are available");
                            }
                            coordinator.completeComponentInitialization(StartupCoordinator.TOOL_DISCOVERY);
                        })
                        .exceptionally(throwable -> {
                            LOGGER.log(Level.WARNING, "Error checking dependencies", throwable);
                            coordinator.failComponentInitialization(StartupCoordinator.TOOL_DISCOVERY, throwable);
                            return null;
                        });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to check dependencies", e);
                coordinator.failComponentInitialization(StartupCoordinator.TOOL_DISCOVERY, e);
                // Continue startup - dependency checking is non-critical for basic
                // functionality
            }
        } else {
            LOGGER.info("Dependency check already in progress in another thread");
        }
    }

    /**
     * Runs the main application.
     */
    private void run() {
        try {
            // Initialize main controller
            initializeMainController();

            // Hide startup dialog before showing main window
            // hideStartupDialog();
            // Show the main window
            if (mainController != null) {
                mainController.show();
                LOGGER.info("Main window displayed");
            } else {
                LOGGER.severe("Main controller is null, cannot show window");
                throw new RuntimeException("Main controller initialization failed");
            }

            // Run the GTK main loop
            LOGGER.info("Starting GTK main loop");
            if (mainController != null && mainController.getUI() != null) {
                mainController.getUI().run();
            } else {
                LOGGER.severe("UI is null, cannot run GTK main loop");
                throw new RuntimeException("UI initialization failed");
            }
        } catch (Exception e) {
            LOGGER.severe("Error running application: " + e.getMessage());
            e.printStackTrace();
            setStartupDialogMessageWithProgress("Shutting down due to runtime error...", 0.0);
            shutdown();
            hideStartupShutdownDialog();
            throw new RuntimeException("Failed to run application", e);
        }
    }

    /**
     * Initializes the main controller and UI.
     */
    private void initializeMainController() {
        try {
            LOGGER.info("Creating main controller...");

            // Validate dependencies
            if (downloadManager == null) {
                throw new IllegalStateException("DownloadManager is null");
            }
            if (settings == null) {
                throw new IllegalStateException("GlobalSettings is null");
            }
            if (uiStateService == null) {
                throw new IllegalStateException("UIStateService is null");
            }
            if (downloadUIService == null) {
                throw new IllegalStateException("DownloadUIService is null");
            }

            // Create main controller
            mainController = new MainWindowController(
                    downloadManager,
                    settings,
                    uiStateService,
                    downloadUIService,
                    torService);

            // Register application shutdown callback for proper resource cleanup
            LOGGER.info("Registering application shutdown callback...");
            mainController.setApplicationShutdownCallback(this::shutdown);

            LOGGER.info("Initializing main controller...");
            // Initialize controller (this creates and loads the UI internally)
            mainController.initialize();

            // Verify UI was created successfully
            if (mainController.getUI() == null) {
                throw new RuntimeException("Main controller failed to create UI instance");
            }

            // Note: Window close handlers are now managed by MainWindowController
            // which will call the registered shutdown callback for proper cleanup
            LOGGER.info("Main controller initialized successfully");

        } catch (Exception e) {
            LOGGER.severe("Failed to initialize main controller: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Cannot initialize main UI", e);
        }
    }

    /**
     * Installs a JVM shutdown hook for emergency cleanup.
     */
    private void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                if (!isShuttingDown) {
                    LOGGER.info("JVM shutdown hook triggered - performing emergency cleanup");
                    try {
                        // Perform minimal emergency cleanup
                        emergencyCleanup();
                    } catch (Exception e) {
                        // Suppress all exceptions in shutdown hook
                        LOGGER.severe("Error in shutdown hook: " + e.getMessage());
                    }
                    hideStartupShutdownDialog();
                }
            }
        }, "emergency-shutdown-hook"));

        LOGGER.info("JVM shutdown hook installed");
    }

    /**
     * Shows the startup dialog asynchronously.
     */
    private void showStartupDialogAsync() {
        if (startupShutdownDialogController == null) {
            startupShutdownDialogController = new StartShutdownController();
        }
        startupShutdownDialogController.setStatusMessage("Starting Open Download Manager...");
        startupShutdownDialogController.setProgress(0.0);
        startupShutdownDialogController.showDialog();

        progressDialogThread = new Thread(() -> {
            if (startupShutdownDialogController.getUI() != null) {
                startupShutdownDialogController.getUI().run();
            }
        }, "startup-progress-dialog-thread");
        progressDialogThread.setDaemon(true); // Optional
        progressDialogThread.start();
    }

    /**
     * Updates the startup dialog message.
     */
    private void setStartupDialogMessage(String message) {
        if (startupShutdownDialogController != null) {
            startupShutdownDialogController.setStatusMessage(message);
        }
    }

    /**
     * Updates the startup dialog progress.
     */
    private void setStartupDialogProgress(double fraction) {
        if (startupShutdownDialogController != null) {
            startupShutdownDialogController.setProgress(fraction);
        }
    }

    /**
     * Hides the startup dialog.
     */
    private void hideStartupShutdownDialog() {
        if (startupShutdownDialogController != null) {
            startupShutdownDialogController.hideDialog();
            // Properly terminate the startup dialog's GTK context
            if (startupShutdownDialogController.getUI() != null) {
                LOGGER.info("Quitting GTK Startup application...");
                startupShutdownDialogController.getUI().quit();
            }
        }
        // Clear thread reference to allow garbage collection
        progressDialogThread = null;
    }

    /**
     * Shows the startup/shutdown dialog with message and progress.
     */
    private void setStartupDialogMessageWithProgress(String message, double progress) {
        if (startupShutdownDialogController == null) {
            startupShutdownDialogController = new StartShutdownController();
        }
        startupShutdownDialogController.setStatusMessage(message);
        startupShutdownDialogController.setProgress(progress);
        startupShutdownDialogController.showDialog();
    }

    /**
     * Hides the main controller and shows the shutdown dialog.
     */
    private void hideMainControllerAndShowShutdownDialog() {
        try {
            LOGGER.info("Preparing shutdown dialog...");

            // Show shutdown dialog first
            showShutdownDialogAsync();

            // Give the dialog more time to appear and process GTK events
            Thread.sleep(500);

            // Ensure the dialog is visible before hiding main window
            if (startupShutdownDialogController != null) {
                startupShutdownDialogController.ensureVisible();

                // Process more GTK events to ensure dialog is fully rendered
                for (int i = 0; i < 20; i++) {
                    try {
                        GtkNativeLibraries.Gtk.INSTANCE.gtk_main_iteration_do(false);
                        Thread.sleep(25);
                    } catch (Exception e) {
                        // Ignore iteration errors
                    }
                }
            }

            // Update the dialog with initial shutdown message
            setShutdownDialogMessageWithProgress("Initiating shutdown...", 0.1);

            // Now hide the main window after dialog is fully shown
            if (mainController != null) {
                LOGGER.info("Hiding main window for shutdown...");
                mainController.hide();
            }

            // Final visibility check
            if (startupShutdownDialogController != null) {
                startupShutdownDialogController.ensureVisible();
            }

            LOGGER.info("Shutdown dialog displayed and main window hidden");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error hiding main controller or showing shutdown dialog", e);
            // Continue with shutdown even if dialog fails
        }
    }

    /**
     * Shows the shutdown dialog using the existing GTK context.
     */
    private void showShutdownDialogAsync() {
        try {
            LOGGER.info("Creating shutdown dialog in existing GTK context...");

            // Create a new dialog controller for shutdown
            startupShutdownDialogController = new StartShutdownController();
            startupShutdownDialogController.setStatusMessage("Shutting down Open Download Manager...");
            startupShutdownDialogController.setProgress(0.0);

            // Show the dialog immediately in the current GTK context
            startupShutdownDialogController.showDialog();
            startupShutdownDialogController.ensureVisible();

            // Force GTK to process events to ensure dialog is shown
            if (startupShutdownDialogController.getUI() != null) {
                // Process pending GTK events to ensure dialog appears
                for (int i = 0; i < 10; i++) {
                    try {
                        GtkNativeLibraries.Gtk.INSTANCE.gtk_main_iteration_do(false);
                        Thread.sleep(10);
                    } catch (Exception e) {
                        // Ignore iteration errors
                    }
                }
            }

            LOGGER.info("Shutdown dialog created and shown in existing GTK context");
        } catch (Exception e) {
            LOGGER.warning("Failed to show shutdown dialog: " + e.getMessage());
        }
    }

    /**
     * Updates the shutdown dialog message and progress.
     */
    private void setShutdownDialogMessageWithProgress(String message, double progress) {
        if (startupShutdownDialogController != null) {
            LOGGER.info("Updating shutdown dialog: " + message + " (" + (int) (progress * 100) + "%)");
            startupShutdownDialogController.setStatusMessage(message);
            startupShutdownDialogController.setProgress(progress);
            startupShutdownDialogController.ensureVisible();

            // Process GTK events to ensure the update is visible
            try {
                for (int i = 0; i < 5; i++) {
                    GtkNativeLibraries.Gtk.INSTANCE.gtk_main_iteration_do(false);
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                // Ignore GTK iteration errors
            }
        } else {
            LOGGER.warning("Shutdown dialog controller is null, cannot update message: " + message);
        }
    }

    /**
     * Performs emergency cleanup when JVM is shutting down unexpectedly.
     */
    private void emergencyCleanup() {
        setShutdownDialogMessageWithProgress("Performing emergency cleanup...", 0.0);
        try {
            // Mark as shutting down to prevent normal shutdown from running
            isShuttingDown = true;

            // Try to save critical state quickly
            setShutdownDialogMessageWithProgress("Saving critical state...", 0.3);
            if (downloadManager != null) {
                try {
                    downloadManager.saveState().get(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Ignore in emergency cleanup - can't log in shutdown hook
                }
            }

            // Emergency external process cleanup - focus on aria2c only in JVM shutdown
            // hook
            setShutdownDialogMessageWithProgress("Cleaning up external processes...", 0.7);
            try {
                Process killAria2 = new ProcessBuilder("pkill", "-f", "aria2c").start();
                killAria2.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                // Ignore in emergency cleanup
            }

            setShutdownDialogMessageWithProgress("Emergency cleanup completed", 1.0);
        } catch (Exception e) {
            // Suppress all exceptions in emergency cleanup
        }
    }

    /**
     * Performs clean shutdown of the application.
     */
    private void shutdown() {
        if (isShuttingDown) {
            LOGGER.info("Shutdown already in progress, ignoring duplicate call");
            return;
        }

        synchronized (this) {
            if (isShuttingDown) {
                return;
            }
            isShuttingDown = true;
        }

        try {
            LOGGER.info("Shutting down application...");

            // Step 1: Hide main controller and show shutdown dialog
            hideMainControllerAndShowShutdownDialog();

            // Step 2: Cleanup main controller to stop UI interactions and save window state
            setShutdownDialogMessageWithProgress("Cleaning up main window...", 0.2);
            if (mainController != null) {
                try {
                    LOGGER.info("Cleaning up main controller...");
                    mainController.shutdown();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error cleaning up main controller", e);
                }
            }

            // Step 3: Use centralized shutdown with coordination
            try {
                setShutdownDialogMessageWithProgress("Shutting down core services...", 0.4);
                LOGGER.info("Initiating coordinated application shutdown...");
                long startTime = System.currentTimeMillis();

                // Mark shutdown in coordinator
                ApplicationContext.getStartupCoordinator().markShutdownBegin();

                setShutdownDialogMessageWithProgress("Saving application state...", 0.6);
                ApplicationContext.shutdown();

                long duration = System.currentTimeMillis() - startTime;
                LOGGER.info("Coordinated shutdown completed successfully in " + duration + "ms");
                setShutdownDialogMessageWithProgress("Shutdown completed successfully", 0.9);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during coordinated shutdown", e);
                setShutdownDialogMessageWithProgress("Performing emergency cleanup...", 0.7);
                performEmergencyCleanup();
            }

            LOGGER.info("Application shutdown completed successfully");
            setShutdownDialogMessageWithProgress("Finalizing shutdown...", 1.0);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Critical error during shutdown", e);
            setShutdownDialogMessageWithProgress("Critical error - emergency cleanup...", 0.5);
            performEmergencyCleanup();
        } finally {
            // Give the user a moment to see the final shutdown message
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Quit GTK application
            try {
                if (mainController != null && mainController.getUI() != null) {
                    LOGGER.info("Quitting GTK application...");
                    mainController.getUI().quit();
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error quitting GTK application", e);
            }

            // Schedule graceful exit and then hide shutdown dialog
            scheduleGracefulExit();

            // Hide shutdown dialog after a brief delay
            try {
                Thread.sleep(500);
                hideStartupShutdownDialog();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                hideStartupShutdownDialog();
            }
        }
    }

    /**
     * Performs emergency cleanup when coordinated shutdown fails or times out.
     * This is a fallback mechanism that only runs when the normal
     * ShutdownCoordinator process doesn't complete successfully.
     */
    private void performEmergencyCleanup() {
        try {
            LOGGER.severe("Performing emergency cleanup due to coordinated shutdown failure...");
            LOGGER.info("Note: This indicates the ShutdownCoordinator did not complete successfully");
            setShutdownDialogMessageWithProgress("Emergency cleanup in progress...", 0.1);

            // Try to save critical state quickly before process cleanup
            if (downloadManager != null) {
                try {
                    LOGGER.info("Attempting emergency state save...");
                    setShutdownDialogMessageWithProgress("Saving critical state...", 0.3);
                    downloadManager.saveState().get(3, java.util.concurrent.TimeUnit.SECONDS);
                    LOGGER.info("Emergency state save completed successfully");
                } catch (Exception e) {
                    LOGGER.severe("Emergency state save failed: " + e.getMessage());
                }
            }

            // Emergency external process cleanup as last resort
            LOGGER.info("Performing emergency external process cleanup...");
            setShutdownDialogMessageWithProgress("Cleaning up external processes...", 0.7);
            cleanupExternalProcesses("aria2c", "curl", "yt-dlp", "youtube-dl", "httrack");

            setShutdownDialogMessageWithProgress("Emergency cleanup completed", 0.9);
            LOGGER.warning("Emergency cleanup completed - this should be investigated");

        } catch (Exception e) {
            LOGGER.severe("Emergency cleanup failed: " + e.getMessage());
            setShutdownDialogMessageWithProgress("Emergency cleanup failed", 0.5);
        }
    }

    /**
     * Emergency cleanup of external processes. Only used when coordinated
     * shutdown fails. Under normal circumstances, the ShutdownCoordinator
     * handles all process cleanup.
     */
    private void cleanupExternalProcesses(String... processNames) {
        LOGGER.severe("Emergency external process cleanup - coordinated shutdown failed!");

        for (String processName : processNames) {
            try {
                LOGGER.info("Emergency termination of " + processName + " processes...");
                Process killProcess = new ProcessBuilder("pkill", "-f", processName).start();
                boolean completed = killProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);

                if (completed) {
                    int exitCode = killProcess.exitValue();
                    if (exitCode == 0) {
                        LOGGER.info("Successfully terminated " + processName + " processes");
                    } else {
                        LOGGER.fine("No " + processName + " processes found to terminate");
                    }
                } else {
                    LOGGER.warning("Timeout while trying to terminate " + processName + " processes");
                }
            } catch (Exception e) {
                // Ignore errors - this is best effort emergency cleanup
                LOGGER.warning("Could not emergency cleanup " + processName + " processes: " + e.getMessage());
            }
        }

        LOGGER.info("Emergency external process cleanup completed");
    }

    /**
     * Schedules a graceful exit with fallback to forced exit.
     */
    private void scheduleGracefulExit() {
        Thread exitThread = new Thread(() -> {
            try {
                // Wait for a short period to allow any final cleanup
                Thread.sleep(3000);
                LOGGER.info("Graceful shutdown timeout reached, exiting application");
                System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.info("Exit thread interrupted, forcing immediate exit");
                System.exit(1);
            }
        }, "shutdown-exit-thread");

        exitThread.setDaemon(true);
        exitThread.start();
    }

    private volatile boolean isShuttingDown = false;
}
