package org.odm.gtk4;

import java.util.concurrent.atomic.AtomicInteger;
import org.gnome.glib.MainContext;
import org.gnome.gtk.Gtk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmarks the single core-to-GTK marshal point ({@link UiThread}): how
 * fast a worker-thread event reaches the GTK main loop. This is the path
 * every download progress callback travels, so its latency bounds UI
 * responsiveness under load. Requires a display (Xvfb in Docker).
 */
@Tag("performance")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("UI thread marshal performance")
class UiThreadMarshalPerfTest {

    @BeforeAll
    static void initGtk() {
        Gtk.init();
    }

    private static void drainUntil(java.util.function.BooleanSupplier done, long timeoutMillis,
            String what) {
        MainContext context = MainContext.default_();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!done.getAsBoolean()) {
            while (context.pending()) {
                context.iteration(false);
            }
            if (done.getAsBoolean()) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for GTK idle tasks: " + what);
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while draining GTK events", e);
            }
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("Sequential marshal round-trip latency")
    void sequentialRoundTrips() {
        int tasks = UiPerf.scale(200);
        long totalLatency = 0;
        for (int i = 0; i < tasks; i++) {
            AtomicInteger done = new AtomicInteger();
            long start = System.nanoTime();
            UiThread.marshal(done::incrementAndGet);
            drainUntil(() -> done.get() > 0, 5_000, "sequential task " + i);
            totalLatency += System.nanoTime() - start;
        }
        UiPerf.report("UiThread", "marshal-sequential", tasks, totalLatency, "");
        assertTrue(totalLatency < 60_000_000_000L,
                "sequential marshals took " + totalLatency / 1_000_000 + "ms");
    }

    @Test
    @Timeout(120)
    @DisplayName("Burst of 500 marshalled events drains cleanly")
    void burstDrain() {
        int tasks = UiPerf.scale(500);
        AtomicInteger done = new AtomicInteger();
        long enqueueStart = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            UiThread.marshal(done::incrementAndGet);
        }
        long enqueueElapsed = System.nanoTime() - enqueueStart;
        UiPerf.report("UiThread", "marshal-enqueue-burst", tasks, enqueueElapsed, "");

        long drainStart = System.nanoTime();
        drainUntil(() -> done.get() >= tasks, 15_000, "burst of " + tasks);
        long drainElapsed = System.nanoTime() - drainStart;
        assertEquals(tasks, done.get());
        UiPerf.report("UiThread", "marshal-drain-burst", tasks, drainElapsed, "");

        assertTrue(enqueueElapsed + drainElapsed < 60_000_000_000L,
                "burst took " + (enqueueElapsed + drainElapsed) / 1_000_000 + "ms");
    }
}
