package org.httrack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.manager.ApplicationContext;
import org.manager.tools.ToolManagerFactory;
import org.httrack.HttrackToolManager;

/**
 * Client for interacting with httrack website mirroring tool. Provides
 * high-level interface for website scraping operations.
 */
public class HttrackClient {

    private static final Logger LOGGER = Logger.getLogger(HttrackClient.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Progress parsing patterns
    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "\\s*(\\d+)/(\\d+)\\s+\\((\\d+)\\).*");

    private static final Pattern BYTES_PATTERN = Pattern.compile(
            "Bytes\\s+received:\\s+(\\d+)");
    private static final Pattern RATE_PATTERN = Pattern.compile(
            "Transfer\\s+rate:\\s+(\\d+)\\s+bytes/sec");

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final String httrackPath;
    private final Map<String, Process> activeProcesses;
    private final Map<String, HttrackJob> activeJobs;
    private final Map<String, Future<?>> monitoringFutures;
    private final List<HttrackNotificationListener> listeners;
    private final ExecutorService executorService;
    private final AtomicInteger jobIdCounter;

    /**
     * Gets the ToolManagerFactory instance using ApplicationContext.
     */
    private static ToolManagerFactory getToolManagerFactory() {
        return ApplicationContext.getToolManagerFactory();
    }

    /**
     * Creates a new HttrackClient with default httrack path from
     * ToolManagerFactory.
     */
    public HttrackClient() {
        this(getHttrackPath());
    }

    /**
     * Gets the httrack path using the ToolManagerFactory.
     */
    private static String getHttrackPath() {
        try {
            ToolManagerFactory factory = getToolManagerFactory();
            if (factory != null) {
                HttrackToolManager httrackManager = factory.getHttrackManager();
                if (httrackManager != null) {
                    return httrackManager.getToolPath();
                }
            }

            // Final fallback - try system httrack
            return "httrack";
        } catch (Exception e) {
            // Final fallback - try system httrack
            return "httrack";
        }
    }

    /**
     * Creates a new HttrackClient with specified httrack executable path.
     *
     * @param httrackPath Path to httrack executable
     */
    public HttrackClient(String httrackPath) {
        this.httrackPath = httrackPath;
        this.activeProcesses = new ConcurrentHashMap<>();
        this.activeJobs = new ConcurrentHashMap<>();
        this.monitoringFutures = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.executorService = Executors.newCachedThreadPool();
        this.jobIdCounter = new AtomicInteger(0);
    }

