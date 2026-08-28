package org.manager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates graceful shutdown of all components in the download manager. This
 * class ensures that all resources are properly cleaned up and all operations
 * are completed or safely terminated during shutdown.
 */
public class ShutdownCoordinator {

    private static final Logger LOGGER = Logger.getLogger(ShutdownCoordinator.class.getName());
    private static final long DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 60;

    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean isShutdownComplete = new AtomicBoolean(false);
    /** Single-flight guard for {@link #performShutdown()}. */
    private final AtomicBoolean shutdownEntered = new AtomicBoolean(false);
    /** Single-removal guard for the JVM hook. */
    private final AtomicBoolean jvmHookRemoved = new AtomicBoolean(false);
    private final List<ShutdownHook> shutdownHooks = new ArrayList<>();
    private final Object hooksLock = new Object();
    private final ExecutorService shutdownExecutor;
    private final long shutdownTimeoutSeconds;
    private final Thread jvmHook;

    /** Live coordinator generations whose JVM hook is still registered. */
    private static final AtomicInteger LIVE_JVM_HOOKS = new AtomicInteger();

    /**
     * Represents a shutdown hook with priority and timeout.
     */
    public static class ShutdownHook {

        private final String name;
        private final Runnable task;
        private final int priority;
        private final long timeoutSeconds;
        private final boolean essential;

        /**
         * Creates a new shutdown hook.
         *
         * @param name           A descriptive name for the hook
         * @param task           The task to execute during shutdown
         * @param priority       Priority (higher values execute first)
         * @param timeoutSeconds Maximum time to wait for this hook
         * @param essential      Whether this hook is essential (shutdown fails if it
         *                       times out)
         */
        public ShutdownHook(String name, Runnable task, int priority, long timeoutSeconds, boolean essential) {
            this.name = name;
            this.task = task;
            this.priority = priority;
            this.timeoutSeconds = timeoutSeconds;
            this.essential = essential;
        }

        /**
         * Creates a new essential shutdown hook with default timeout.
         *
         * @param name     A descriptive name for the hook
         * @param task     The task to execute during shutdown
         * @param priority Priority (higher values execute first)
         */
        public ShutdownHook(String name, Runnable task, int priority) {
            this(name, task, priority, 30, true);
        }

        public String getName() {
            return name;
        }

        public Runnable getTask() {
            return task;
        }

        public int getPriority() {
            return priority;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public boolean isEssential() {
            return essential;
        }
    }

    /**
     * Shutdown phases executed in order.
     */
    public enum ShutdownPhase {
        PREPARE(1000, "Preparation phase - stop accepting new work"),
        DOWNLOADS(900, "Downloads phase - complete or cancel active downloads"),
        SERVICES(800, "Services phase - shutdown download services"),
        EXECUTORS(700, "Executors phase - shutdown thread pools"),
        RESOURCES(600, "Resources phase - release system resources"),
        PERSISTENCE(500, "Persistence phase - save state and cleanup files"),
        CLEANUP(400, "Cleanup phase - final cleanup operations");

        private final int priority;
        private final String description;

        ShutdownPhase(int priority, String description) {
            this.priority = priority;
            this.description = description;
        }

        public int getPriority() {
            return priority;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Creates a new ShutdownCoordinator with default timeout.
     */
    public ShutdownCoordinator() {
        this(DEFAULT_SHUTDOWN_TIMEOUT_SECONDS);
    }

    /**
     * Creates a new ShutdownCoordinator with specified timeout.
     *
     * @param shutdownTimeoutSeconds Maximum time to wait for complete shutdown
     */
    public ShutdownCoordinator(long shutdownTimeoutSeconds) {
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.shutdownExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "shutdown-coordinator");
            t.setDaemon(true);
            return t;
        });

        // Register JVM shutdown hook; performShutdown unregisters it again
        // so reset/retry generations cannot accumulate hooks until JVM exit
        this.jvmHook = new Thread(this::performShutdown, "jvm-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(jvmHook);
        LIVE_JVM_HOOKS.incrementAndGet();
    }

