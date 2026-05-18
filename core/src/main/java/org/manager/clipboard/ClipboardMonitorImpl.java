package org.manager.clipboard;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of ClipboardMonitor using Java's AWT Clipboard API. Monitors
 * the system clipboard for changes and detects URLs in the content.
 *
 * This implementation uses polling to check for clipboard changes since Java's
 * Clipboard API doesn't provide native change notifications.
 */
public class ClipboardMonitorImpl implements ClipboardMonitor {

    private static final Logger LOGGER = Logger.getLogger(ClipboardMonitorImpl.class.getName());

    private final Clipboard systemClipboard;
    private final List<ClipboardListener> listeners;
    private final AtomicBoolean monitoring;
    private final AtomicReference<String> lastClipboardContent;
    private final AtomicBoolean silentMode;

    private ScheduledExecutorService scheduledExecutor;
    private ScheduledFuture<?> monitoringTask;
    private long monitoringInterval = 500; // Default 500ms

    /**
     * Creates a new clipboard monitor instance.
     */
    public ClipboardMonitorImpl() {
        this.systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        this.listeners = new CopyOnWriteArrayList<>();
        this.monitoring = new AtomicBoolean(false);
        this.lastClipboardContent = new AtomicReference<>("");
        this.silentMode = new AtomicBoolean(false);

        LOGGER.info("ClipboardMonitor initialized");
    }