    /**
     * Add a notification listener.
     *
     * @param listener The listener to add
     */
    public void addNotificationListener(HttrackNotificationListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a notification listener.
     *
     * @param listener The listener to remove
     */
    public void removeNotificationListener(HttrackNotificationListener listener) {
        listeners.remove(listener);
    }

    /**
     * Starts mirroring a website with the given settings.
     *
     * @param settings The httrack settings to use
     * @return CompletableFuture containing the job ID
     */
    public CompletableFuture<String> startMirror(HttrackSettings settings) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                validateSettings(settings);

                String jobId = generateJobId();
                HttrackJob job = new HttrackJob(jobId, settings);
                activeJobs.put(jobId, job);

                // Create output directory if it doesn't exist
                if (settings.getOutputDirectory() != null) {
                    Files.createDirectories(settings.getOutputDirectory());
                }

                // Build command
                List<String> command = buildCommand(settings);

                // Start process
                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.redirectErrorStream(true);
                if (settings.getOutputDirectory() != null) {
                    processBuilder.directory(settings.getOutputDirectory().toFile());
                }

                Process process = processBuilder.start();
                activeProcesses.put(jobId, process);

                // Update job status
                job.setStatus(HttrackJob.Status.RUNNING);
                notifyJobStarted(job);

                // Start monitoring in background
                Future<?> monitoringFuture = executorService.submit(() -> monitorProcess(process, job));
                monitoringFutures.put(jobId, monitoringFuture);

                LOGGER.info("Started httrack job " + jobId + " for URL: " + settings.getUrl());
                return jobId;

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to start httrack mirror", e);
                throw new RuntimeException("Failed to start httrack mirror: " + e.getMessage(), e);
            }
        }, executorService);
    }

    /**
     * Pauses a running mirror job.
     *
     * @param jobId The job ID to pause
     * @return CompletableFuture that completes when the job is paused
     */
    public CompletableFuture<Void> pauseJob(String jobId) {
        return CompletableFuture.runAsync(() -> {
            synchronized (this) {
                Process process = activeProcesses.get(jobId);
                HttrackJob job = activeJobs.get(jobId);
                Future<?> monitoringFuture = monitoringFutures.get(jobId);

                if (process != null && job != null) {
                    // Cancel the monitoring thread first to prevent it from overriding our status
                    if (monitoringFuture != null) {
                        monitoringFuture.cancel(true);
                        monitoringFutures.remove(jobId);
                    }

                    // httrack doesn't support pausing, so we'll stop and mark as paused
                    // Set the status to paused first, then destroy the process
                    job.setStatus(HttrackJob.Status.PAUSED);

                    // Then destroy the process and remove it
                    process.destroy();
                    activeProcesses.remove(jobId);

                    notifyJobPaused(job);
                    LOGGER.info("Paused httrack job: " + jobId);
                }
            }
        }, executorService);
    }

    /**
     * Resumes a paused mirror job.
     *
     * @param jobId The job ID to resume
     * @return CompletableFuture that completes when the job is resumed
     */
    public CompletableFuture<Void> resumeJob(String jobId) {
        return CompletableFuture.runAsync(() -> {
            HttrackJob job = activeJobs.get(jobId);

            if (job != null && job.getStatus() == HttrackJob.Status.PAUSED) {
                try {
                    // Add update flag to continue existing mirror
                    HttrackSettings settings = job.getSettings().copySettings();
                    settings.addAdditionalOption("i", ""); // Update existing mirror

                    // Restart the process
                    List<String> command = buildCommand(settings);
                    ProcessBuilder processBuilder = new ProcessBuilder(command);
                    processBuilder.redirectErrorStream(true);
                    if (settings.getOutputDirectory() != null) {
                        processBuilder.directory(settings.getOutputDirectory().toFile());
                    }

                    Process process = processBuilder.start();
                    activeProcesses.put(jobId, process);

                    job.setStatus(HttrackJob.Status.RUNNING);
                    notifyJobResumed(job);

                    // Start monitoring in background
                    Future<?> monitoringFuture = executorService.submit(() -> monitorProcess(process, job));
                    monitoringFutures.put(jobId, monitoringFuture);

                    LOGGER.info("Resumed httrack job: " + jobId);

                } catch (Exception e) {
                    job.setStatus(HttrackJob.Status.ERROR);
                    job.setErrorMessage("Failed to resume: " + e.getMessage());
                    notifyJobError(job, e.getMessage());
                    LOGGER.log(Level.SEVERE, "Failed to resume httrack job", e);
                }
            }
        }, executorService);
    }

    /**
     * Cancels a running mirror job.
     *
     * @param jobId       The job ID to cancel
     * @param deleteFiles Whether to delete downloaded files
     * @return CompletableFuture that completes when the job is canceled
     */
    public CompletableFuture<Void> cancelJob(String jobId, boolean deleteFiles) {
        return CompletableFuture.runAsync(() -> {
            Process process = activeProcesses.get(jobId);
            HttrackJob job = activeJobs.get(jobId);

            if (process != null) {
                process.destroyForcibly();
                activeProcesses.remove(jobId);
            }

            if (job != null) {
                if (deleteFiles && job.getSettings().getOutputDirectory() != null) {
                    try {
                        deleteDirectory(job.getSettings().getOutputDirectory());
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to delete output directory", e);
                    }
                }

                job.setStatus(HttrackJob.Status.CANCELED);
                notifyJobCanceled(job);
                activeJobs.remove(jobId);
                LOGGER.info("Canceled httrack job: " + jobId);
            }
        }, executorService);
    }

    /**
     * Gets the status of a mirror job.
     *
     * @param jobId The job ID
     * @return The job status, or null if job not found
     */
    public HttrackJob getJobStatus(String jobId) {
        return activeJobs.get(jobId);
    }

    /**
     * Gets all active jobs.
     *
     * @return Map of job ID to HttrackJob
     */
    public Map<String, HttrackJob> getActiveJobs() {
        return new ConcurrentHashMap<>(activeJobs);
    }

    /**
     * Checks if httrack is available and working.
     *
     * @return CompletableFuture that completes with true if httrack is
     *         available
     */
    public CompletableFuture<Boolean> isHttrackAvailable() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(httrackPath, "--version");
                processBuilder.redirectErrorStream(true);
                Process process = processBuilder.start();
                int exitCode = process.waitFor();
                return exitCode == 0;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "httrack not available", e);
                return false;
            }
        }, executorService);
    }

    /**
     * Shuts down the client and stops all active jobs.
     */
    public void shutdown() {
        // Cancel all active jobs
        for (String jobId : new ArrayList<>(activeJobs.keySet())) {
            cancelJob(jobId, false);
        }

        // Shutdown executor
        executorService.shutdown();
    }

    private void validateSettings(HttrackSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Settings cannot be null");
        }
        if (settings.getUrl() == null || settings.getUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("URL cannot be null or empty");
        }
    }

    private String generateJobId() {
        return "httrack_" + jobIdCounter.incrementAndGet() + "_" + System.currentTimeMillis();
    }

    private List<String> buildCommand(HttrackSettings settings) {
        List<String> command = new ArrayList<>();
        command.add(httrackPath);

        // Add URL
        command.add(settings.getUrl());

        // Add output directory
        if (settings.getOutputDirectory() != null) {
            command.add("-O");
            command.add(settings.getOutputDirectory().toString());
        }

        // Add basic options
        command.add("-q"); // Quiet mode
        command.add("-%v"); // Verbose status

        // Add depth limit
        command.add("-r" + settings.getDepth());

        // External links
        if (settings.isFollowExternalLinks()) {
            command.add("-*");
        } else {
            command.add("-%e");
        }

        // Connection settings
        command.add("-c" + settings.getConnections());

        // Rate limiting
        if (settings.getMaxRate() > 0) {
            command.add("-A" + (settings.getMaxRate() * 1024)); // Convert KB/s to bytes/s
        }

        // User agent
        if (settings.getUserAgent() != null) {
            command.add("-F");
            command.add(settings.getUserAgent());
        }

        // Proxy settings
        if (settings.isUseProxy() && settings.getProxyAddress() != null) {
            command.add("-P");
            command.add(settings.getProxyAddress());
        }

        // File type filters
        List<String> filters = new ArrayList<>();
        if (settings.isIncludeImages()) {
            filters.add("+*.jpg");
            filters.add("+*.png");
            filters.add("+*.gif");
            filters.add("+*.jpeg");
            filters.add("+*.webp");
            filters.add("+*.svg");
        }
        if (settings.isIncludeVideos()) {
            filters.add("+*.mp4");
            filters.add("+*.webm");
            filters.add("+*.avi");
            filters.add("+*.mov");
        }
        if (settings.isIncludeAudio()) {
            filters.add("+*.mp3");
            filters.add("+*.wav");
            filters.add("+*.ogg");
        }
        if (settings.isIncludeDocuments()) {
            filters.add("+*.pdf");
            filters.add("+*.doc");
            filters.add("+*.docx");
            filters.add("+*.txt");
        }

        // Add include patterns
        for (String pattern : settings.getIncludePatterns()) {
            filters.add("+" + pattern);
        }

        // Add exclude patterns
        for (String pattern : settings.getExcludePatterns()) {
            filters.add("-" + pattern);
        }

        // Add filters to command
        if (!filters.isEmpty()) {
            command.add(String.join(",", filters));
        }

        // Add additional options
        for (Map.Entry<String, String> entry : settings.getAdditionalOptions().entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                command.add("-" + entry.getKey());
            } else {
                command.add("-" + entry.getKey() + entry.getValue());
            }
        }

        return command;
    }

    private void monitorProcess(Process process, HttrackJob job) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            long lastUpdateTime = System.currentTimeMillis();

            while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                parseProgressLine(line, job);

                // Throttle progress updates
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastUpdateTime > 1000) {
                    notifyJobProgress(job);
                    lastUpdateTime = currentTime;
                }
            }

            // Wait for process completion
            int exitCode = process.waitFor();
            handleProcessCompletion(exitCode, job);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.setStatus(HttrackJob.Status.CANCELED);
            notifyJobCanceled(job);
        } catch (IOException e) {
            // Don't change status if thread was interrupted or if job is already paused
            synchronized (this) {
                if (Thread.currentThread().isInterrupted() || job.getStatus() == HttrackJob.Status.PAUSED) {
                    return;
                }
            }

            job.setStatus(HttrackJob.Status.ERROR);
            job.setErrorMessage("IO error during monitoring: " + e.getMessage());
            notifyJobError(job, e.getMessage());
        } finally {
            activeProcesses.remove(job.getJobId());
        }
    }

    private void parseProgressLine(String line, HttrackJob job) {
        // Parse progress information from httrack output
        Matcher progressMatcher = PROGRESS_PATTERN.matcher(line);
        if (progressMatcher.find()) {
            long downloaded = Long.parseLong(progressMatcher.group(1));
            long total = Long.parseLong(progressMatcher.group(2));
            int rate = Integer.parseInt(progressMatcher.group(3));

            job.setFilesDownloaded(downloaded);
            job.setTotalFiles(total);
            job.setTransferRate(rate);

            if (total > 0) {
                job.setProgress((float) downloaded / total * 100);
            }
        }

        // Parse bytes information
        Matcher bytesMatcher = BYTES_PATTERN.matcher(line);
        if (bytesMatcher.find()) {
            long bytes = Long.parseLong(bytesMatcher.group(1));
            job.setBytesDownloaded(bytes);
        }

        // Parse transfer rate
        Matcher rateMatcher = RATE_PATTERN.matcher(line);
        if (rateMatcher.find()) {
            int rate = Integer.parseInt(rateMatcher.group(1));
            job.setTransferRate(rate);
        }
    }

    private void handleProcessCompletion(int exitCode, HttrackJob job) {
        synchronized (this) {
            // Don't process completion if the job is already paused
            // (paused jobs have their process destroyed intentionally)
            if (job.getStatus() == HttrackJob.Status.PAUSED) {
                // Job was paused, don't change its status or remove it
                return;
            }

            // Clean up monitoring future
            monitoringFutures.remove(job.getJobId());

            if (exitCode == 0) {
                job.setStatus(HttrackJob.Status.COMPLETED);
                job.setProgress(100.0f);
                notifyJobCompleted(job);
                LOGGER.info("httrack job completed successfully: " + job.getJobId());
            } else {
                job.setStatus(HttrackJob.Status.ERROR);
                job.setErrorMessage("httrack process exited with code: " + exitCode);
                notifyJobError(job, job.getErrorMessage());
                LOGGER.warning("httrack job failed with exit code " + exitCode + ": " + job.getJobId());
            }

            activeJobs.remove(job.getJobId());
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walk(directory)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to delete: " + path, e);
                    }
                });
    }

    // Notification methods
    private void notifyJobStarted(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobStarted(job);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in notification listener", e);
            }
        }
    }

    private void notifyJobProgress(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobProgress(job);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in notification listener", e);
            }
        }
    }

    private void notifyJobCompleted(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobCompleted(job);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in notification listener", e);
            }
        }
    }

    private void notifyJobPaused(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobPaused(job);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in notification listener", e);
            }
        }
    }

    private void notifyJobResumed(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobResumed(job);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in notification listener", e);
            }
        }
    }

    private void notifyJobCanceled(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobCanceled(job);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in notification listener", e);
            }
        }
    }

    private void notifyJobError(HttrackJob job, String errorMessage) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobError(job, errorMessage);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in notification listener", e);
            }
        }
    }

    /**
     * Interface for receiving httrack job notifications.
     */
    public interface HttrackNotificationListener {

        default void onJobStarted(HttrackJob job) {
        }

        default void onJobProgress(HttrackJob job) {
        }

        default void onJobCompleted(HttrackJob job) {
        }

        default void onJobPaused(HttrackJob job) {
        }

        default void onJobResumed(HttrackJob job) {
        }

        default void onJobCanceled(HttrackJob job) {
        }

        default void onJobError(HttrackJob job, String errorMessage) {
        }
    }
}
