package org.odm.ui;

import java.util.logging.Logger;

import org.jgtk.GladeUI;

import com.sun.jna.Pointer;

/**
 * Base class for main window controllers providing common functionality.
 * Implements common window handling patterns, UI setup, and lifecycle
 * management for main application windows.
 */
public abstract class BaseWindow {

    private static final Logger LOGGER = Logger.getLogger(BaseWindow.class.getName());

    protected GladeUI ui;
    protected boolean isInitialized = false;
    protected boolean isShutdown = false;

    /**
     * Resource path to the Glade UI definition file.
     * Must be overridden by subclasses.
     */
    protected abstract String getGladeResourcePath();

    /**
     * Name of the main window widget.
     * Must be overridden by subclasses.
     */
    protected abstract String getWindowName();

    /**
     * Initialize window with default values and settings.
     * Should be implemented by subclasses.
     */
    protected abstract void initializeWindow();

    /**
     * Setup signal handlers for the window.
     * Should be implemented by subclasses.
     */
    protected abstract void setupSignalHandlers();

    /**
     * Initialize the window and load UI resources.
     * Handles common window setup.
     *
     * @return true if initialization was successful, false otherwise
     */
    public boolean initialize() {
        try {
            if (isInitialized) {
                LOGGER.warning("Window already initialized");
                return true;
            }

            ui = new GladeUI();
            boolean loaded = ui.loadFromResource(getGladeResourcePath());
            if (!loaded) {
                throw new RuntimeException("Failed to load Glade file: " + getGladeResourcePath());
            }

            // Set up window
            setupSignalHandlers();
            initializeWindow();

            isInitialized = true;
            LOGGER.info("Window initialized successfully: " + getClass().getSimpleName());
            return true;

        } catch (Exception e) {
            LOGGER.severe("Error initializing window: " + e.getMessage());
            return false;
        }
    }

    /**
     * Shows the window.
     */
    public void show() {
        if (ui != null && isInitialized) {
            ui.showAll(getWindowName());
        }
    }

    /**
     * Hides the window.
     */
    public void hide() {
        if (ui != null && isInitialized) {
            ui.hide(getWindowName());
        }
    }

    /**
     * Gets the main window widget pointer.
     *
     * @return window widget pointer or null if not initialized
     */
    public Pointer getWindow() {
        if (ui != null && isInitialized) {
            return ui.getWidget(getWindowName());
        }
        return null;
    }

    /**
     * Gets the UI instance.
     *
     * @return GladeUI instance or null if not initialized
     */
    public GladeUI getUI() {
        return ui;
    }

    /**
     * Checks if the window is initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Checks if the window is in shutdown state.
     *
     * @return true if shutdown, false otherwise
     */
    public boolean isShutdown() {
        return isShutdown;
    }

    /**
     * Performs cleanup when the window is closed.
     * Should be overridden by subclasses to perform additional cleanup.
     */
    protected void cleanup() {
        try {
            if (isShutdown) {
                return;
            }

            isShutdown = true;

            if (ui != null) {
                ui.destroy();
                ui = null;
            }

            isInitialized = false;
            LOGGER.info("Window cleanup completed for " + getClass().getSimpleName());

        } catch (Exception e) {
            LOGGER.severe("Error during cleanup: " + e.getMessage());
        }
    }

    /**
     * Initiates window shutdown process.
     * Can be overridden by subclasses to perform pre-shutdown actions.
     */
    public void shutdown() {
        cleanup();
    }
}
