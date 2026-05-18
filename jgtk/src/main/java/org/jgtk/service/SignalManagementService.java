package org.jgtk.service;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.jgtk.core.GtkCallbacks;
import org.jgtk.core.GtkNativeLibraries;

import com.sun.jna.Callback;
import com.sun.jna.Pointer;

/**
 * Enhanced service for managing GTK signal connections and handlers.
 * Provides both legacy 2-parameter callbacks and enhanced callbacks with full
 * signal parameters.
 * Maintains backward compatibility while adding support for advanced signal
 * handling.
 */
public class SignalManagementService {

    private static final Logger LOGGER = Logger.getLogger(SignalManagementService.class.getName());

    private final Map<String, GtkCallbacks.GtkCallback> signalHandlers = new HashMap<>();
    private final Map<String, Callback> enhancedHandlers = new HashMap<>();
    private final Pointer builder;

    /**
     * Creates a new signal management service.
     *
     * @param builder the GTK builder to use for automatic signal connection
     */
    public SignalManagementService(Pointer builder) {
        this.builder = builder;
    }

    /**
     * Registers a legacy signal handler with the specified name.
     * This handler will be automatically connected when connectSignals() is called.
     *
     * @param handlerName the name of the handler (as specified in the Glade file)
     * @param callback    the callback to execute when the signal is triggered
     */
    public void registerHandler(String handlerName, GtkCallbacks.GtkCallback callback) {
        if (handlerName == null || handlerName.isBlank()) {
            LOGGER.warning("Signal handler registration failed: handler name is null or empty");
            return;
        }

        if (callback == null) {
            LOGGER.warning("Signal handler registration failed: callback is null for handler '" + handlerName + "'");
            return;
        }

        signalHandlers.put(handlerName.trim(), callback);
        LOGGER.fine(() -> "Successfully registered legacy signal handler: '" + handlerName +
                "' (total handlers: " + (signalHandlers.size() + enhancedHandlers.size()) + ")");
    }

    /**
     * Registers an enhanced signal handler with the specified name.
     * This handler will be automatically connected when connectSignals() is called.
     *
     * @param handlerName the name of the handler (as specified in the Glade file)
     * @param callback    the enhanced callback to execute when the signal is
     *                    triggered
     */
    public void registerEnhancedHandler(String handlerName, Callback callback) {
        if (handlerName == null || handlerName.isBlank()) {
            LOGGER.warning("Enhanced signal handler registration failed: handler name is null or empty");
            return;
        }

        if (callback == null) {
            LOGGER.warning(
                    "Enhanced signal handler registration failed: callback is null for handler '" + handlerName + "'");
            return;
        }

        enhancedHandlers.put(handlerName.trim(), callback);
        LOGGER.fine(() -> "Successfully registered enhanced signal handler: '" + handlerName +
                "' (total handlers: " + (signalHandlers.size() + enhancedHandlers.size()) + ")");
    }