    /**
     * Number of coordinator generations whose JVM shutdown hook is still
     * registered (test seam for hook-leak detection).
     */
    public static int liveJvmHookCount() {
        return LIVE_JVM_HOOKS.get();
    }

    private void removeJvmHook() {
        if (jvmHookRemoved.compareAndSet(false, true)) {
            try {
                if (Runtime.getRuntime().removeShutdownHook(jvmHook)) {
                    LIVE_JVM_HOOKS.decrementAndGet();
                }
            } catch (IllegalStateException e) {
                // JVM shutdown already in progress: the hook is about to run
                // (or has run) and cannot be unregistered
            }
        }
    }

    /**
     * Registers a shutdown hook to be executed during shutdown.
     *
     * @param hook The shutdown hook to register
     */
    public void registerShutdownHook(ShutdownHook hook) {
        if (isShuttingDown.get()) {
            LOGGER.warning("Attempted to register shutdown hook '" + hook.getName() + "' during shutdown - ignored");
            return;
        }

        synchronized (hooksLock) {
            shutdownHooks.add(hook);
            // Sort by priority (highest first)
            shutdownHooks.sort((h1, h2) -> Integer.compare(h2.getPriority(), h1.getPriority()));
            LOGGER.fine("Registered shutdown hook: " + hook.getName() + " (priority: " + hook.getPriority() + ")");
        }
    }

    /**
     * Registers a shutdown hook for a specific phase.
     *
     * @param phase The shutdown phase
     * @param name  A descriptive name for the hook
     * @param task  The task to execute
     */
    public void registerShutdownHook(ShutdownPhase phase, String name, Runnable task) {
        registerShutdownHook(new ShutdownHook(name, task, phase.getPriority()));
    }

    /**
     * Registers a shutdown hook for a specific phase with custom timeout.
     *
     * @param phase          The shutdown phase
     * @param name           A descriptive name for the hook
     * @param task           The task to execute
     * @param timeoutSeconds Maximum time to wait for this hook
     * @param essential      Whether this hook is essential
     */
    public void registerShutdownHook(ShutdownPhase phase, String name, Runnable task,
            long timeoutSeconds, boolean essential) {
        registerShutdownHook(new ShutdownHook(name, task, phase.getPriority(), timeoutSeconds, essential));
    }

    /**
     * Initiates graceful shutdown of all registered components.
     *
     * @return A CompletableFuture that completes when shutdown is finished
     */
    public CompletableFuture<Void> initiateShutdown() {
        if (isShuttingDown.compareAndSet(false, true)) {
            LOGGER.info("Initiating graceful shutdown...");
            return CompletableFuture.runAsync(this::performShutdown, shutdownExecutor);
        } else {
            LOGGER.info("Shutdown already in progress");
            return waitForShutdownCompletion();
        }
    }

