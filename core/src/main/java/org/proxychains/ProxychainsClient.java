package org.proxychains;

import org.manager.tools.ExternalProcessRegistry;
import org.manager.tools.ToolPaths;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.tools.ToolManagerFactory;

/**
 * A client for executing downloads through proxychains. This class manages the
 * execution of commands through the proxychains proxy wrapper.
 */
public class ProxychainsClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxychainsClient.class);
    private static final Pattern ARIA2_PROGRESS_PATTERN = Pattern.compile(
            "\\[#([0-9a-f]+)\\s+([0-9.]+)([KMGTkmgt]?i?)B/([0-9.]+)([KMGTkmgt]?i?)B\\(([0-9.]+)%\\).*");
    private static final Pattern SPEED_PATTERN = Pattern.compile(
            ".*DL:([0-9]+(?:\\.[0-9]+)?)([KMGTkmgt]?i?)B(/s)?.*");
    private static final Pattern UPLOAD_SPEED_PATTERN = Pattern.compile(
            ".*UL:([0-9]+(?:\\.[0-9]+)?)([KMGTkmgt]?i?)B(/s)?.*");
    private static final Set<String> PROXYCHAIN_OWNED_ARIA2_OPTIONS = Set.of(
            "all-proxy", "dir", "out", "allow-overwrite", "file-allocation");

    private final String proxychainsPath;
    private final String configPath;
    private volatile boolean honorExternalAria2Configuration;
    private volatile String torrentListenPorts = "";
    private final ExecutorService executorService;
    private final org.manager.tools.ExternalProcessRegistry activeProcesses;
    private final Map<String, String> gidMap; // Download ID -> aria2 GID
    private final Map<String, Download> activeDownloads;
    /** Also identifies the worker run, so a late cleanup cannot remove a resumed download. */
    private final Map<String, CompletableFuture<String>> launchFutures = new ConcurrentHashMap<>();

    /**
     * Creates a new ProxychainsClient with the default proxychains command path
     * from ToolManagerFactory.
     */
    public ProxychainsClient() {
        this(ToolPaths.proxychains(), null, false);
    }


    /**
     * Creates a new ProxychainsClient with the specified proxychains command
     * path.
     *
     * @param proxychainsPath Path to the proxychains executable
     * @param configPath      Path to the proxychains configuration file, or null to
     *                        use default
     */
    public ProxychainsClient(String proxychainsPath, String configPath) {
        this(proxychainsPath, configPath, false);
    }

    public ProxychainsClient(String proxychainsPath, String configPath,
            boolean honorExternalAria2Configuration) {
        this.proxychainsPath = proxychainsPath;
        this.configPath = configPath;
        this.honorExternalAria2Configuration = honorExternalAria2Configuration;
        // Daemon threads: a missed shutdown() must never keep the JVM alive
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "proxychains-client");
            t.setDaemon(true);
            return t;
        });
        this.activeProcesses = new org.manager.tools.ExternalProcessRegistry("proxychains");
        this.gidMap = new ConcurrentHashMap<>();
        this.activeDownloads = new ConcurrentHashMap<>();

        // Validate that proxychains is available
        validateProxychainsInstallation();
    }

    public void setHonorExternalAria2Configuration(boolean honor) {
        this.honorExternalAria2Configuration = honor;
    }

    public void setTorrentListenPorts(String ports) {
        this.torrentListenPorts = org.aria2.Aria2GlobalOptions.normalizeListenPorts(ports);
    }

    /**
     * Validates that proxychains is installed and available.
     *
     * @throws RuntimeException if proxychains is not available
     */
    private void validateProxychainsInstallation() {
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(proxychainsPath, "-h");
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();

            // Bounded wait: a hung binary must fail validation instead of
            // blocking construction (this runs on startup paths). Draining
            // output before the wait would itself block forever on a binary
            // that never closes its stream; -h output is far below the pipe
            // capacity so no pre-drain is needed.
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("proxychains at path '" + proxychainsPath
                        + "' did not respond within 10 seconds");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 && exitCode != 1) { // Some versions return 1 for help
                throw new RuntimeException("proxychains command failed with exit code: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to execute proxychains. Make sure it's installed and available in PATH.",
                    e);
        }
    }

    /**
     * Creates a temporary proxychains configuration file with the specified
     * proxy settings.
     *
     * @param proxyType The type of proxy (socks5, socks4, http, etc.)
     * @param proxyHost The proxy host address
     * @param proxyPort The proxy port
     * @return Path to the created configuration file
     * @throws IOException if the configuration file cannot be created
     */
    public Path createTempConfig(String proxyType, String proxyHost, int proxyPort) throws IOException {
        Path configFile = Files.createTempFile("proxychains_", ".conf");

        List<String> lines = new ArrayList<>();
        lines.add("dynamic_chain"); // Use dynamic chain
        lines.add("proxy_dns"); // Proxy DNS requests
        lines.add("tcp_read_time_out 15000");
        lines.add("tcp_connect_time_out 8000");
        lines.add("");
        lines.add("[ProxyList]");
        lines.add(String.format("%s %s %d", proxyType, proxyHost, proxyPort));

        Files.write(configFile, lines, StandardCharsets.UTF_8);
        configFile.toFile().deleteOnExit(); // Clean up when the JVM exits

        return configFile;
    }

    /**
     * Starts a download using aria2c through proxychains.
     *
     * @param download The download to start
     * @param listener Listener for download events
     * @param options  Additional aria2c options
     */
    public CompletableFuture<String> startDownload(Download download, DownloadListener listener, Map<String, String> options) {
        return startDownload(download, listener, options, false);
    }

    private CompletableFuture<String> startDownload(Download download, DownloadListener listener, Map<String, String> options, boolean resuming) {
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
                Process process = null;
                Path generatedConfig = null;
                org.manager.tools.ExternalProcessRegistry.Registration registration = null;
                try {
                    // Paused before the process spawned (pause raced the async
                    // start): abort so the paused state sticks
                    if (download.getStatus() == Download.Status.PAUSED) {
                        LOGGER.info("Download " + download.getId() + " was paused before spawning; aborting start");
                        return;
                    }

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

                    // Get or create proxychains config
                    Path configPath = this.configPath != null ? Paths.get(this.configPath) : null;

                    if (download.isUseProxy()) {
                        generatedConfig = ProxychainsConfig.forProxyAddress(download.getProxyAddress()).createTempConfig();
                        configPath = generatedConfig;
                    }

                    // Build command for aria2c through proxychains
                    List<String> command = buildProxychainsCommand(download, outputFile, configPath, options);

                    // Start the process
                    ProcessBuilder processBuilder = new ProcessBuilder(command);
                    // Don't redirect error stream - read both separately

                    // Do not log the command: it can contain signed URLs and
                    // proxy credentials. The download id is sufficient to trace.
                    LOGGER.info("Starting proxychains process for download " + download.getId());
                    registration = launch.start(processBuilder);
                    process = registration.process();
                    final Process finalProcess = process; // Make final for lambda usage

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

                    // Read both stdout and stderr in separate threads
                    Thread stderrReader = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(finalProcess.getErrorStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null && !launch.isCancelled()) {
                                // Per-line tool output at 1+ lines/second: FINE
                                LOGGER.debug("[ARIA2 STDERR]: " + line);
                                synchronized (download) {
                                    if (!launch.isCancelled()) {
                                        processAria2Output(line, download, listener);
                                    }
                                }
                            }
                        } catch (IOException e) {
                            LOGGER.error("Error reading stderr: " + e.getMessage());
                        }
                    });
                    stderrReader.setDaemon(true);
                    stderrReader.start();

                    // Read process stdout to track progress
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;

                        while ((line = reader.readLine()) != null && !launch.isCancelled()) {
                            // Per-line tool output at 1+ lines/second: FINE
                            LOGGER.debug("[ARIA2 STDOUT]: " + line);
                            synchronized (download) {
                                if (!launch.isCancelled()) {
                                    processAria2Output(line, download, listener);
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
                        } else if (download.getStatus() == Download.Status.PAUSED
                                || download.getStatus() == Download.Status.CANCELED) {
                            // Intentionally stopped by pauseDownload (process destroy):
                            // keep the PAUSED state so a later resume works
                            LOGGER.info("Process of paused download " + download.getId()
                                    + " terminated (exit " + exitCode + ")");
                        } else {
                            if (download.compareAndSetStatus(Download.Status.DOWNLOADING,
                                    Download.Status.ERROR)
                                    || download.compareAndSetStatus(Download.Status.CONNECTING,
                                            Download.Status.ERROR)) {
                                download.setErrorMessage("proxychains process exited with code: " + exitCode);
                                if (listener != null) {
                                    listener.onDownloadError(download, download.getErrorMessage());
                                }
                            }
                        }
                    }
                } catch (CancellationException e) {
                    started.completeExceptionally(e);
                    // Expected when pause/cancel wins before child launch.
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
                    if (generatedConfig != null) {
                        try {
                            Files.deleteIfExists(generatedConfig);
                        } catch (IOException e) {
                            LOGGER.warn("Could not remove temporary proxy configuration", e);
                        }
                    }
                    // Generation-safe cleanup: an old worker whose run was
                    // replaced (pause + resume raced it) must not unregister
                    // the newer process under the same key
                    synchronized (download) {
                        launch.unregister();
                        boolean current = launchFutures.remove(download.getId(), started);
                        if (current) {
                            gidMap.remove(download.getId());
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
     * Extracts the GID from an aria2c output line.
     *
     * @param line The output line containing the GID
     * @return The extracted GID or null if not found
     */
    private static final Pattern GID_PATTERN = Pattern.compile("GID#([0-9a-f]+)");

    private String extractGid(String line) {
        Matcher matcher = GID_PATTERN.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Parses the download speed from the aria2c output.
     *
     * @param value The speed value
     * @param unit  The speed unit (K, M, G)
     * @return The speed in bytes per second
     */
    private static float parseSpeed(String value, String unit) {
        float speed = Float.parseFloat(value);

        if (unit == null || unit.isEmpty()) {
            return speed;
        }

        // Convert to bytes/second based on unit
        return speed * switch (unit.toUpperCase().charAt(0)) {
            case 'K' ->
                1024;
            case 'M' ->
                1024 * 1024;
            case 'G' ->
                1024 * 1024 * 1024;
            default ->
                1; // Already in bytes/sec
        };
    }

    /**
     * Converts a value with unit to bytes.
     *
     * @param value The numeric value
     * @param unit  The unit (K, M, G, Ki, Mi, Gi, etc.)
     * @return The value in bytes
     */
    private long convertToBytes(float value, String unit) {
        if (unit == null || unit.isEmpty()) {
            return (long) value;
        }

        // Handle binary units (Ki, Mi, Gi) and decimal units (K, M, G)
        return (long) switch (unit.toUpperCase().charAt(0)) {
            case 'K' ->
                value * 1024;
            case 'M' ->
                value * 1024 * 1024;
            case 'G' ->
                value * 1024 * 1024 * 1024;
            case 'T' ->
                value * 1024L * 1024L * 1024L * 1024L;
            default ->
                value;
        };
    }

    /**
     * Builds the proxychains command with appropriate options.
     *
     * @param download   The download to create a command for
     * @param outputFile The output file path
     * @param configPath The proxychains configuration file path (may be null)
     * @param options    Additional aria2c options
     * @return List of command arguments
     */
    List<String> buildProxychainsCommand(Download download, Path outputFile, Path configPath,
            Map<String, String> options) {
        List<String> command = new ArrayList<>();

        // Add proxychains executable
        command.add(proxychainsPath);

        // Add config file if specified
        if (configPath != null) {
            command.add("-f");
            command.add(configPath.toString());
        }

        // Add aria2c executable
        command.add(ToolPaths.aria2c());
        if (!honorExternalAria2Configuration) {
            command.add("--no-conf");
        }

        // Basic aria2c options
        command.add("--allow-overwrite=true");
        command.add("--file-allocation=none");
        command.add("--max-connection-per-server=" + download.getConnections());
        command.add("--async-dns=false"); // Important for proxychains
        // progress
        command.add("--summary-interval=1"); // Show progress every 1 second
        command.add("--console-log-level=notice"); // More verbose output to see progress
        command.add("--human-readable=false"); // Use exact byte values
        command.add("--show-console-readout=true"); // Force console progress display

        org.manager.download.ExternalToolSettings common = download.getSettings();
        if (common.getDownloadLimitKB() > 0) {
            command.add("--max-download-limit=" + common.getDownloadLimitKB() + "K");
        }
        if (common.getUploadLimitKB() > 0) {
            command.add("--max-upload-limit=" + common.getUploadLimitKB() + "K");
        }
        if (common.getMaxRetries() > 0) {
            command.add("--max-tries=" + common.getMaxRetries());
        }
        if (common.getRetryDelaySeconds() > 0) {
            command.add("--retry-wait=" + common.getRetryDelaySeconds());
        }
        if (common.getReferer() != null) {
            command.add("--referer=" + common.getReferer());
        }
        if (common.getUserAgent() != null) {
            command.add("--user-agent=" + common.getUserAgent());
        }
        if (common.getCookieHeader() != null) {
            command.add("--header=" + common.getCookieHeader());
        }

        // A SOCKS-routed record retains its original Aria2Settings even
        // though the handler type becomes PROXYCHAINS. Carry the safe native
        // options across that route boundary so seeding, encryption, file
        // selection, and SFTP host-key verification do not silently vanish.
        if (download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings) {
            Map<String, String> nativeOptions = new java.util.LinkedHashMap<>();
            aria2Settings.toRpcOptions().forEach((key, value) -> {
                if (!PROXYCHAIN_OWNED_ARIA2_OPTIONS.contains(key) && value != null) {
                    nativeOptions.put(key, value.toString());
                }
            });
            appendFilteredAria2Options(command, nativeOptions);
        }

        // Set download directory and filename
        command.add("-d");
        command.add(outputFile.getParent().toString());
        boolean localDescriptor = "file".equalsIgnoreCase(download.getUri().getScheme())
                && (download.getProtocol() == Download.Protocol.TORRENT
                    || download.getProtocol() == Download.Protocol.METALINK);
        if (!localDescriptor) {
            command.add("-o");
            command.add(outputFile.getFileName().toString());
        }

        // Add any custom options; imported settings are untrusted, so only
        // allowlisted aria2 options survive (--on-download-* hooks execute
        // arbitrary commands)
        if (options != null) {
            Map<String, String> aria2Options = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, String> entry : options.entrySet()) {
                if (entry.getKey().startsWith("aria2.")) {
                    aria2Options.put(entry.getKey().substring(6), entry.getValue());
                }
            }
            appendFilteredAria2Options(command, aria2Options);
        }

        String listenPorts = torrentListenPorts;
        if (!listenPorts.isEmpty()) {
            command.add("--listen-port=" + listenPorts);
            command.add("--dht-listen-port=" + listenPorts);
        }

        // DHT, PEX and LPD bypass a TCP proxy. These final arguments must win
        // over both an honored external config and per-download preferences.
        command.add("--enable-dht=false");
        command.add("--enable-dht6=false");
        command.add("--enable-peer-exchange=false");
        command.add("--bt-enable-lpd=false");

        // Add mirrors if any
        if (download.getMirrors() != null && !download.getMirrors().isEmpty()) {
            for (java.net.URI mirror : download.getMirrors()) {
                command.add(mirror.toString());
            }
        }

        if (localDescriptor) {
            Path descriptor = Path.of(download.getUri()).toAbsolutePath();
            if (!Files.isRegularFile(descriptor) || !Files.isReadable(descriptor)) {
                throw new IllegalArgumentException("Local download descriptor is unavailable");
            }
            command.add((download.getProtocol() == Download.Protocol.TORRENT
                    ? "--torrent-file=" : "--metalink-file=") + descriptor);
        } else {
            command.add(download.getUri().toString());
        }

        return command;
    }

    private static void appendFilteredAria2Options(List<String> command,
            Map<String, String> options) {
        for (Map.Entry<String, String> entry : org.manager.tools.ToolOptionFilter
                .filter(org.manager.tools.ToolOptionFilter.Tool.ARIA2, options)
                .entrySet()) {
            command.add("--" + entry.getKey() + "=" + entry.getValue());
        }
    }

    /**
     * Pauses a download by terminating the wrapped aria2c process. A
     * standalone aria2c launched through proxychains has no RPC channel, so
     * there is nothing to send a pause command to: SIGTERM lets aria2c save
     * its .aria2 control file and exit, and resume restarts the transfer
     * where it left off (aria2c continues partial downloads by default).
     *
     * @param download The download to pause
     * @param listener Listener for download events
     */
    public void pauseDownload(Download download, DownloadListener listener) {
        synchronized (download) {
            download.setStatus(Download.Status.PAUSED);
            // Graceful terminate (SIGTERM: aria2c saves its control file),
            // escalating to a hard kill if it ignores the signal
            activeProcesses.terminate(download.getId(), 5);
            CompletableFuture<String> pending = launchFutures.get(download.getId());
            if (pending != null) pending.completeExceptionally(new CancellationException("Download paused"));

            // Process not spawned (yet) is fine too: the download is trivially
            // paused; reflect it so a subsequent resume works and listeners
            // learn about the pause
            if (listener != null) {
                listener.onDownloadPause(download);
            }
        }
    }

    /**
     * Stops the standalone proxychains/aria2c process for an engine route
     * handoff. The manager owns the subsequent status/type transition, so
     * this deliberately emits no pause or cancellation callback.
     */
    public void stopForRouteChange(Download download) {
        activeProcesses.terminate(download.getId(), 5);
        gidMap.remove(download.getId());
        activeDownloads.remove(download.getId());
    }

    /**
     * Resumes a paused download.
     *
     * @param download The download to resume
     * @param listener Listener for download events
     * @param options  Additional aria2c options
     */
    public CompletableFuture<Void> resumeDownload(Download download, DownloadListener listener, Map<String, String> options) {
        if (download != null && download.getStatus() == Download.Status.PAUSED) {
            return startDownload(download, listener, options, true).thenApply(id -> null);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Cancels a download by stopping the proxychains process.
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

            // Remove GID mapping
            gidMap.remove(download.getId());

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
                        Path controlFile = Paths.get(outputFile.toString() + ".aria2");
                        if (org.manager.util.PathSafety.isConfined(controlFile, download.getDestination())) {
                            org.manager.util.PathSafety.deleteIfExistsConfined(controlFile,
                                    download.getDestination());
                        }
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
        gidMap.clear();
        activeDownloads.clear();

        // Shutdown executor
        executorService.shutdownNow();
    }

    /**
     * Checks if proxychains is installed and available.
     *
     * @return true if proxychains is available, false otherwise
     */
    /**
     * Checks whether a proxychains binary is available. Debian-family
     * systems install the binary as {@code proxychains4} only; probing the
     * legacy name alone reported false negatives exactly there.
     *
     * @return true when either variant answers
     */
    public static boolean isProxychainsAvailable() {
        return isProxychainsAvailable("proxychains4") || isProxychainsAvailable("proxychains");
    }

    /**
     * Checks whether the given proxychains binary answers {@code -h}.
     * Package-private for deterministic availability tests.
     *
     * @param binary the binary name or path to probe
     * @return true when the binary runs and exits with a help-style code
     */
    static boolean isProxychainsAvailable(String binary) {
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(binary, "-h");
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();

            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 || process.exitValue() == 1; // Some versions return 1 for help
        } catch (IOException | InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Processes a line of aria2 output to extract progress information.
     *
     * @param line     The output line from aria2
     * @param download The download being processed
     * @param listener The download listener
     */
    private void processAria2Output(String line, Download download, DownloadListener listener) {
        // Check for GID assignment
        if (line.contains("GID")) {
            String gid = extractGid(line);
            if (gid != null) {
                gidMap.put(download.getId(), gid);
            }
        }

        // Progress-summary lines arrive every second (--summary-interval=1);
        // keep them out of INFO or a single download floods the log
        if (line.contains("#") || line.contains("%") || line.contains("DL:")) {
            LOGGER.debug("[POTENTIAL PROGRESS]: " + line);
        }

        // BitTorrent summaries carry UL independently of the ordinary
        // downloaded/total progress tuple. In particular, seeding lines look
        // like "SEED(...) ... UL:..." and therefore do not match
        // ARIA2_PROGRESS_PATTERN. Apply the upload rate before parsing download
        // progress so both downloading and seeding torrents remain visible.
        float uploadSpeed = parseUploadSpeed(line);
        if (!Float.isNaN(uploadSpeed)) {
            download.setUploadSpeed(uploadSpeed);
        }

        // Parse progress information
        Matcher progressMatcher = ARIA2_PROGRESS_PATTERN.matcher(line);
        if (progressMatcher.find()) {
            LOGGER.debug("[PROGRESS MATCHED]: " + line);
            String gid = progressMatcher.group(1);
            // Store the GID for this download
            gidMap.put(download.getId(), gid);

            // Parse downloaded bytes with unit conversion
            float downloadedValue = Float.parseFloat(progressMatcher.group(2));
            String downloadedUnit = progressMatcher.group(3);
            long downloadedBytes = convertToBytes(downloadedValue, downloadedUnit);

            // Parse total bytes with unit conversion
            float totalValue = Float.parseFloat(progressMatcher.group(4));
            String totalUnit = progressMatcher.group(5);
            long totalBytes = convertToBytes(totalValue, totalUnit);

            float progress = Float.parseFloat(progressMatcher.group(6));

            // Extract speed
            float speed = 0;
            Matcher speedMatcher = SPEED_PATTERN.matcher(line);
            if (speedMatcher.find()) {
                speed = parseSpeed(speedMatcher.group(1), speedMatcher.group(2));
            }

            // Update download stats
            download.setSize(totalBytes);
            download.setDownloaded(downloadedBytes);
            download.setSpeed(speed);

            // Always send progress updates for testing
            if (listener != null) {
                listener.onDownloadProgress(download, progress,
                        downloadedBytes, totalBytes, speed);
            }
        }
    }

    /**
     * Extracts aria2's console UL field in bytes per second.
     *
     * @return the parsed rate, or {@link Float#NaN} when the line has no UL field
     */
    static float parseUploadSpeed(String line) {
        if (line == null) {
            return Float.NaN;
        }
        Matcher matcher = UPLOAD_SPEED_PATTERN.matcher(line);
        return matcher.find()
                ? parseSpeed(matcher.group(1), matcher.group(2))
                : Float.NaN;
    }
}
