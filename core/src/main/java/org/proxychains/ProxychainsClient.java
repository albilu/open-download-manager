package org.proxychains;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.manager.ApplicationContext;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.tools.ToolManagerFactory;

/**
 * A client for executing downloads through proxychains. This class manages the
 * execution of commands through the proxychains proxy wrapper.
 */
public class ProxychainsClient {

    private static final Logger LOGGER = Logger.getLogger(ProxychainsClient.class.getName());
    private static final Pattern ARIA2_PROGRESS_PATTERN = Pattern.compile(
            "\\[#([0-9a-f]+)\\s+([0-9.]+)([KMGTkmgt]?i?)B/([0-9.]+)([KMGTkmgt]?i?)B\\(([0-9.]+)%\\).*");
    private static final Pattern SPEED_PATTERN = Pattern.compile(
            ".*DL:([0-9.]+)([KMGTkmgt]?i?)B(/s)?.*");

    private final String proxychainsPath;
    private final String configPath;
    private final ExecutorService executorService;
    private final Map<String, Process> activeProcesses;
    private final Map<String, String> gidMap; // Download ID -> aria2 GID
    private final Map<String, Download> activeDownloads;

    /**
     * Gets the ToolManagerFactory instance using ApplicationContext.
     */
    private static ToolManagerFactory getToolManagerFactory() {
        return ApplicationContext.getToolManagerFactory();
    }

    /**
     * Creates a new ProxychainsClient with the default proxychains command path
     * from ToolManagerFactory.
     */
    public ProxychainsClient() {
        this(getProxychainsPath(), null);
    }

    /**
     * Gets the proxychains path using the ToolManagerFactory.
     */
    private static String getProxychainsPath() {
        try {
            ToolManagerFactory factory = getToolManagerFactory();
            if (factory != null) {
                ProxychainsToolManager proxychainsManager = factory.getProxychainsManager();
                if (proxychainsManager != null) {
                    return proxychainsManager.getToolPath();
                }
            }

            // Final fallback - try system proxychains4
            return "proxychains4";
        } catch (Exception e) {
            // Final fallback - try system proxychains4
            return "proxychains4";
        }
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
        this.proxychainsPath = proxychainsPath;
        this.configPath = configPath;
        this.executorService = Executors.newCachedThreadPool();
        this.activeProcesses = new ConcurrentHashMap<>();
        this.gidMap = new ConcurrentHashMap<>();
        this.activeDownloads = new ConcurrentHashMap<>();

        // Validate that proxychains is available
        validateProxychainsInstallation();
    }