    /**
     * Convenience method to register a signal handler using a Runnable.
     *
     * @param handlerName the name of the handler (as specified in the Glade file)
     * @param action      the action to execute when the signal is triggered
     */
    public void registerHandler(String handlerName, Runnable action) {
        if (action == null) {
            LOGGER.warning("Signal handler registration failed: action is null for handler '" + handlerName + "'");
            return;
        }

        registerHandler(handlerName, (instance, data) -> {
            try {
                action.run();
            } catch (Exception e) {
                LOGGER.severe("Signal handler execution failed for '" + handlerName + "': " +
                        e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        });
    }

    /**
     * Connects all registered signal handlers to their corresponding widgets.
     * This must be called after registering handlers and before showing widgets.
     */
    public void connectSignals() {
        if (builder == null) {
            LOGGER.warning("Signal connection failed: no GTK builder available");
            return;
        }

        int totalHandlers = signalHandlers.size() + enhancedHandlers.size();
        if (totalHandlers == 0) {
            LOGGER.info("Signal connection skipped: no signal handlers registered");
            return;
        }

        try {
            var connector = new SignalConnector();
            GtkNativeLibraries.GtkBuilderSignals.INSTANCE.gtk_builder_connect_signals_full(
                    builder, connector, null);
            LOGGER.fine(() -> "Successfully connected " + totalHandlers + " signal handlers to GTK widgets " +
                    "(" + signalHandlers.size() + " legacy, " + enhancedHandlers.size() + " enhanced)");
        } catch (Exception e) {
            LOGGER.severe("Signal connection failed with exception: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage() +
                    " (attempted to connect " + totalHandlers + " handlers)");
        }
    }

    /**
     * Connects a signal directly to a widget.
     *
     * @param widget     the widget to connect the signal to
     * @param signalName the name of the signal (e.g., "clicked", "destroy")
     * @param callback   the callback to execute
     */
    public void connectSignal(Pointer widget, String signalName, GtkCallbacks.GtkCallback callback) {
        if (widget == null) {
            LOGGER.warning("Widget is null for signal: " + signalName);
            return;
        }

        if (signalName == null || signalName.isBlank()) {
            LOGGER.warning("Signal name is null or empty");
            return;
        }

        if (callback == null) {
            LOGGER.warning("Callback is null for signal: " + signalName);
            return;
        }

        try {
            GtkNativeLibraries.GObjectLib.INSTANCE.g_signal_connect_data(
                    widget, signalName, callback, null, null, 0);
            LOGGER.fine("Connected signal: " + signalName);
        } catch (Exception e) {
            LOGGER.severe("Failed to connect signal " + signalName + ": " + e.getMessage());
        }
    }

    /**
     * Connects an enhanced signal directly to a widget.
     * Note: This uses the same underlying mechanism as legacy signals,
     * but the enhanced callbacks will receive the full parameter set.
     *
     * @param widget     the widget to connect the signal to
     * @param signalName the name of the signal (e.g., "switch-page",
     *                   "value-changed")
     * @param callback   the enhanced callback to execute
     */
    public void connectEnhancedSignal(Pointer widget, String signalName, Callback callback) {
        if (widget == null) {
            LOGGER.warning("Widget is null for signal: " + signalName);
            return;
        }

        if (signalName == null || signalName.isBlank()) {
            LOGGER.warning("Signal name is null or empty");
            return;
        }

        if (callback == null) {
            LOGGER.warning("Enhanced callback is null for signal: " + signalName);
            return;
        }

        try {
            // GTK will automatically provide the correct parameters based on the signal
            // type
            // The enhanced callback interface must match the expected GTK signal signature
            GtkNativeLibraries.GObjectLib.INSTANCE.g_signal_connect_data(
                    widget, signalName, (GtkCallbacks.GtkCallback) callback, null, null, 0);
            LOGGER.fine("Connected enhanced signal: " + signalName + " with " + callback.getClass().getSimpleName());
        } catch (ClassCastException e) {
            LOGGER.severe("Failed to connect enhanced signal " + signalName +
                    ": callback does not implement GtkCallback interface");
        } catch (Exception e) {
            LOGGER.severe("Failed to connect enhanced signal " + signalName + ": " + e.getMessage());
        }
    }

    /**
     * Gets the number of registered signal handlers.
     *
     * @return the number of registered handlers
     */
    public int getHandlerCount() {
        return signalHandlers.size() + enhancedHandlers.size();
    }

    /**
     * Gets the number of legacy signal handlers.
     *
     * @return the number of legacy handlers
     */
    public int getLegacyHandlerCount() {
        return signalHandlers.size();
    }

    /**
     * Gets the number of enhanced signal handlers.
     *
     * @return the number of enhanced handlers
     */
    public int getEnhancedHandlerCount() {
        return enhancedHandlers.size();
    }

    /**
     * Checks if a handler is registered with the given name.
     *
     * @param handlerName the name of the handler
     * @return true if the handler is registered, false otherwise
     */
    public boolean hasHandler(String handlerName) {
        return signalHandlers.containsKey(handlerName) || enhancedHandlers.containsKey(handlerName);
    }

    /**
     * Removes a registered signal handler.
     *
     * @param handlerName the name of the handler to remove
     * @return true if the handler was removed, false if it wasn't registered
     */
    public boolean removeHandler(String handlerName) {
        boolean removedLegacy = signalHandlers.remove(handlerName) != null;
        boolean removedEnhanced = enhancedHandlers.remove(handlerName) != null;
        boolean removed = removedLegacy || removedEnhanced;

        if (removed) {
            LOGGER.fine("Removed signal handler: " + handlerName +
                    (removedLegacy ? " (legacy)" : " (enhanced)"));
        }
        return removed;
    }

    /**
     * Clears all registered signal handlers.
     */
    public void clearHandlers() {
        int totalCount = signalHandlers.size() + enhancedHandlers.size();
        signalHandlers.clear();
        enhancedHandlers.clear();
        LOGGER.fine("Cleared " + totalCount + " signal handlers");
    }

    /**
     * Enhanced inner class that implements the builder connect callback for
     * automatic signal
     * connection with support for both legacy and enhanced handlers.
     * This follows the same pattern as the original gladedemo implementation.
     */
    private class SignalConnector implements GtkCallbacks.BuilderConnectCallback {

        @Override
        public void invoke(Pointer builder, Pointer object, String signalName,
                String handlerName, Pointer connectObject, int flags, Pointer userData) {

            if (handlerName == null || handlerName.trim().isEmpty()) {
                return;
            }

            String trimmedHandlerName = handlerName.trim();

            // Try enhanced handlers first
            Callback enhancedCallback = enhancedHandlers.get(trimmedHandlerName);
            if (enhancedCallback != null) {
                try {
                    GtkNativeLibraries.GObjectLib.INSTANCE.g_signal_connect_data(
                            object, signalName, (GtkCallbacks.GtkCallback) enhancedCallback, null, null, flags);
                    LOGGER.finest("Auto-connected enhanced signal: " + signalName + " -> " + handlerName);
                    return;
                } catch (ClassCastException e) {
                    LOGGER.warning("Enhanced handler " + handlerName + " does not implement proper callback interface");
                } catch (Exception e) {
                    LOGGER.severe("Failed to auto-connect enhanced signal " + signalName +
                            " to handler " + handlerName + ": " + e.getMessage());
                    return;
                }
            }

            // Fall back to legacy handlers
            GtkCallbacks.GtkCallback legacyCallback = signalHandlers.get(trimmedHandlerName);
            if (legacyCallback != null) {
                try {
                    GtkNativeLibraries.GObjectLib.INSTANCE.g_signal_connect_data(
                            object, signalName, legacyCallback, null, null, flags);
                    LOGGER.finest("Auto-connected legacy signal: " + signalName + " -> " + handlerName);
                } catch (Exception e) {
                    LOGGER.severe("Failed to auto-connect legacy signal " + signalName +
                            " to handler " + handlerName + ": " + e.getMessage());
                }
                return;
            }

            LOGGER.warning("No handler registered for: " + handlerName + " (signal: " + signalName + ")");
        }
    }
}
