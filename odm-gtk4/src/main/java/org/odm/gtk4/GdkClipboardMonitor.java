package org.odm.gtk4;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnome.gdk.Clipboard;
import org.gnome.gdk.Display;
import org.gnome.glib.Source;
import org.javagi.gobject.SignalConnection;
import org.manager.clipboard.ClipboardListener;
import org.manager.clipboard.ClipboardMonitor;
import org.manager.clipboard.UrlDetector;

/**
 * Toolkit-native clipboard monitor for the GTK app: subscribes to the
 * GdkClipboard "changed" signal instead of polling. Content is read only
 * when a change actually occurs, so a multi-megabyte clipboard no longer
 * gets materialized twice a second — and AWT/X11 never initializes in the
 * GTK process (the Wayland/X11 clipboard of the running session is used,
 * not a second X11 connection).
 *
 * All Gdk access is marshalled to the GTK main loop via {@link UiThread};
 * listeners are invoked on the main loop thread (they already marshal their
 * own UI work, and core's ClipboardService only touches thread-safe state).
 */
public class GdkClipboardMonitor implements ClipboardMonitor {

    private static final Logger LOGGER = Logger.getLogger(GdkClipboardMonitor.class.getName());

    private final List<ClipboardListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    /** Interval is meaningless for an event-driven monitor, but kept for the interface contract. */
    private volatile long monitoringIntervalMs = 500;
    private volatile boolean silentMode;

    private volatile SignalConnection<?> changedHandler;
    private final AtomicReference<String> lastContent = new AtomicReference<>("");
    /** GLib source ids of a pending change-processing idle, for dedup. */
    private volatile int pendingReadSource;

    @Override
    public CompletableFuture<Void> startMonitoring() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        UiThread.marshal(() -> {
            try {
                if (monitoring.compareAndSet(false, true)) {
                    Display display = Display.getDefault();
                    if (display == null) {
                        throw new IllegalStateException("No default display; GTK not initialized");
                    }
                    Clipboard clipboard = display.getClipboard();
                    changedHandler = clipboard.onChanged(() -> scheduleRead(clipboard));

                    // Seed the last-known content WITHOUT dispatching so the
                    // initial state does not fire a spurious change event on
                    // first mutation. Async on purpose: startMonitoring runs
                    // ON the GTK loop, and a blocking read would deadlock it.
                    clipboard.readTextAsync(null, res -> {
                        try {
                            lastContent.set(java.util.Objects.requireNonNullElse(
                                    clipboard.readTextFinish(res), ""));
                        } catch (Throwable ignore) {
                            // seeding is best-effort
                        }
                    });
                    LOGGER.info("GDK clipboard monitoring started (event-driven)");
                    notifyListeners(ClipboardListener::onMonitoringStarted);
                }
                future.complete(null);
            } catch (Throwable t) {
                monitoring.set(false);
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Void> stopMonitoring() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        UiThread.marshal(() -> {
            try {
                if (monitoring.compareAndSet(true, false)) {
                    if (changedHandler != null) {
                        changedHandler.disconnect();
                        changedHandler = null;
                    }
                    if (pendingReadSource != 0) {
                        Source.remove(pendingReadSource);
                        pendingReadSource = 0;
                    }
                    LOGGER.info("GDK clipboard monitoring stopped");
                    notifyListeners(ClipboardListener::onMonitoringStopped);
                }
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public boolean isMonitoring() {
        return monitoring.get();
    }

    @Override
    public void addClipboardListener(ClipboardListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeClipboardListener(ClipboardListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public void setMonitoringInterval(long intervalMs) {
        // Event-driven: no polling interval exists. Recorded so callers can
        // round-trip the setting (the AWT implementation is constructed with
        // it when the provider seam is unused).
        this.monitoringIntervalMs = Math.max(100, intervalMs);
    }

    @Override
    public long getMonitoringInterval() {
        return monitoringIntervalMs;
    }

    @Override
    public void setSilentMode(boolean silent) {
        this.silentMode = silent;
    }

    @Override
    public boolean isSilentMode() {
        return silentMode;
    }

    @Override
    public String getCurrentClipboardContent() {
        return readTextSync(null);
    }

    @Override
    public boolean containsValidUrls(String text) {
        return text != null && UrlDetector.containsUrls(text);
    }

    /** Coalesces rapid change notifications into one read per idle cycle. */
    private void scheduleRead(Clipboard clipboard) {
        if (!monitoring.get()) {
            return;
        }
        UiThread.marshal(() -> {
            if (pendingReadSource != 0 || !monitoring.get()) {
                return; // a read is already scheduled
            }
            pendingReadSource = org.gnome.glib.GLib.timeoutAdd(
                    org.gnome.glib.GLib.PRIORITY_DEFAULT, 50, () -> {
                        pendingReadSource = 0;
                        if (monitoring.get()) {
                            readTextAsync(clipboard);
                        }
                        return false; // one-shot
                    });
        });
    }

    private void readTextAsync(Clipboard clipboard) {
        try {
            clipboard.readTextAsync(null, result -> {
                try {
                    String text = clipboard.readTextFinish(result);
                    handleContent(text);
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE, "Clipboard read failed: {0}", t.getMessage());
                }
            });
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Clipboard read scheduling failed: {0}", t.getMessage());
        }
    }

    /** Test seam: drives the change-dispatch path directly. */
    void handleContentForTest(String content) {
        handleContent(content);
    }

    private void handleContent(String content) {
        String normalized = content != null ? content : "";
        if (normalized.equals(lastContent.get())) {
            return; // ownership changed but content identical
        }
        lastContent.set(normalized);

        if (normalized.isBlank()) {
            notifyListeners(l -> l.onClipboardChanged(normalized));
            return;
        }

        List<URI> urls = UrlDetector.extractUrls(normalized);
        if (!urls.isEmpty()) {
            LOGGER.info("Detected " + urls.size() + " URL(s) in clipboard (GDK)");
            notifyListeners(l -> l.onUrlsDetected(urls, normalized));
        } else {
            notifyListeners(l -> l.onClipboardChanged(normalized));
        }
    }

    /** Blocking read for interface consumers; runs on the GTK loop. */
    private String readTextSync(Clipboard preconfigured) {
        // Never block the main loop itself: awaiting our own idle from the
        // loop thread would deadlock (until the 5s timeout degraded it)
        if (org.gnome.glib.MainContext.default_().isOwner()) {
            return null; // called from the GTK thread; unsupported
        }
        try {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>();
            UiThread.marshal(() -> {
                try {
                    Clipboard clipboard = preconfigured != null ? preconfigured
                            : Display.getDefault().getClipboard();
                    clipboard.readTextAsync(null, res -> {
                        try {
                            result.set(clipboard.readTextFinish(res));
                        } catch (Throwable t) {
                            result.set(null);
                        } finally {
                            done.countDown();
                        }
                    });
                } catch (Throwable t) {
                    done.countDown();
                }
            });
            done.await(5, TimeUnit.SECONDS);
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public CompletableFuture<Void> checkClipboardNow() {
        // Event-driven source of truth: a forced check simply re-reads and
        // dispatches if the content differs from the last seen
        return CompletableFuture.runAsync(() -> {
            String text = readTextSync(null);
            handleContent(text);
        });
    }

    private void notifyListeners(java.util.function.Consumer<ClipboardListener> action) {
        for (ClipboardListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying clipboard listener", e);
            }
        }
    }
}
