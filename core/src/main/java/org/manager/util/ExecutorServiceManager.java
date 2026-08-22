package org.manager.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralized manager for ExecutorService instances.
 * This class provides a single point of control for all thread pools
 * used throughout the application, ensuring proper resource management
 * and coordinated shutdown.
 */
public class ExecutorServiceManager {

    private static final Logger LOGGER = Logger.getLogger(ExecutorServiceManager.class.getName());
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    private static volatile ExecutorServiceManager instance;
    private static final Object instanceLock = new Object();

    private final ExecutorService generalPurposeExecutor;
    private final ScheduledExecutorService scheduledExecutor;
    private final ExecutorService downloadExecutor;
    private final ExecutorService ioExecutor;
    private final ExecutorService eventExecutor;
    private final AtomicBoolean isShutdown;

    /**
     * Private constructor for singleton pattern.
     */
    private ExecutorServiceManager() {
        this.isShutdown = new AtomicBoolean(false);

        // General purpose thread pool for async operations
        this.generalPurposeExecutor = Executors.newCachedThreadPool(
                new NamedThreadFactory("odm-general"));

        // Scheduled executor for periodic tasks and polling
        this.scheduledExecutor = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                new NamedThreadFactory("odm-scheduled"));

        // Fixed thread pool for download operations
        int downloadThreads = Math.max(4, Runtime.getRuntime().availableProcessors());
        this.downloadExecutor = Executors.newFixedThreadPool(
                downloadThreads,
                new NamedThreadFactory("odm-download"));

        // IO-bound operations (file operations, process management)
        this.ioExecutor = Executors.newCachedThreadPool(
                new NamedThreadFactory("odm-io"));

        // Single-threaded event dispatcher: DownloadListener notifications are
        // delivered here, serially and in submission order, so that consumers
        // never run on tool/poller threads and only need one marshal point.
        this.eventExecutor = Executors.newSingleThreadExecutor(
                new NamedThreadFactory("odm-events"));

