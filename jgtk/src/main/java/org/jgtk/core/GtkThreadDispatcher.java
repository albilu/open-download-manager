package org.jgtk.core;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Thread-safe dispatcher for GTK operations.
 * Ensures all GTK operations are executed on the main thread to prevent
 * SIGSEGV crashes that occur when GTK is accessed from background threads.
 */
public class GtkThreadDispatcher {
    private static final Logger LOGGER = Logger.getLogger(GtkThreadDispatcher.class.getName());

    private static final ConcurrentLinkedQueue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private static volatile Thread gtkMainThread;

    private GtkThreadDispatcher() {
        // Utility class
    }

    /**
     * Sets the GTK main thread reference.
     * Should be called during GTK initialization.
     */
    public static void setGtkMainThread(Thread thread) {
        gtkMainThread = thread;
        LOGGER.fine("GTK main thread set to: " + thread.getName());
    }

    /**
     * Checks if the current thread is the GTK main thread.
     */
    public static boolean isGtkMainThread() {
        return Thread.currentThread() == gtkMainThread;
    }

    /**
     * Executes a task on the GTK main thread.
     * If already on the main thread, executes immediately.
     * Otherwise, schedules for execution on the main thread.
     */
    public static void invokeLater(Runnable task) {
        if (task == null) {
            return;
        }

        // If we're already on the GTK main thread, execute immediately
        if (isGtkMainThread()) {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.severe("Error executing GTK task on main thread: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        // Schedule for execution on the main thread
        taskQueue.offer(task);

        // Trigger processing if not already processing
        if (!isProcessing.getAndSet(true)) {
            scheduleProcessing();
        }
    }

    /**
     * Executes a task synchronously on the GTK main thread.
     * WARNING: This can cause deadlocks if called from the main thread.
     * Use invokeLater() instead when possible.
     */
    public static void invokeAndWait(Runnable task) {
        if (task == null) {
            return;
        }

        // If we're already on the GTK main thread, execute immediately
        if (isGtkMainThread()) {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.severe("Error executing GTK task on main thread: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        // Use a synchronization object to wait for completion
        final Object sync = new Object();
        final Exception[] exception = new Exception[1];

        synchronized (sync) {
            invokeLater(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    exception[0] = e;
                } finally {
                    synchronized (sync) {
                        sync.notify();
                    }
                }
            });

            try {
                sync.wait(5000); // 5 second timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warning("Thread interrupted while waiting for GTK task execution");
            }
        }

        if (exception[0] != null) {
            LOGGER.severe("Error executing GTK task synchronously: " + exception[0].getMessage());
            exception[0].printStackTrace();
        }
    }

    /**
     * Processes all pending GTK tasks.
     * This should be called periodically from the main thread.
     */
    public static void processPendingTasks() {
        if (!isGtkMainThread()) {
            LOGGER.warning("processPendingTasks() called from non-main thread");
            return;
        }

        isProcessing.set(true);
        try {
            Runnable task;
            while ((task = taskQueue.poll()) != null) {
                try {
                    task.run();
                } catch (Exception e) {
                    LOGGER.severe("Error executing queued GTK task: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } finally {
            isProcessing.set(false);
        }
    }

    /**
     * Schedules processing of pending tasks by triggering a GTK idle callback.
     */
    private static void scheduleProcessing() {
        try {
            // Use g_idle_add to schedule processing on the main thread
            GtkNativeLibraries.GLib.INSTANCE.g_idle_add(new GtkNativeLibraries.GCallback() {
                @Override
                public boolean callback(com.sun.jna.Pointer data) {
                    processPendingTasks();
                    return false; // Remove the idle source after execution
                }
            }, null);
        } catch (Exception e) {
            LOGGER.severe("Failed to schedule GTK task processing: " + e.getMessage());
            e.printStackTrace();
            // Fallback: set processing to false so future tasks can try again
            isProcessing.set(false);
        }
    }
}
