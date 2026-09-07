package org.httrack;

import org.manager.tools.ExternalProcessRegistry;
import org.manager.tools.ToolPaths;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(HttrackClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Progress parsing patterns
    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "\\s*(\\d+)/(\\d+)\\s+\\((\\d+)\\).*");

    private static final Pattern BYTES_PATTERN = Pattern.compile(
            "Bytes\\s+received:\\s+(\\d+)");
    private static final Pattern RATE_PATTERN = Pattern.compile(
            "Transfer\\s+rate:\\s+(\\d+)\\s+bytes/sec");
    /** Strips the VT100 escapes httrack emits in verbose status lines. */
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    /** "Files written: N" summary line; 0 written + errors = total failure. */
    private static final Pattern FILES_WRITTEN_PATTERN = Pattern.compile("Files\\s+written:\\s*(\\d+)");

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final String httrackPath;
    private final org.manager.tools.ExternalProcessRegistry activeProcesses;
    private final Map<String, HttrackJob> activeJobs;
    private final Map<String, Future<?>> monitoringFutures;
    private final List<HttrackNotificationListener> listeners;
    private final ExecutorService executorService;
    private final AtomicInteger jobIdCounter;

    /**
     * Creates a new HttrackClient with default httrack path from
     * ToolManagerFactory.
     */
    public HttrackClient() {
        this(ToolPaths.httrack());
    }


    /**
     * Creates a new HttrackClient with specified httrack executable path.
     *
     * @param httrackPath Path to httrack executable
     */
    public HttrackClient(String httrackPath) {
        this.httrackPath = httrackPath;
        this.activeProcesses = new org.manager.tools.ExternalProcessRegistry("httrack");
        this.activeJobs = new ConcurrentHashMap<>();
        this.monitoringFutures = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        // Daemon threads: a missed shutdown() must never keep the JVM alive
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "httrack-client");
            t.setDaemon(true);
            return t;
        });
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
            String jobId = null;
            PreparedCommand prepared = null;
            try {
                validateSettings(settings);

                jobId = generateJobId();
                HttrackJob job = new HttrackJob(jobId, settings);
                activeJobs.put(jobId, job);

                // Create output directory if it doesn't exist
                if (settings.getOutputDirectory() != null) {
                    Files.createDirectories(settings.getOutputDirectory());
                }

                // Build command
                prepared = prepareCommand(settings);

                // Start process
                ProcessBuilder processBuilder = org.manager.tools.NetworkProcessPolicy.prepare(new ProcessBuilder(prepared.arguments()));
                processBuilder.redirectErrorStream(true);
                if (settings.getOutputDirectory() != null) {
                    processBuilder.directory(settings.getOutputDirectory().toFile());
                }

                org.manager.tools.ExternalProcessRegistry.LaunchReservation launch =
                        activeProcesses.reserve(jobId);
                org.manager.tools.ExternalProcessRegistry.Registration registration =
                        launch.start(processBuilder);
                Process process = registration.process();

                // Update job status
                job.setStatus(HttrackJob.Status.RUNNING);
                notifyJobStarted(job);

                // Start monitoring in background
                PreparedCommand ownedCommand = prepared;
                process.onExit().thenRun(ownedCommand::close);
                Future<?> monitoringFuture = executorService.submit(() -> monitorProcess(process, job, registration));
                monitoringFutures.put(jobId, monitoringFuture);

                LOGGER.info("Started httrack job " + jobId);
                return jobId;

            } catch (Exception e) {
                if (prepared != null) {
                    prepared.close();
                }
                if (jobId != null) {
                    activeProcesses.terminate(jobId, 1);
                    Future<?> monitor = monitoringFutures.remove(jobId);
                    if (monitor != null) {
                        monitor.cancel(true);
                    }
                    activeJobs.remove(jobId);
                }
                LOGGER.error("Failed to start httrack mirror", e);
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
                boolean hadProcess = activeProcesses.get(jobId) != null;
                HttrackJob job = activeJobs.get(jobId);
                Future<?> monitoringFuture = monitoringFutures.get(jobId);

                if (hadProcess && job != null) {
                    // Cancel the monitoring thread first to prevent it from overriding our status
                    if (monitoringFuture != null) {
                        monitoringFuture.cancel(true);
                        monitoringFutures.remove(jobId);
                    }

                    // httrack doesn't support pausing, so we'll stop and mark as paused
                    // Set the status to paused first, then destroy the process
                    job.setStatus(HttrackJob.Status.PAUSED);

                    // Then terminate the process (SIGTERM, bounded
                    // escalation) and drop the registration
                    activeProcesses.terminate(jobId, 5);

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
        return resumeJob(jobId, null);
    }

    /** Resumes the same cache with the latest per-download settings and route. */
    public CompletableFuture<Void> resumeJob(String jobId, HttrackSettings latestSettings) {
        return CompletableFuture.runAsync(() -> {
            synchronized (this) {
                HttrackJob job = activeJobs.get(jobId);

                if (job != null && job.getStatus() == HttrackJob.Status.PAUSED) {
                    PreparedCommand prepared = null;
                    try {
                    // Resume the interrupted cache without turning the action
                    // into a remote-content update.
                    HttrackSettings settings = (latestSettings == null
                            ? job.getSettings() : latestSettings).copySettings();
                    settings.setRunMode(HttrackSettings.RunMode.CONTINUE);
                    job.setSettings(settings);

                    // Restart the process
                    prepared = prepareCommand(settings);
                    ProcessBuilder processBuilder = org.manager.tools.NetworkProcessPolicy.prepare(new ProcessBuilder(prepared.arguments()));
                    processBuilder.redirectErrorStream(true);
                    if (settings.getOutputDirectory() != null) {
                        processBuilder.directory(settings.getOutputDirectory().toFile());
                    }

                    org.manager.tools.ExternalProcessRegistry.LaunchReservation launch =
                            activeProcesses.reserve(jobId);
                    org.manager.tools.ExternalProcessRegistry.Registration registration =
                            launch.start(processBuilder);
                    Process process = registration.process();

                    job.setStatus(HttrackJob.Status.RUNNING);
                    notifyJobResumed(job);

                    // Start monitoring in background
                    PreparedCommand ownedCommand = prepared;
                    process.onExit().thenRun(ownedCommand::close);
                    Future<?> monitoringFuture = executorService.submit(() -> monitorProcess(process, job, registration));
                    monitoringFutures.put(jobId, monitoringFuture);

                    LOGGER.info("Resumed httrack job: " + jobId);

                    } catch (Exception e) {
                        activeProcesses.terminate(jobId, 1);
                        if (prepared != null) {
                            prepared.close();
                        }
                        if (job.getStatus() != HttrackJob.Status.CANCELED) {
                            job.setStatus(HttrackJob.Status.ERROR);
                            job.setErrorMessage("Failed to resume: " + e.getMessage());
                            notifyJobError(job, e.getMessage());
                        }
                        LOGGER.error("Failed to resume httrack job", e);
                        throw new java.util.concurrent.CompletionException(e);
                    }
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
            synchronized (this) {
                HttrackJob job = activeJobs.get(jobId);

                if (job != null) {
                // CANCELED lands before termination so the monitoring
                // thread's completion handler (woken by the dying process)
                // observes the intentional state instead of converting the
                // kill exit code into ERROR
                job.setStatus(HttrackJob.Status.CANCELED);
            }

            // Bounded tree termination (SIGTERM with grace so httrack can
            // save its index/state files, then SIGKILL): the old bare
            // destroyForcibly returned before the process (and its spawned
            // helpers) were actually dead, racing the deletion below
                activeProcesses.terminate(jobId, 5);

                if (job != null) {
                    Future<?> monitor = monitoringFutures.remove(jobId);
                    if (monitor != null) {
                        monitor.cancel(true);
                    }
                    if (deleteFiles && job.getSettings().getOutputDirectory() != null) {
                        try {
                            deleteDirectory(job.getSettings().getOutputDirectory());
                        } catch (IOException e) {
                            LOGGER.warn("Failed to delete output directory", e);
                        }
                    }

                    notifyJobCanceled(job);
                    activeJobs.remove(jobId);
                    LOGGER.info("Canceled httrack job: " + jobId);
                }
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
            Process process = null;
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(httrackPath, "--version");
                processBuilder.redirectErrorStream(true);
                process = processBuilder.start();
                // Bounded wait: a hung binary must degrade to "unavailable"
                // instead of blocking startup availability checks
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    LOGGER.warn("httrack at path '" + httrackPath
                            + "' did not respond to --version within 10 seconds");
                    return false;
                }
                return process.exitValue() == 0;
            } catch (Exception e) {
                if (process != null) {
                    process.destroyForcibly();
                }
                LOGGER.warn("httrack not available", e);
                return false;
            }
        }, executorService);
    }

    /**
     * Shuts down the client and stops all active jobs.
     */
    public void shutdown() {
        // Cancel all active jobs and wait so callers observe a fully
        // stopped client with no lingering active jobs
        List<CompletableFuture<Void>> cancellations = new ArrayList<>();
        for (String jobId : new ArrayList<>(activeJobs.keySet())) {
            cancellations.add(cancelJob(jobId, false));
        }
        CompletableFuture.allOf(cancellations.toArray(new CompletableFuture[0])).join();

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
        command.addAll(settings.buildCommandLine());
        // Client-owned output settings used by the progress parser.
        command.add("-q"); // Quiet mode
        command.add("-%v"); // Verbose status
        if (settings.isUseProxy()) {
            // Route controls remain last, after all generic native options.
            command.add("-P");
            command.add(settings.proxyWithCredentials());
        }
        return command;
    }

    PreparedCommand prepareCommand(HttrackSettings settings) throws IOException {
        if (!settings.isUseProxy()) {
            return new PreparedCommand(buildCommand(settings), null);
        }
        String proxy = org.manager.tools.NetworkProcessPolicy.proxyAddress(settings.proxyWithCredentials());
        if (proxy.startsWith("https://")) {
            throw new IOException("HTTrack does not support TLS to an HTTPS proxy");
        }
        if (proxy == null || proxy.isBlank()) {
            throw new IllegalArgumentException("An enabled proxy requires an address");
        }
        if (!org.manager.download.handler.DownloadHandlerFactory.isSocksProxyAddress(proxy)) {
            return new PreparedCommand(buildCommand(settings), null);
        }
        // Older packaged HTTrack versions lack native SOCKS. Use the same
        // mandatory SOCKS/DNS route as the other proxychains downloads.
        org.proxychains.ProxychainsConfig config = org.proxychains.ProxychainsConfig.forProxyAddress(proxy);
        HttrackSettings nativeSettings = settings.copySettings();
        nativeSettings.setUseProxy(false);
        List<String> command = new ArrayList<>(List.of(ToolPaths.proxychains(), "-f"));
        Path configFile = config.createTempConfig();
        command.add(configFile.toString());
        command.addAll(buildCommand(nativeSettings));
        return new PreparedCommand(command, configFile);
    }

    record PreparedCommand(List<String> arguments, Path configFile) implements AutoCloseable {
        @Override
        public void close() {
            if (configFile != null) {
                try {
                    Files.deleteIfExists(configFile);
                } catch (IOException e) {
                    LOGGER.warn("Could not remove temporary HTTrack proxy configuration", e);
                }
            }
        }
    }

    private void monitorProcess(Process process, HttrackJob job,
            org.manager.tools.ExternalProcessRegistry.Registration registration) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            long lastUpdateTime = System.currentTimeMillis();
            boolean sawErrors = false;
            int filesWritten = 0;

            while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                parseProgressLine(line, job);

                // httrack exits 0 even when the whole mirror failed (e.g.
                // unresolvable host); " error - " entries in the (ANSI
                // escaped) output are the only failure signal
                String plain = ANSI_PATTERN.matcher(line).replaceAll("");
                if (plain.contains(" error - ")) {
                    sawErrors = true;
                }
                Matcher filesMatcher = FILES_WRITTEN_PATTERN.matcher(plain);
                if (filesMatcher.find()) {
                    filesWritten = Math.max(filesWritten, Integer.parseInt(filesMatcher.group(1)));
                }

                // Throttle progress updates
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastUpdateTime > 1000) {
                    notifyJobProgress(job);
                    lastUpdateTime = currentTime;
                }
            }

            // Wait for process completion
            int exitCode = process.waitFor();
            handleProcessCompletion(exitCode, job, sawErrors && filesWritten == 0);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                if (job.getStatus() != HttrackJob.Status.PAUSED
                        && job.getStatus() != HttrackJob.Status.CANCELED) {
                    job.setStatus(HttrackJob.Status.CANCELED);
                    notifyJobCanceled(job);
                }
            }
        } catch (IOException e) {
            // Don't change status if thread was interrupted or if job is
            // already paused/canceled (intentionally destroyed process)
            synchronized (this) {
                if (Thread.currentThread().isInterrupted() || job.getStatus() == HttrackJob.Status.PAUSED
                        || job.getStatus() == HttrackJob.Status.CANCELED) {
                    return;
                }
            }

            job.setStatus(HttrackJob.Status.ERROR);
            job.setErrorMessage("IO error during monitoring: " + e.getMessage());
            notifyJobError(job, e.getMessage());
        } finally {
            // Generation-safe cleanup: a monitor for a replaced run (resume
            // raced it) must not unregister the newer process under the key
            if (registration != null) {
                registration.unregister();
            }
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

    private void handleProcessCompletion(int exitCode, HttrackJob job, boolean totalFailure) {
        synchronized (this) {
            // Don't process completion if the job is already paused or
            // canceled: those are terminal/intentional states whose process
            // was destroyed on purpose — overwriting them (e.g. CANCELED ->
            // ERROR from the kill exit code) and firing a spurious error
            // notification would corrupt the user-visible outcome.
            if (job.getStatus() == HttrackJob.Status.PAUSED
                    || job.getStatus() == HttrackJob.Status.CANCELED) {
                // Job was paused or canceled, don't change its status or remove it
                return;
            }

            // Clean up monitoring future
            monitoringFutures.remove(job.getJobId());

            // exitCode 0 with totalFailure: httrack reported transfer errors
            // and wrote no files (e.g. unresolvable host) — the exit code
            // alone cannot distinguish this from success
            if (exitCode == 0 && !totalFailure) {
                job.setStatus(HttrackJob.Status.COMPLETED);
                job.setProgress(100.0f);
                notifyJobCompleted(job);
                LOGGER.info("httrack job completed successfully: " + job.getJobId());
            } else {
                job.setStatus(HttrackJob.Status.ERROR);
                job.setErrorMessage(totalFailure
                        ? "httrack reported transfer errors and wrote no files"
                        : "httrack process exited with code: " + exitCode);
                notifyJobError(job, job.getErrorMessage());
                LOGGER.warn("httrack job failed with exit code " + exitCode + ": " + job.getJobId());
            }

            activeJobs.remove(job.getJobId());
        }
    }

    /**
     * Recursively deletes the configured output directory. Every entry is
     * re-validated against the configured (normalized absolute) root with
     * real-path containment immediately before deletion: a symlinked
     * component that resolves outside the configured directory can never
     * redirect the deletion, even when swapped in mid-walk. The root itself
     * is deleted last and only as itself (a symlink root removes the link,
     * never its target tree).
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Path lexicalRoot = directory.toAbsolutePath().normalize();
        List<Path> paths;
        try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
            paths = walk.sorted((a, b) -> b.compareTo(a)).toList();
        }

        for (Path path : paths) {
            try {
                if (path.equals(directory)) {
                    Files.deleteIfExists(path);
                } else if (realPathConfined(path, lexicalRoot)) {
                    Files.delete(path);
                } else {
                    LOGGER.warn("Skipping deletion outside the configured output directory: " + path);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to delete: " + path, e);
            }
        }
    }

    private static boolean realPathConfined(Path candidate, Path lexicalRoot) {
        if (Files.isSymbolicLink(candidate)) {
            return false;
        }
        Path parent = candidate.getParent();
        if (parent == null) {
            return false;
        }
        try {
            return parent.toRealPath().startsWith(lexicalRoot);
        } catch (IOException e) {
            return false;
        }
    }

    // Notification methods
    private void notifyJobStarted(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobStarted(job);
            } catch (Exception e) {
                LOGGER.warn("Error in notification listener", e);
            }
        }
    }

    private void notifyJobProgress(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobProgress(job);
            } catch (Exception e) {
                LOGGER.warn("Error in notification listener", e);
            }
        }
    }

    private void notifyJobCompleted(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobCompleted(job);
            } catch (Exception e) {
                LOGGER.warn("Error in notification listener", e);
            }
        }
    }

    private void notifyJobPaused(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobPaused(job);
            } catch (Exception e) {
                LOGGER.warn("Error in notification listener", e);
            }
        }
    }

    private void notifyJobResumed(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobResumed(job);
            } catch (Exception e) {
                LOGGER.warn("Error in notification listener", e);
            }
        }
    }

    private void notifyJobCanceled(HttrackJob job) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobCanceled(job);
            } catch (Exception e) {
                LOGGER.warn("Error in notification listener", e);
            }
        }
    }

    private void notifyJobError(HttrackJob job, String errorMessage) {
        for (HttrackNotificationListener listener : listeners) {
            try {
                listener.onJobError(job, errorMessage);
            } catch (Exception e) {
                LOGGER.warn("Error in notification listener", e);
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