    /**
     * Waits for shutdown to complete. Runs off the coordinator's own
     * executor: that pool is terminated by performShutdown itself, so a
     * late submission there would be rejected.
     *
     * @return A CompletableFuture that completes when shutdown is finished
     */
    public CompletableFuture<Void> waitForShutdownCompletion() {
        if (isShutdownComplete.get()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            while (!isShutdownComplete.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    /**
     * Performs the actual shutdown process. Package-private so the JVM
     * shutdown hook and tests can enter it; single-flight via CAS so the
     * hook path and {@link #initiateShutdown()} can never execute the hook
     * sequence twice concurrently.
     *
     * <p>Essential hook failures are aggregated and rethrown once every
     * phase has run, so the shutdown future completes exceptionally while
     * best-effort cleanup still happens. Non-essential hook failures are
     * logged and tolerated.</p>
     */
    void performShutdown() {
        if (!shutdownEntered.compareAndSet(false, true)) {
            // Another thread is already running the shutdown sequence.
            return;
        }
        if (isShutdownComplete.get()) {
            return;
        }
        isShuttingDown.set(true);

        long startTime = System.currentTimeMillis();
        LOGGER.info("Starting shutdown process...");
        List<Throwable> essentialFailures = new ArrayList<>();

        try {
            List<ShutdownHook> hooksToExecute;
            synchronized (hooksLock) {
                hooksToExecute = new ArrayList<>(shutdownHooks);
            }

            if (hooksToExecute.isEmpty()) {
                LOGGER.info("No shutdown hooks registered");
                return;
            }

            LOGGER.info("Executing " + hooksToExecute.size() + " shutdown hooks...");

            // Group hooks by priority to execute them in phases
            Map<Integer, List<ShutdownHook>> phaseGroups = new LinkedHashMap<>();
            for (ShutdownHook hook : hooksToExecute) {
                phaseGroups.computeIfAbsent(hook.getPriority(), k -> new ArrayList<>()).add(hook);
            }

            AtomicInteger totalExecuted = new AtomicInteger(0);
            AtomicInteger totalFailed = new AtomicInteger(0);

            // Execute hooks phase by phase
            for (Map.Entry<Integer, List<ShutdownHook>> entry : phaseGroups.entrySet()) {
                int priority = entry.getKey();
                List<ShutdownHook> phaseHooks = entry.getValue();

                String phaseName = getPhaseNameForPriority(priority);
                LOGGER.info("Executing shutdown phase: " + phaseName + " (" + phaseHooks.size() + " hooks)");

                // Execute all hooks in this phase concurrently
                Map<ShutdownHook, CompletableFuture<Void>> phaseResults = new LinkedHashMap<>();
                for (ShutdownHook hook : phaseHooks) {
                    phaseResults.put(hook, executeShutdownHook(hook));
                }

                try {
                    CompletableFuture.allOf(phaseResults.values().toArray(new CompletableFuture[0]))
                            .get(Math.max(1, shutdownTimeoutSeconds), TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Shutdown phase " + phaseName + " did not complete cleanly", e);
                }

                phaseResults.forEach((hook, future) -> {
                    if (future.isDone() && !future.isCompletedExceptionally()) {
                        totalExecuted.incrementAndGet();
                    } else {
                        totalFailed.incrementAndGet();
                        if (hook.isEssential()) {
                            Throwable cause;
                            try {
                                future.get();
                                cause = new IllegalStateException(
                                        "Essential hook '" + hook.getName() + "' never completed");
                            } catch (Exception e) {
                                cause = (e instanceof java.util.concurrent.ExecutionException
                                        && e.getCause() != null) ? e.getCause() : e;
                            }
                            LOGGER.log(Level.SEVERE,
                                    "Essential shutdown hook '" + hook.getName() + "' failed", cause);
                            essentialFailures.add(cause);
                        } else {
                            LOGGER.warning("Best-effort (non-essential) shutdown hook '" + hook.getName()
                                    + "' failed; shutdown continues");
                        }
                    }
                });

                LOGGER.info("Completed shutdown phase: " + phaseName);
            }

            long duration = System.currentTimeMillis() - startTime;
            LOGGER.info(String.format("Shutdown process completed in %dms. Executed: %d, Failed: %d",
                    duration, totalExecuted.get(), totalFailed.get()));

            if (!essentialFailures.isEmpty()) {
                RuntimeException aggregate = new RuntimeException(
                        "Shutdown failed: " + essentialFailures.size()
                                + " essential hook failure(s) (see suppressed)");
                for (Throwable failure : essentialFailures) {
                    aggregate.addSuppressed(failure);
                }
                throw aggregate;
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Critical error during shutdown process", e);
            throw e instanceof RuntimeException runtime ? runtime : new RuntimeException(e);
        } finally {
            isShutdownComplete.set(true);

            // Unregister the JVM hook: this generation is done
            removeJvmHook();

            // Terminate the shutdown executor, but never await it from a
            // task it may be running: the pool cannot terminate until this
            // method returns, so awaiting here always burned the timeout
            shutdownExecutor.shutdown();
        }
    }

    /**
     * Executes a single shutdown hook with timeout handling.
     *
     * @param hook The shutdown hook to execute
     * @return A CompletableFuture that completes when the hook finishes
     */
    private CompletableFuture<Void> executeShutdownHook(ShutdownHook hook) {
        return CompletableFuture.runAsync(() -> {
            LOGGER.fine("Executing shutdown hook: " + hook.getName());
            long startTime = System.currentTimeMillis();

            try {
                // Execute the hook with timeout
                CompletableFuture<Void> hookTask = CompletableFuture.runAsync(hook.getTask());
                hookTask.get(hook.getTimeoutSeconds(), TimeUnit.SECONDS);

                long duration = System.currentTimeMillis() - startTime;
                LOGGER.fine("Shutdown hook '" + hook.getName() + "' completed in " + duration + "ms");

            } catch (TimeoutException e) {
                long duration = System.currentTimeMillis() - startTime;
                String message = "Shutdown hook '" + hook.getName() + "' timed out after " + duration + "ms";

                if (hook.isEssential()) {
                    LOGGER.severe(message + " (essential hook)");
                    throw new RuntimeException(message);
                } else {
                    LOGGER.warning(message + " (non-essential hook)");
                }

            } catch (Exception e) {
                String message = "Shutdown hook '" + hook.getName() + "' failed: " + e.getMessage();

                if (hook.isEssential()) {
                    LOGGER.log(Level.SEVERE, message + " (essential hook)", e);
                    throw new RuntimeException(message, e);
                } else {
                    LOGGER.log(Level.WARNING, message + " (non-essential hook)", e);
                }
            }
        }, shutdownExecutor);
    }

    /**
     * Gets the phase name for a given priority.
     *
     * @param priority The priority value
     * @return The phase name or a generic description
     */
    private String getPhaseNameForPriority(int priority) {
        for (ShutdownPhase phase : ShutdownPhase.values()) {
            if (phase.getPriority() == priority) {
                return phase.name() + " - " + phase.getDescription();
            }
        }
        return "Custom Phase (priority " + priority + ")";
    }

    /**
     * Checks if shutdown is currently in progress.
     *
     * @return true if shutdown is in progress, false otherwise
     */
    public boolean isShuttingDown() {
        return isShuttingDown.get();
    }

    /**
     * Checks if shutdown has completed.
     *
     * @return true if shutdown has completed, false otherwise
     */
    public boolean isShutdownComplete() {
        return isShutdownComplete.get();
    }

    /**
     * Gets the number of registered shutdown hooks.
     *
     * @return The number of registered shutdown hooks
     */
    public int getRegisteredHookCount() {
        synchronized (hooksLock) {
            return shutdownHooks.size();
        }
    }

    /**
     * Gets information about registered shutdown hooks.
     *
     * @return A list of hook information
     */
    public List<String> getShutdownHookInfo() {
        synchronized (hooksLock) {
            return shutdownHooks.stream()
                    .map(hook -> String.format("%s (priority: %d, timeout: %ds, essential: %s)",
                            hook.getName(), hook.getPriority(), hook.getTimeoutSeconds(), hook.isEssential()))
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    /**
     * Unregisters a shutdown hook by name.
     *
     * @param name The name of the hook to unregister
     * @return true if the hook was found and removed, false otherwise
     */
    public boolean unregisterShutdownHook(String name) {
        synchronized (hooksLock) {
            return shutdownHooks.removeIf(hook -> hook.getName().equals(name));
        }
    }

    /**
     * Clears all registered shutdown hooks.
     */
    public void clearShutdownHooks() {
        synchronized (hooksLock) {
            shutdownHooks.clear();
            LOGGER.info("Cleared all shutdown hooks");
        }
    }
}