        // No self-registered JVM shutdown hook here: tearing the pools down
        // races the ShutdownCoordinator's persistence phase (a rejected
        // executor would silently lose download state). The coordinator shuts
        // the pools down in its ordered CLEANUP phase; pool threads are
        // daemon so no path can hang JVM exit.
        LOGGER.info("ExecutorServiceManager initialized with " + downloadThreads + " download threads");
    }

    /**
     * Gets the singleton instance of ExecutorServiceManager.
     *
     * @return The singleton instance
     */
    public static ExecutorServiceManager getInstance() {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new ExecutorServiceManager();
                }
            }
        }
        return instance;
    }

    /**
     * Ends this manager's generation: shuts the pools down (if still live)
     * and clears the singleton so the next {@link #getInstance()} creates a
     * fresh manager. Called when a whole application generation ends
     * (ApplicationFactory.shutdown) — a fresh factory generation must not be
     handed a dead executor manager. Threads are daemon, so a leaked
     * generation cannot hang the JVM.
     */
    public static void resetInstance() {
        synchronized (instanceLock) {
            ExecutorServiceManager current = instance;
            if (current != null) {
                current.shutdown();
                instance = null;
            }
        }
    }

    /**
     * Gets the general purpose executor for async operations.
     *
     * @return The general purpose executor
     * @throws IllegalStateException if the manager has been shut down
     */
    public ExecutorService getGeneralExecutor() {
        checkNotShutdown();
        return generalPurposeExecutor;
    }

    /**
     * Gets the scheduled executor for periodic tasks.
     *
     * @return The scheduled executor
     * @throws IllegalStateException if the manager has been shut down
     */
    public ScheduledExecutorService getScheduledExecutor() {
        checkNotShutdown();
        return scheduledExecutor;
    }

    /**
     * Gets the executor for download operations.
     *
     * @return The download executor
     * @throws IllegalStateException if the manager has been shut down
     */
    public ExecutorService getDownloadExecutor() {
        checkNotShutdown();
        return downloadExecutor;
    }

    /**
     * Gets the single-threaded event dispatcher. All DownloadListener
     * notifications must be delivered on this executor so consumers observe a
     * serial, ordered stream on one known thread.
     *
     * @return The event executor
     * @throws IllegalStateException if the manager has been shut down
     */
    public ExecutorService getEventExecutor() {
        checkNotShutdown();
        return eventExecutor;
    }

    /**
     * Gets the executor for IO operations.
     *
     * @return The IO executor
     * @throws IllegalStateException if the manager has been shut down
     */
    public ExecutorService getIoExecutor() {
        checkNotShutdown();
        return ioExecutor;
    }

    /**
     * Submits a task to the general purpose executor.
     *
     * @param task The task to submit
     * @return A Future representing the task
     * @throws IllegalStateException if the manager has been shut down
     */
    public <T> Future<T> submit(Callable<T> task) {
        return getGeneralExecutor().submit(task);
    }

    /**
     * Submits a runnable task to the general purpose executor.
     *
     * @param task The task to submit
     * @return A Future representing the task
     * @throws IllegalStateException if the manager has been shut down
     */
    public Future<?> submit(Runnable task) {
        return getGeneralExecutor().submit(task);
    }

    /**
     * Schedules a task for periodic execution.
     *
     * @param task         The task to schedule
     * @param initialDelay Initial delay before first execution
     * @param period       Period between successive executions
     * @param unit         Time unit for delays and period
     * @return A ScheduledFuture representing the scheduled task
     * @throws IllegalStateException if the manager has been shut down
     */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return getScheduledExecutor().scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    /**
     * Schedules a task for delayed execution.
     *
     * @param task  The task to schedule
     * @param delay Delay before execution
     * @param unit  Time unit for the delay
     * @return A ScheduledFuture representing the scheduled task
     * @throws IllegalStateException if the manager has been shut down
     */
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return getScheduledExecutor().schedule(task, delay, unit);
    }

    /**
     * Checks if the manager has been shut down.
     *
     * @return true if shut down, false otherwise
     */
    public boolean isShutdown() {
        return isShutdown.get();
    }

    /**
     * Initiates an orderly shutdown of all executors.
     * This method does not wait for previously submitted tasks to complete
     * execution.
     */
    public void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            LOGGER.info("Shutting down ExecutorServiceManager...");

            // Shutdown all executors
            generalPurposeExecutor.shutdown();
            scheduledExecutor.shutdown();
            downloadExecutor.shutdown();
            ioExecutor.shutdown();
            eventExecutor.shutdown();

            // Wait for termination
            try {
                awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                LOGGER.info("ExecutorServiceManager shutdown completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warning("Shutdown interrupted, forcing termination");
                forceShutdown();
            }
        }
    }

    /**
     * Waits for all executors to terminate.
     *
     * @param timeout Maximum time to wait
     * @param unit    Time unit for the timeout
     * @return true if all executors terminated, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutMillis = unit.toMillis(timeout);
        long startTime = System.currentTimeMillis();

        boolean allTerminated = true;

        // Wait for each executor to terminate; the event executor goes last
        // so queued listener notifications drain before the pools vanish.
        ExecutorService[] executors = {
                generalPurposeExecutor, scheduledExecutor, downloadExecutor, ioExecutor, eventExecutor
        };

        for (ExecutorService executor : executors) {
            long remainingTime = timeoutMillis - (System.currentTimeMillis() - startTime);
            if (remainingTime <= 0) {
                allTerminated = false;
                break;
            }

            if (!executor.awaitTermination(remainingTime, TimeUnit.MILLISECONDS)) {
                allTerminated = false;
                break;
            }
        }

        return allTerminated;
    }

    /**
     * Forces immediate shutdown of all executors.
     * This method attempts to stop all actively executing tasks.
     */
    private void forceShutdown() {
        LOGGER.warning("Forcing immediate shutdown of all executors");

        generalPurposeExecutor.shutdownNow();
        scheduledExecutor.shutdownNow();
        downloadExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        eventExecutor.shutdownNow();
    }

    /**
     * Checks if the manager is not shut down, throwing an exception if it is.
     *
     * @throws IllegalStateException if the manager has been shut down
     */
    private void checkNotShutdown() {
        if (isShutdown.get()) {
            throw new IllegalStateException("ExecutorServiceManager has been shut down");
        }
    }

    /**
     * Custom ThreadFactory that provides meaningful names to threads.
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(
                0);
        private final ThreadGroup group;

        public NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
            SecurityManager s = System.getSecurityManager();
            this.group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        }

        @Override
        public Thread newThread(Runnable r) {
            String threadName = namePrefix + "-" + counter.incrementAndGet();
            Thread thread = new Thread(group, r, threadName, 0);

            // Daemon threads: the ShutdownCoordinator drains the pools in
            // its ordered CLEANUP phase during shutdown, and daemon-ness
            // guarantees no code path can hang JVM exit on a stuck pool
            // (there is deliberately no self-registered JVM hook here that
            // could race the coordinator's persistence phase).
            if (!thread.isDaemon()) {
                thread.setDaemon(true);
            }
            if (thread.getPriority() != Thread.NORM_PRIORITY) {
                thread.setPriority(Thread.NORM_PRIORITY);
            }

            return thread;
        }
    }
}
