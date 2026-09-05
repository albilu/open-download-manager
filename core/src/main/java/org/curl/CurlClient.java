package org.curl;

import org.manager.tools.ExternalProcessRegistry;
import org.manager.tools.ToolPaths;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.manager.ApplicationContext;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.tools.ToolManagerFactory;

/**
 * A client for managing downloads using curl command-line tool. This is used as
 * a fallback when proxychains fails, or for specific protocols.
 */
public class CurlClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(CurlClient.class);

    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([\\d.]+[kmgtKMGT]?)\\s+.*");

    private static final Pattern TOTAL_SIZE_PATTERN = Pattern.compile(
            "Content-Length:\\s*(\\d+)");

    private final String curlPath;
    private final ExecutorService executorService;
    private final org.manager.tools.ExternalProcessRegistry activeProcesses;
    private final Map<String, Download> activeDownloads;
    /** Also identifies the worker run, so a late cleanup cannot remove a resumed download. */
    private final Map<String, CompletableFuture<String>> launchFutures = new ConcurrentHashMap<>();

    /**
     * Creates a new CurlClient with default curl path from ToolManagerFactory.
     */
    public CurlClient() {
        this(ToolPaths.curl());
    }


    /**
     * Creates a new CurlClient with the specified curl command path.
     *
     * @param curlPath Path to the curl executable
     */
    public CurlClient(String curlPath) {
        this.curlPath = curlPath;
        // Daemon threads: a missed shutdown() must never keep the JVM alive
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "curl-client");
            t.setDaemon(true);
            return t;
        });
        this.activeProcesses = new org.manager.tools.ExternalProcessRegistry("curl");
        this.activeDownloads = new ConcurrentHashMap<>();

        // Validate that curl is available
        validateCurlInstallation();
    }

    /**
     * Validates that curl is installed and available.
     *
     * @throws RuntimeException if curl is not available
     */
    private void validateCurlInstallation() {
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(curlPath, "--version");
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();
            // Bounded wait: a hung binary must fail validation instead of
            // blocking construction (this runs on startup paths). Output is
            // only drained after exit — draining before the wait would block
            // on a binary that never closes its stream.
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("curl at path '" + curlPath
                        + "' did not respond to --version within 10 seconds");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("curl command failed with exit code: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to execute curl at path: " + curlPath
                    + ". Make sure it's installed and the path is correct.", e);
        }
    }

    /**
     * Starts a download using curl.
     *
     * @param download The download to start
     * @param listener Listener for download events
     */
    public CompletableFuture<String> startDownload(Download download, DownloadListener listener) {
        return startDownload(download, listener, false);
    }

    private CompletableFuture<String> startDownload(Download download, DownloadListener listener, boolean resuming) {
        CompletableFuture<String> started = new CompletableFuture<>();
        if (download == null || download.getUri() == null) {
            if (listener != null) {
                listener.onDownloadError(download, "Invalid download or URI is null");
            }
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid download or URI is null"));
        }

        final ExternalProcessRegistry.LaunchReservation launch;
        synchronized (download) {
            if (!resuming && (download.getStatus() == Download.Status.PAUSED
                    || download.getStatus() == Download.Status.CANCELED)) {
                return CompletableFuture.failedFuture(new CancellationException("Download was stopped before launch"));
            }
            launch = activeProcesses.reserve(download.getId());
            download.setStatus(Download.Status.CONNECTING);
            activeDownloads.put(download.getId(), download);
            CompletableFuture<String> previous = launchFutures.put(download.getId(), started);
            if (previous != null) {
                previous.completeExceptionally(new CancellationException("Launch was replaced"));
            }
        }

        // Start download in a separate thread
        try {
            executorService.submit(() -> {
                org.manager.tools.ExternalProcessRegistry.Registration registration = null;
                try {
                    // Create destination directory if it doesn't exist
                    Path destinationDir = download.getDestination();
                    if (destinationDir != null) {
                        Files.createDirectories(destinationDir);
                    } else {
                        // Use current directory as default
                        destinationDir = Paths.get(".");
                    }

                    // Prepare the output file path
                    String outputName = download.getRequestedFileName() != null
                            ? download.getRequestedFileName() : download.getName();
                    org.manager.util.PathSafety.requireSafeFileName(outputName);
                    Path outputFile = destinationDir.resolve(outputName);
                    download.setName(outputName);
                    download.recordOutputPath(outputFile);

                    // Build curl command
                    List<String> command = buildCurlCommand(download, outputFile);

                    // Start the process
                    ProcessBuilder processBuilder = new ProcessBuilder(command);
                    // Don't redirect error stream - we need to read stderr separately for progress
                    // processBuilder.redirectErrorStream(true);

                    registration = launch.start(processBuilder);
                    Process process = registration.process();

                    // Gobble stdout on a daemon thread: the command normally
                    // writes to -o, but if a flag ever routes the document to
                    // stdout an undrained pipe would fill (64K) and deadlock
                    // the transfer
                    Thread stdoutDrain = new Thread(() -> {
                        try {
                            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                        } catch (IOException ignored) {
                            // process died; draining is done
                        }
                    }, "curl-stdout-drain");
                    stdoutDrain.setDaemon(true);
                    stdoutDrain.start();

                    synchronized (download) {
                        // Pause/cancel/replacement wins over this worker's launch result.
                        if (launch.isCancelled()) {
                            return;
                        }
                        if (!download.compareAndSetStatus(Download.Status.CONNECTING,
                                Download.Status.DOWNLOADING)) {
                            registration.terminate(5);
                            return;
                        }
                        if (listener != null) {
                            if (resuming) {
                                listener.onDownloadResume(download);
                            } else {
                                listener.onDownloadStart(download);
                            }
                        }
                        started.complete(download.getId());
                    }

                    // Read process error stream (stderr) to track progress
                    // curl sends progress information to stderr
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                        String line;
                        long lastUpdateTime = System.currentTimeMillis();
                        long lastDownloaded = 0;
                        boolean firstProgressUpdate = true;

                        while ((line = reader.readLine()) != null && !launch.isCancelled()) {
                            // Check for content length
                            Matcher sizeMatcher = TOTAL_SIZE_PATTERN.matcher(line);
                            if (sizeMatcher.find()) {
                                long totalSize = Long.parseLong(sizeMatcher.group(1));
                                download.setSize(totalSize);
                            }

                            // Parse progress
                            Matcher matcher = PROGRESS_PATTERN.matcher(line);
                            if (matcher.matches()) {
                                long totalBytes = Long.parseLong(matcher.group(2));
                                long downloadedBytes = Long.parseLong(matcher.group(4));
                                String speedStr = matcher.group(7);

                                // Convert speed from curl format (with k/m/g suffix) to bytes per second
                                float speedBps = parseSpeed(speedStr);

                                // Set total size if not already set
                                if (download.getSize() == 0 && totalBytes > 0) {
                                    download.setSize(totalBytes);
                                }

                                // Update download stats
                                download.setDownloaded(downloadedBytes);
                                download.setSpeed(speedBps);

                                // Calculate progress
                                float progress = 0;
                                if (download.getSize() > 0) {
                                    progress = (float) downloadedBytes / download.getSize() * 100;
                                }

                                // Throttle progress updates to avoid UI flooding
                                long currentTime = System.currentTimeMillis();
                                long timeDiff = currentTime - lastUpdateTime;
                                long byteDiff = downloadedBytes - lastDownloaded;

                                // Always send progress callback for the first update or when significant
                                // progress is made
                                // or when download is complete
                                boolean shouldUpdate = firstProgressUpdate
                                        || // First update
                                        (timeDiff > 1000)
                                        || // Time-based throttling
                                        (byteDiff > 1024 * 1024)
                                        || // Byte-based throttling
                                        (downloadedBytes == download.getSize() && download.getSize() > 0); // Download
                                // complete

                                if (shouldUpdate) {
                                    if (listener != null) {
                                        listener.onDownloadProgress(download, progress,
                                                downloadedBytes, download.getSize(), speedBps);
                                    }
                                    lastUpdateTime = currentTime;
                                    lastDownloaded = downloadedBytes;
                                    firstProgressUpdate = false; // Mark that we've sent the first update
                                }
                            }
                        }
                    }

                    // Wait for process to complete
                    int exitCode = process.waitFor();

                    synchronized (download) {
                        if (launch.isCancelled()) {
                            return;
                        }

                        // Handle process completion
                        if (exitCode == 0) {
                            if (download.compareAndSetStatus(Download.Status.DOWNLOADING,
                                    Download.Status.COMPLETED) && listener != null) {
                                listener.onDownloadComplete(download);
                            }
                        } else {
                            // Terminal user states always win over late process exit.
                            if (download.compareAndSetStatus(Download.Status.DOWNLOADING,
                                    Download.Status.ERROR)
                                    || download.compareAndSetStatus(Download.Status.CONNECTING,
                                            Download.Status.ERROR)) {
                                download.setErrorMessage("curl process exited with code: " + exitCode);
                                if (listener != null) {
                                    listener.onDownloadError(download, download.getErrorMessage());
                                }
                            }
                        }
                    }
                } catch (CancellationException e) {
                    started.completeExceptionally(e);
                    // Cancellation before launch is an expected terminal path.
                } catch (IOException | InterruptedException | RuntimeException e) {
                    synchronized (download) {
                        if (launch.isCancelled()) {
                            started.completeExceptionally(e);
                            return;
                        }
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        if (download.compareAndSetStatus(Download.Status.DOWNLOADING,
                                Download.Status.ERROR)
                                || download.compareAndSetStatus(Download.Status.CONNECTING,
                                        Download.Status.ERROR)) {
                            download.setErrorMessage("Error during download: " + e.getMessage());
                            if (listener != null) {
                                listener.onDownloadError(download, download.getErrorMessage());
                            }
                        }
                        started.completeExceptionally(e);
                    }
                } finally {
                    if (registration != null && registration.process().isAlive()) {
                        registration.terminate(5);
                    }
                    started.completeExceptionally(new CancellationException("Process launch was stopped"));
                    // Generation-safe cleanup: an old worker whose run was
                    // replaced (pause + resume raced it) must not unregister
                    // the newer process under the same key
                    synchronized (download) {
                        launch.unregister();
                        boolean current = launchFutures.remove(download.getId(), started);
                        if (current) {
                            activeDownloads.remove(download.getId(), download);
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            synchronized (download) {
                launch.unregister();
                if (launchFutures.remove(download.getId(), started)) {
                    activeDownloads.remove(download.getId(), download);
                    if (download.compareAndSetStatus(Download.Status.CONNECTING, Download.Status.ERROR)) {
                        download.setErrorMessage("Could not submit download process");
                        if (listener != null) listener.onDownloadError(download, download.getErrorMessage());
                    }
                }
            }
            started.completeExceptionally(e);
        }
        return started;
    }

    /**
     * Builds the curl command with appropriate options.
     *
     * @param download   The download to create a command for
     * @param outputFile The output file path
     * @return List of command arguments
     */
    List<String> buildCurlCommand(Download download, Path outputFile) {
        List<String> command = new ArrayList<>();

        // Add curl executable
        command.add(curlPath);

        // Get settings (use existing or default)
        CurlSettings settings = switch (download.getSettings()) {
            case CurlSettings curlSettings ->
                curlSettings;
            case null, default ->
                new CurlSettings();
        };

        // Basic options
        if (settings.isFollowRedirects()) {
            command.add("-L"); // Follow redirects
        }

        if (settings.isResumeDownloads()) {
            command.add("-C");
            command.add("-"); // Resume downloads
        }

        // Enable progress reporting - curl's default numerical progress format
        // This provides parseable progress information in the stderr output
        // Format: % Total % Received % Xferd Average Speed Time Time Time Current
        // Dload Upload Total Spent Left Speed
        // 0 0 0 0 0 0 0 0 --:--:-- --:--:-- --:--:-- 0
        if (settings.isCreateDirs()) {
            command.add("--create-dirs"); // Create directories in output path if needed
        }

        // Add proxy if specified
        if (settings.isUseProxy() && settings.getProxyAddress() != null) {
            command.add("-x");
            command.add(settings.getProxyAddress());
        }

        // Add output file
        command.add("-o");
        command.add(outputFile.toString());

        // Add connection options
        command.add("--connect-timeout");
        command.add(String.valueOf(settings.getConnectTimeout()));

        // Add retry options
        command.add("--retry");
        command.add(String.valueOf(settings.getRetryCount()));
        if (settings.getRetryDelaySeconds() > 0) {
            command.add("--retry-delay");
            command.add(String.valueOf(settings.getRetryDelaySeconds()));
        }
        if (settings.getDownloadLimitKB() > 0) {
            command.add("--limit-rate");
            command.add(settings.getDownloadLimitKB() + "K");
        }

        // Add user agent if specified
        if (settings.getUserAgent() != null) {
            command.add("--user-agent");
            command.add(settings.getUserAgent());
        }

        // Add referer if specified
        if (settings.getReferer() != null) {
            command.add("--referer");
            command.add(settings.getReferer());
        }
        if (settings.getCookieHeader() != null) {
            command.add("--cookie");
            command.add(settings.getCookieHeader().replaceFirst("(?i)^Cookie:\\s*", ""));
        }

        // Opt-in only: a hidden low-speed abort must not terminate legitimate
        // slow transfers in the base profile.
        if (settings.getLowSpeedLimit() > 0 && settings.getLowSpeedTime() > 0) {
            command.add("--speed-limit");
            command.add(String.valueOf(settings.getLowSpeedLimit()));
            command.add("--speed-time");
            command.add(String.valueOf(settings.getLowSpeedTime()));
        }

        // Add max redirects if following redirects
        if (settings.isFollowRedirects()) {
            command.add("--max-redirs");
            command.add(String.valueOf(settings.getMaxRedirects()));
        }

        // Add fail flag to treat HTTP error status codes as errors
        if (settings.isFailOnHttpError()) {
            command.add("--fail");
        }

        // Add insecure mode if enabled
        if (settings.isInsecureMode()) {
            command.add("--insecure");
        }

        // The aria2/proxychains SFTP fallback must retain an explicit host-key
        // pin. Curl supports MD5 pins, but has no SHA-1 pin option.
        if (download.getProtocol() == Download.Protocol.SFTP) {
            String pin = org.aria2.Aria2Settings.normalizeSshHostKeyDigest(
                    settings.getOption("ssh-host-key-md"));
            if (pin.startsWith("sha-1=")) {
                throw new IllegalArgumentException(
                        "Curl cannot enforce an SFTP SHA-1 host-key pin; restore proxychains "
                        + "or configure an MD5 host-key pin to use Curl fallback");
            }
            if (pin.startsWith("md5=")) {
                command.add("--hostpubmd5");
                command.add(pin.substring(4));
            }
        }

        // For backward compatibility, also check legacy options
        // But skip if we already have CurlSettings to avoid duplicates
        if (!(download.getSettings() instanceof CurlSettings)) {
            Map<String, String> options = download.getOptions();
            if (options != null) {
                Map<String, String> legacyCurl = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, String> entry : options.entrySet()) {
                    if (entry.getKey().startsWith("curl.")) {
                        legacyCurl.put(entry.getKey().substring(5), entry.getValue());
                    }
                }
                // Imported settings are untrusted: only allowlisted curl
                // flags survive (--config and friends execute or redirect)
                for (Map.Entry<String, String> entry : org.manager.tools.ToolOptionFilter
                        .filter(org.manager.tools.ToolOptionFilter.Tool.CURL, legacyCurl)
                        .entrySet()) {
                    // Skip progress-bar option since we need numerical progress output for parsing
                    if ("progress-bar".equals(entry.getKey())) {
                        continue;
                    }

                    command.add("--" + entry.getKey());
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        command.add(entry.getValue());
                    }
                }
            }
        }

        // Add the URL
        command.add(download.getUri().toString());

        return command;
    }

    /**
     * Pauses a download by stopping the curl process.
     *
     * @param download The download to pause
     * @param listener Listener for download events
     */
    public void pauseDownload(Download download, DownloadListener listener) {
        synchronized (download) {
            download.setStatus(Download.Status.PAUSED);
            activeProcesses.terminate(download.getId(), 5);
            CompletableFuture<String> pending = launchFutures.get(download.getId());
            if (pending != null) pending.completeExceptionally(new CancellationException("Download paused"));
            if (listener != null) {
                listener.onDownloadPause(download);
            }
        }
    }

    /**
     * Resumes a paused download.
     *
     * @param download The download to resume
     * @param listener Listener for download events
     */
    public CompletableFuture<Void> resumeDownload(Download download, DownloadListener listener) {
        if (download != null && download.getStatus() == Download.Status.PAUSED) {
            return startDownload(download, listener, true).thenApply(id -> null);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Cancels a download by stopping the curl process and deleting the partial
     * file.
     *
     * @param download   The download to cancel
     * @param listener   Listener for download events
     * @param deleteFile Whether to delete the partial file
     */
    public void cancelDownload(Download download, DownloadListener listener, boolean deleteFile) {
        synchronized (download) {
            download.setStatus(Download.Status.CANCELED);
            activeProcesses.terminate(download.getId(), 5);
            CompletableFuture<String> pending = launchFutures.get(download.getId());
            if (pending != null) pending.completeExceptionally(new CancellationException("Download canceled"));

            // Delete partial file if requested. The name comes from the
            // download model and may be stale or corrupted, so both a
            // plain-file-name check and real-path containment must pass before
            // anything is deleted.
            if (deleteFile && download.getDestination() != null) {
                try {
                    Path outputFile = download.getPrimaryOutputPath();
                    if (outputFile != null
                            && org.manager.util.PathSafety.isConfined(outputFile, download.getDestination())) {
                        org.manager.util.PathSafety.deleteIfExistsConfined(outputFile,
                                download.getDestination());
                    } else {
                        LOGGER.warn("Refusing unsafe partial-file deletion for " + download.getId()
                                + ": " + download.getName());
                    }
                } catch (IllegalArgumentException invalidPath) {
                    LOGGER.warn("Refusing unsafe partial-file name for " + download.getId()
                            + ": " + download.getName());
                }
            }

            if (listener != null) {
                listener.onDownloadCanceled(download);
            }

            activeDownloads.remove(download.getId());
        }
    }

    /**
     * Shuts down the client and cancels all active downloads.
     */
    public void shutdown() {
        // Stop all active processes (SIGTERM -> bounded wait -> SIGKILL)
        activeProcesses.terminateAll(5);
        launchFutures.values().forEach(future -> future.completeExceptionally(
                new CancellationException("Download client shut down")));
        launchFutures.clear();
        activeDownloads.clear();

        // Shutdown executor
        executorService.shutdownNow();
    }

    /**
     * Checks if the client has been shut down.
     *
     * @return true if the client has been shut down, false otherwise
     */
    public boolean isShutdown() {
        return executorService.isShutdown();
    }

    /**
     * Checks if curl is installed and available.
     *
     * @return true if curl is available, false otherwise
     */
    public static boolean isCurlAvailable() {
        try {
            // Use the ToolManagerFactory to check if curl is available
            return ApplicationContext.isToolAvailable("curl");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if curl is available at the specified path.
     *
     * @param path The path to the curl executable
     * @return true if curl is available at the specified path, false otherwise
     */
    public static boolean isCurlAvailable(String path) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(path, "--version");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Parses curl speed format (e.g., "2215k", "1.5M", "100") to bytes per
     * second
     *
     * @param speedStr Speed string from curl output
     * @return Speed in bytes per second
     */
    private float parseSpeed(String speedStr) {
        if (speedStr == null || speedStr.trim().isEmpty()) {
            return 0.0f;
        }

        speedStr = speedStr.trim();
        float multiplier = 1.0f;

        // Check for unit suffix
        if (speedStr.endsWith("k") || speedStr.endsWith("K")) {
            multiplier = 1024.0f;
            speedStr = speedStr.substring(0, speedStr.length() - 1);
        } else if (speedStr.endsWith("m") || speedStr.endsWith("M")) {
            multiplier = 1024.0f * 1024.0f;
            speedStr = speedStr.substring(0, speedStr.length() - 1);
        } else if (speedStr.endsWith("g") || speedStr.endsWith("G")) {
            multiplier = 1024.0f * 1024.0f * 1024.0f;
            speedStr = speedStr.substring(0, speedStr.length() - 1);
        }

        try {
            float speed = Float.parseFloat(speedStr);
            return speed * multiplier;
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }
}