    @Override
    public CompletableFuture<Void> startMonitoring() {
        return CompletableFuture.runAsync(() -> {
            if (monitoring.compareAndSet(false, true)) {
                LOGGER.info("Starting clipboard monitoring with interval: " + monitoringInterval + "ms");

                // Initialize the executor if not already created
                if (scheduledExecutor == null || scheduledExecutor.isShutdown()) {
                    scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "ClipboardMonitor");
                        t.setDaemon(true);
                        return t;
                    });
                }

                // Initialize with current clipboard content to avoid false positives
                String initialContent = getCurrentClipboardContent();
                lastClipboardContent.set(initialContent != null ? initialContent : "");

                // Start the monitoring task
                monitoringTask = scheduledExecutor.scheduleWithFixedDelay(
                        this::checkClipboardContent,
                        0,
                        monitoringInterval,
                        TimeUnit.MILLISECONDS);

                // Notify listeners
                notifyListeners(listener -> listener.onMonitoringStarted());

                LOGGER.info("Clipboard monitoring started successfully");
            } else {
                LOGGER.warning("Clipboard monitoring is already active");
            }
        });
    }

    @Override
    public CompletableFuture<Void> stopMonitoring() {
        return CompletableFuture.runAsync(() -> {
            if (monitoring.compareAndSet(true, false)) {
                LOGGER.info("Stopping clipboard monitoring");

                // Cancel the monitoring task
                if (monitoringTask != null && !monitoringTask.isCancelled()) {
                    monitoringTask.cancel(false);
                    monitoringTask = null;
                }

                // Shutdown the executor
                if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
                    scheduledExecutor.shutdown();
                    try {
                        if (!scheduledExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                            scheduledExecutor.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        scheduledExecutor.shutdownNow();
                    }
                }

                // Notify listeners
                notifyListeners(listener -> listener.onMonitoringStopped());

                LOGGER.info("Clipboard monitoring stopped successfully");
            } else {
                LOGGER.warning("Clipboard monitoring is not currently active");
            }
        });
    }

    @Override
    public boolean isMonitoring() {
        return monitoring.get();
    }

    @Override
    public void addClipboardListener(ClipboardListener listener) {
        if (listener != null) {
            listeners.add(listener);
            LOGGER.fine("Added clipboard listener: " + listener.getClass().getSimpleName());
        }
    }

    @Override
    public void removeClipboardListener(ClipboardListener listener) {
        if (listener != null) {
            listeners.remove(listener);
            LOGGER.fine("Removed clipboard listener: " + listener.getClass().getSimpleName());
        }
    }

    @Override
    public void setMonitoringInterval(long intervalMs) {
        if (intervalMs < 100) {
            throw new IllegalArgumentException("Monitoring interval must be at least 100ms");
        }

        long oldInterval = this.monitoringInterval;
        this.monitoringInterval = intervalMs;

        LOGGER.info("Monitoring interval changed from " + oldInterval + "ms to " + intervalMs + "ms");

        // If monitoring is active, restart with new interval
        if (monitoring.get()) {
            LOGGER.info("Restarting monitoring with new interval");
            stopMonitoring().thenRun(() -> startMonitoring());
        }
    }

    @Override
    public long getMonitoringInterval() {
        return monitoringInterval;
    }

    @Override
    public String getCurrentClipboardContent() {
        try {
            if (!systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return null;
            }

            Transferable contents = systemClipboard.getContents(null);
            if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
                return text != null ? text.trim() : null;
            }
        } catch (UnsupportedFlavorException | IOException | IllegalStateException e) {
            LOGGER.log(Level.FINE, "Error reading clipboard content: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public boolean containsValidUrls(String text) {
        return UrlDetector.containsUrls(text);
    }

    @Override
    public void setSilentMode(boolean silent) {
        boolean oldValue = silentMode.getAndSet(silent);
        if (oldValue != silent) {
            LOGGER.info("Silent mode " + (silent ? "enabled" : "disabled"));
        }
    }

    @Override
    public boolean isSilentMode() {
        return silentMode.get();
    }

    @Override
    public CompletableFuture<Void> checkClipboardNow() {
        return CompletableFuture.runAsync(this::checkClipboardContent);
    }

    /**
     * Checks the current clipboard content and processes any changes. This
     * method is called periodically by the monitoring task.
     */
    private void checkClipboardContent() {
        try {
            String currentContent = getCurrentClipboardContent();
            String lastContent = lastClipboardContent.get();

            // Skip if content hasn't changed
            if (isSameContent(currentContent, lastContent)) {
                return;
            }

            // Update the last known content
            lastClipboardContent.set(currentContent != null ? currentContent : "");

            LOGGER.fine("Clipboard content changed");

            // Process the new content
            processClipboardContent(currentContent);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during clipboard check", e);
            notifyListeners(listener -> listener.onClipboardError(e));
        }
    }

    /**
     * Processes clipboard content and notifies listeners of any detected URLs.
     *
     * @param content The clipboard content to process
     */
    private void processClipboardContent(String content) {
        ClipboardEvent.ClipboardEventType eventType;
        final List<URI> detectedUrls = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            eventType = ClipboardEvent.ClipboardEventType.CONTENT_CLEARED;
        } else {
            detectedUrls.addAll(UrlDetector.extractUrls(content));
            eventType = detectedUrls.isEmpty()
                    ? ClipboardEvent.ClipboardEventType.CONTENT_CHANGED
                    : ClipboardEvent.ClipboardEventType.URLS_DETECTED;
        }

        // Create clipboard event
        ClipboardEvent event = new ClipboardEvent(content, detectedUrls, eventType);

        // Notify listeners based on event type
        if (event.hasUrls()) {
            LOGGER.info("Detected " + detectedUrls.size() + " URL(s) in clipboard");
            notifyListeners(listener -> listener.onUrlsDetected(detectedUrls, content));
        } else if (content != null) {
            notifyListeners(listener -> listener.onClipboardChanged(content));
        }
    }

    /**
     * Checks if two clipboard contents are the same.
     *
     * @param content1 First content
     * @param content2 Second content
     * @return true if contents are the same
     */
    private boolean isSameContent(String content1, String content2) {
        if (content1 == null && content2 == null) {
            return true;
        }
        if (content1 == null || content2 == null) {
            return false;
        }
        return content1.equals(content2);
    }

    /**
     * Notifies all listeners with the given action.
     *
     * @param action The action to perform on each listener
     */
    private void notifyListeners(ListenerAction action) {
        for (ClipboardListener listener : listeners) {
            try {
                action.perform(listener);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying clipboard listener", e);
            }
        }
    }

    /**
     * Functional interface for listener notification actions.
     */
    @FunctionalInterface
    private interface ListenerAction {

        void perform(ClipboardListener listener);
    }

    /**
     * Cleanup method to be called when the monitor is no longer needed. This
     * ensures proper resource cleanup.
     */
    public void cleanup() {
        if (monitoring.get()) {
            stopMonitoring().join();
        }
        listeners.clear();
        LOGGER.info("ClipboardMonitor cleanup completed");
    }

    /**
     * Sets the clipboard content programmatically. This is useful for testing
     * or programmatic clipboard manipulation.
     *
     * @param text The text to set in the clipboard
     */
    public void setClipboardContent(String text) {
        try {
            StringSelection selection = new StringSelection(text != null ? text : "");
            systemClipboard.setContents(selection, null);
            LOGGER.fine("Clipboard content set programmatically");
        } catch (IllegalStateException e) {
            LOGGER.log(Level.WARNING, "Error setting clipboard content", e);
        }
    }

    /**
     * Gets statistics about the clipboard monitor.
     *
     * @return A string containing monitor statistics
     */
    public String getStatistics() {
        return """
                ClipboardMonitor Statistics:
                  Monitoring: %s
                  Interval: %dms
                  Silent Mode: %s
                  Listeners: %d
                  Last Content Length: %d""".formatted(
                monitoring.get(),
                monitoringInterval,
                silentMode.get(),
                listeners.size(),
                lastClipboardContent.get().length());
    }
}
