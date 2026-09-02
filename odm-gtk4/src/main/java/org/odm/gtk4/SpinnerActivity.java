package org.odm.gtk4;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.gnome.gtk.Spinner;

/** Keeps a GTK spinner active until every overlapping background task ends. */
final class SpinnerActivity {

    private final Consumer<Boolean> presentation;
    private int active;
    private boolean disposed;

    SpinnerActivity(Spinner spinner) {
        this(spinning -> UiThread.marshal(() -> spinner.setSpinning(spinning)));
    }

    /** Test seam which also keeps the activity accounting GTK-independent. */
    SpinnerActivity(Consumer<Boolean> presentation) {
        this.presentation = Objects.requireNonNull(presentation, "presentation");
    }

    /** Starts one activity and returns an idempotent completion callback. */
    synchronized Runnable begin() {
        if (disposed) {
            return () -> { };
        }
        active++;
        if (active == 1) {
            presentation.accept(true);
        }
        AtomicBoolean completed = new AtomicBoolean();
        return () -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            completeOne();
        };
    }

    private synchronized void completeOne() {
        if (active > 0) {
            active--;
        }
        if (active == 0 && !disposed) {
            presentation.accept(false);
        }
    }

    /** Tracks a future without changing its result or exception. */
    <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        Objects.requireNonNull(future, "future");
        Runnable completed = begin();
        future.whenComplete((ignored, error) -> completed.run());
        return future;
    }

    synchronized int activeCount() {
        return active;
    }

    synchronized void dispose() {
        if (!disposed) {
            disposed = true;
            active = 0;
            presentation.accept(false);
        }
    }
}
