package org.odm.gtk4;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.gnome.glib.MainLoop;
import org.gnome.gtk.Gtk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.clipboard.ClipboardListener;

/**
 * Lifecycle contract for the event-driven GTK clipboard monitor: it must
 * subscribe to the session clipboard on start (without touching AWT),
 * deliver change events with detected URLs, and detach cleanly on stop.
 * Requires a display (Xvfb in the Docker test env) and a running GLib main
 * loop, which this test drives on a dedicated thread.
 */
@DisplayName("GdkClipboardMonitor lifecycle")
class GdkClipboardMonitorSmokeTest {

    private static MainLoop loop;
    private static ExecutorService loopThread;

    @BeforeAll
    static void initGtk() {
        Gtk.init();
        loop = new MainLoop(null, false);
        loopThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-glib-loop");
            t.setDaemon(true);
            return t;
        });
        loopThread.submit(loop::run);
    }

    @AfterAll
    static void tearDown() {
        loop.quit();
        loopThread.shutdownNow();
    }

    /** Runs an assertion block on the loop thread and reports the outcome. */
    private static void onLoop(Runnable block)
            throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        UiThread.marshal(() -> {
            try {
                block.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(40)
    @DisplayName("start subscribes, change dispatches URLs, stop detaches")
    void startChangeStopCycle() throws Exception {
        GdkClipboardMonitor monitor = new GdkClipboardMonitor();

        CountDownLatch urlSeen = new CountDownLatch(1);
        AtomicReference<String> seenContent = new AtomicReference<>();
        monitor.addClipboardListener(new ClipboardListener() {
            @Override
            public void onUrlsDetected(java.util.List<java.net.URI> urls, String clipboardContent) {
                seenContent.set(clipboardContent);
                urlSeen.countDown();
            }
        });

        // Start (as the core service does, from a non-loop thread)
        monitor.startMonitoring().get(10, TimeUnit.SECONDS);
        assertTrue(monitor.isMonitoring(), "monitor must be active after start");

        // Simulate a clipboard change by invoking the dispatch path with a
        // URL-bearing payload (driving the real X11 selection would need an
        // external setter; handleContent is the unit under contract)
        onLoop(() -> monitor.handleContentForTest("http://example.test/file.bin"));

        assertTrue(urlSeen.await(10, TimeUnit.SECONDS), "URL change must be dispatched");
        assertEquals("http://example.test/file.bin", seenContent.get());

        monitor.stopMonitoring().get(10, TimeUnit.SECONDS);
        assertFalse(monitor.isMonitoring(), "monitor must be inactive after stop");

        assertDoesNotThrow(() -> onLoop(() -> monitor.handleContentForTest("http://after-stop.test/x")));
    }
}