    /**
     * Validates that proxychains is installed and available.
     *
     * @throws RuntimeException if proxychains is not available
     */
    private void validateProxychainsInstallation() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(proxychainsPath, "-h");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Read output to prevent process hanging
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Just read the line to consume output
                }
            }

            int exitCode = process.waitFor();

            if (exitCode != 0 && exitCode != 1) { // Some versions return 1 for help
                throw new RuntimeException("proxychains command failed with exit code: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
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
    public void startDownload(Download download, DownloadListener listener, Map<String, String> options) {
        if (download == null || download.getUri() == null) {
            if (listener != null) {
                listener.onDownloadError(download, "Invalid download or URI is null");
            }
            return;
        }

        // Set download status to connecting
        download.setStatus(Download.Status.CONNECTING);

        // Add to active downloads
        activeDownloads.put(download.getId(), download);

        // Start download in a separate thread
        executorService.submit(() -> {
            Process process = null;
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
                Path outputFile = destinationDir.resolve(download.getName());

                // Get or create proxychains config
                Path configPath = this.configPath != null ? Paths.get(this.configPath) : null;

                if (configPath == null && download.isUseProxy() && download.getProxyAddress() != null) {
                    // Parse proxy address (format: socks5h://127.0.0.1:9050)
                    String proxyAddress = download.getProxyAddress();
                    String[] parts = proxyAddress.split("://");
                    if (parts.length == 2) {
                        String proxyType = parts[0].replace("h", ""); // Convert socks5h to socks5
                        String[] hostPort = parts[1].split(":");
                        if (hostPort.length == 2) {
                            String host = hostPort[0];
                            int port = Integer.parseInt(hostPort[1]);
                            configPath = createTempConfig(proxyType, host, port);
                        }
                    }
                }

                // Build command for aria2c through proxychains
                List<String> command = buildProxychainsCommand(download, outputFile, configPath, options);

                // Start the process
                ProcessBuilder processBuilder = new ProcessBuilder(command);
                // Don't redirect error stream - read both separately

                LOGGER.info("Executing command: " + String.join(" ", command));
                process = processBuilder.start();
                final Process finalProcess = process; // Make final for lambda usage
                activeProcesses.put(download.getId(), process);

                // Update download status
                download.setStatus(Download.Status.DOWNLOADING);
                if (listener != null) {
                    listener.onDownloadStart(download);
                }

                // Read both stdout and stderr in separate threads
                Thread stderrReader = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(finalProcess.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            LOGGER.info("[ARIA2 STDERR]: " + line);
                            processAria2Output(line, download, listener);
                        }
                    } catch (IOException e) {
                        LOGGER.severe("Error reading stderr: " + e.getMessage());
                    }
                });
                stderrReader.setDaemon(true);
                stderrReader.start();

                // Read process stdout to track progress
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        LOGGER.info("[ARIA2 STDOUT]: " + line);
                        processAria2Output(line, download, listener);
                    }
                }

                // Wait for process to complete
                int exitCode = process.waitFor();

                // Handle process completion
                if (exitCode == 0) {
                    download.setStatus(Download.Status.COMPLETED);
                    if (listener != null) {
                        listener.onDownloadComplete(download);
                    }
                } else {
                    download.setStatus(Download.Status.ERROR);
                    download.setErrorMessage("proxychains process exited with code: " + exitCode);
                    if (listener != null) {
                        listener.onDownloadError(download, download.getErrorMessage());
                    }
                }
            } catch (IOException | InterruptedException e) {
                // Handle errors
                download.setStatus(Download.Status.ERROR);
                download.setErrorMessage("Error during download: " + e.getMessage());
                if (listener != null) {
                    listener.onDownloadError(download, download.getErrorMessage());
                }
            } finally {
                // Clean up
                activeProcesses.remove(download.getId());
                gidMap.remove(download.getId());
                activeDownloads.remove(download.getId());
            }
        });
    }

    /**
     * Extracts the GID from an aria2c output line.
     *
     * @param line The output line containing the GID
     * @return The extracted GID or null if not found
     */
    private String extractGid(String line) {
        Pattern pattern = Pattern.compile("GID#([0-9a-f]+)");
        Matcher matcher = pattern.matcher(line);
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
    private float parseSpeed(String value, String unit) {
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
    private List<String> buildProxychainsCommand(Download download, Path outputFile, Path configPath,
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
        command.add(ApplicationContext.getToolPath("aria2"));

        // Basic aria2c options
        command.add("--allow-overwrite=true");
        command.add("--file-allocation=none");
        command.add("--max-connection-per-server=" + download.getConnections());
        command.add("--async-dns=false"); // Important for proxychains
        // progress
        command.add("--summary-interval=1"); // Show progress every 1 second
        command.add("--console-log-level=notice"); // More verbose output to see progress
        command.add("--check-certificate=false"); // Optional, makes HTTPS more reliable with some proxies
        command.add("--human-readable=false"); // Use exact byte values
        command.add("--show-console-readout=true"); // Force console progress display

        // Set download directory and filename
        command.add("-d");
        command.add(outputFile.getParent().toString());
        command.add("-o");
        command.add(outputFile.getFileName().toString());

        // Add any custom options
        if (options != null) {
            for (Map.Entry<String, String> entry : options.entrySet()) {
                if (entry.getKey().startsWith("aria2.")) {
                    String option = entry.getKey().substring(6); // Remove "aria2." prefix
                    command.add("--" + option + "=" + entry.getValue());
                }
            }
        }

        // Add mirrors if any
        if (download.getMirrors() != null && !download.getMirrors().isEmpty()) {
            for (java.net.URI mirror : download.getMirrors()) {
                command.add(mirror.toString());
            }
        }

        // Add the URL
        command.add(download.getUri().toString());

        return command;
    }

    /**
     * Pauses a download by sending a pause signal to the aria2c process.
     *
     * @param download The download to pause
     * @param listener Listener for download events
     */
    public void pauseDownload(Download download, DownloadListener listener) {
        String gid = gidMap.get(download.getId());
        Process process = activeProcesses.get(download.getId());

        if (process != null) {
            try {
                if (gid != null) {
                    // Try to pause aria2c download with GID
                    List<String> command = new ArrayList<>();
                    command.add("aria2c");
                    command.add("--force-pause");
                    command.add("gid=" + gid);

                    ProcessBuilder pauseBuilder = new ProcessBuilder(command);
                    pauseBuilder.start().waitFor();
                } else {
                    // If GID is not available, kill the process
                    process.destroy();
                }

                activeProcesses.remove(download.getId());

                download.setStatus(Download.Status.PAUSED);
                if (listener != null) {
                    listener.onDownloadPause(download);
                }
            } catch (IOException | InterruptedException e) {
                LOGGER.log(Level.SEVERE, "Failed to pause download", e);
                // Force kill if gentle pause fails
                process.destroy();
                activeProcesses.remove(download.getId());

                download.setStatus(Download.Status.PAUSED);
                if (listener != null) {
                    listener.onDownloadPause(download);
                }
            }
        }
    }

    /**
     * Resumes a paused download.
     *
     * @param download The download to resume
     * @param listener Listener for download events
     * @param options  Additional aria2c options
     */
    public void resumeDownload(Download download, DownloadListener listener, Map<String, String> options) {
        if (download.getStatus() == Download.Status.PAUSED) {
            // Just restart the download with continue option
            startDownload(download, listener, options);

            if (listener != null) {
                listener.onDownloadResume(download);
            }
        }
    }

    /**
     * Cancels a download by stopping the proxychains process.
     *
     * @param download   The download to cancel
     * @param listener   Listener for download events
     * @param deleteFile Whether to delete the partial file
     */
    public void cancelDownload(Download download, DownloadListener listener, boolean deleteFile) {
        Process process = activeProcesses.get(download.getId());
        if (process != null) {
            process.destroy();
            activeProcesses.remove(download.getId());
        }

        // Remove GID mapping
        gidMap.remove(download.getId());

        // Delete partial file if requested
        if (deleteFile && download.getDestination() != null) {
            Path outputFile = download.getDestination().resolve(download.getName());
            try {
                Files.deleteIfExists(outputFile);

                // Also delete aria2 control file
                Path controlFile = Paths.get(outputFile.toString() + ".aria2");
                Files.deleteIfExists(controlFile);
            } catch (IOException e) {
                // Log error but continue
                LOGGER.log(Level.WARNING, "Failed to delete partial file: " + e.getMessage(), e);
            }
        }

        download.setStatus(Download.Status.CANCELED);
        if (listener != null) {
            listener.onDownloadCanceled(download);
        }

        activeDownloads.remove(download.getId());
    }

    /**
     * Shuts down the client and cancels all active downloads.
     */
    public void shutdown() {
        // Stop all active processes
        for (Process process : activeProcesses.values()) {
            process.destroy();
        }

        // Clear maps
        activeProcesses.clear();
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
    public static boolean isProxychainsAvailable() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("proxychains", "-h");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Read output to prevent process hanging
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Just read the line to consume output
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0 || exitCode == 1; // Some versions return 1 for help
        } catch (IOException | InterruptedException e) {
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

        // Also log lines that might contain progress info for debugging
        if (line.contains("#") || line.contains("%") || line.contains("DL:")) {
            LOGGER.info("[POTENTIAL PROGRESS]: " + line);
        }

        // Parse progress information
        Matcher progressMatcher = ARIA2_PROGRESS_PATTERN.matcher(line);
        if (progressMatcher.find()) {
            LOGGER.info("[PROGRESS MATCHED]: " + line);
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
}
