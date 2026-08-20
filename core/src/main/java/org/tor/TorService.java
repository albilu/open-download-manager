package org.tor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service class to manage Tor process lifecycle. Handles starting, stopping,
 * restarting Tor with custom configurations.
 */
public class TorService {

    private static final Logger LOGGER = Logger.getLogger(TorService.class.getName());

    // Default Tor configuration
    private static final int DEFAULT_SOCKS_PORT = 9050;
    private static final int DEFAULT_CONTROL_PORT = 9051;
    private static final String DEFAULT_DATA_DIR = System.getProperty("java.io.tmpdir") + "/tor-odm";
    private static final String DEFAULT_LOG_LEVEL = "notice";

    // Process management
    private final AtomicReference<Process> torProcess = new AtomicReference<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    /** Serializes start(): concurrent callers coalesce onto one process. */
    private final Object startLock = new Object();

    // Configuration
    private final Map<String, String> torConfig;
    private final Path configFile;
    private final Path dataDirectory;
    private final String torExecutablePath;
    private final ExecutorService executorService;

    // Monitoring
    private final List<TorServiceListener> listeners;
    private final CompletableFuture<Void> shutdownFuture;

    /**
     * Creates a new TorService with default configuration.
     *
     * @param torExecutablePath Path to the tor executable
     */
    public TorService(String torExecutablePath) {
        this(torExecutablePath, new HashMap<>(), null);
    }

    /**
     * Creates a new TorService with custom configuration.
     *
     * @param torExecutablePath Path to the tor executable
     * @param customConfig      Custom Tor configuration options
     * @param configFilePath    Optional path to existing torrc file
     */
    public TorService(String torExecutablePath, Map<String, String> customConfig, Path configFilePath) {
        this.torExecutablePath = torExecutablePath;
        this.torConfig = new HashMap<>(getDefaultConfig());
        this.torConfig.putAll(customConfig);
        this.configFile = configFilePath != null ? configFilePath : createTempConfigFile();
        this.dataDirectory = Paths.get(this.torConfig.getOrDefault("DataDirectory", DEFAULT_DATA_DIR));
        this.listeners = new ArrayList<>();
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "TorService-Worker");
            t.setDaemon(true);
            return t;
        });
        this.shutdownFuture = new CompletableFuture<>();

        // Ensure data directory exists
        createDataDirectory();
    }

    /**
     * Starts the Tor service.
     *
     * @return CompletableFuture that completes when Tor is ready or fails to
     *         start
     */
    public CompletableFuture<Boolean> start() {
        synchronized (startLock) {
            if (isRunning.get()) {
                LOGGER.info("Tor service is already running");
                return CompletableFuture.completedFuture(true);
            }

            return CompletableFuture.supplyAsync(() -> {
                synchronized (startLock) {
                    if (isRunning.get()) {
                        // A concurrent start call already launched Tor;
                        // coalesce instead of spawning a duplicate process
                        // that dies on the port bind and breaks isHealthy()
                        return true;
                    }
                    // A previous stop() left isShuttingDown latched; a new
                    // lifecycle clears it (the output monitors check it, and
                    // with it stuck true nobody drains tor's pipes, so the
                    // relaunched process never becomes ready). After a real
                    // shutdown() the executor is gone: refuse to resurrect.
                    if (executorService.isShutdown()) {
                        LOGGER.severe("Cannot start Tor service: executor is shut down");
                        return false;
                    }
                    isShuttingDown.set(false);
                    try {
                        LOGGER.info("Starting Tor service...");

                        // Write configuration to file
                        writeConfigFile();

                        // Build command
                        List<String> command = buildTorCommand();

                        // Start process
                        ProcessBuilder processBuilder = new ProcessBuilder(command);
                        processBuilder.environment().put("HOME", System.getProperty("user.home"));
                        // Don't redirect error stream - we'll handle stdout and stderr separately

                        Process process = processBuilder.start();
                        torProcess.set(process);

                        // Monitor process output
                        startOutputMonitoring(process);

                        // Wait for Tor to be ready
                        boolean ready = waitForTorReady(30); // 30 seconds timeout

                        if (ready) {
                            isRunning.set(true);
                            notifyListeners(TorServiceEvent.STARTED);
                            LOGGER.info("Tor service started successfully");
                            return true;
                        } else {
                            LOGGER.severe("Tor failed to start within timeout");
                            stopInternal();
                            return false;
                        }

                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Failed to start Tor service", e);
                        stopInternal();
                        return false;
                    }
                }
            }, executorService);
        }
    }

    /**
     * Stops the Tor service.
     *
     * @return true if stopped successfully, false otherwise
     */
    public boolean stop() {
        if (!isRunning.get()) {
            LOGGER.info("Tor service is not running");
            return true;
        }

        return stopInternal();
    }

    /**
     * Restarts the Tor service.
     *
     * @return CompletableFuture that completes when restart is finished
     */
    public CompletableFuture<Boolean> restart() {
        return CompletableFuture.supplyAsync(() -> {
            LOGGER.info("Restarting Tor service...");

            if (isRunning.get()) {
                if (!stop()) {
                    LOGGER.severe("Failed to stop Tor service during restart");
                    return false;
                }

                // Wait a bit before restarting
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            try {
                return start().get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to restart Tor service", e);
                return false;
            }
        }, executorService);
    }

    /**
     * Checks if Tor service is running and responsive.
     *
     * @return true if service is running and responsive
     */
    public boolean isHealthy() {
        if (!isRunning.get()) {
            return false;
        }

        Process process = torProcess.get();
        if (process == null || !process.isAlive()) {
            isRunning.set(false);
            return false;
        }

        // Test SOCKS connection
        return testSocksConnection();
    }

    /**
     * Gets the current Tor configuration.
     *
     * @return Map of configuration options
     */
    public Map<String, String> getConfiguration() {
        return new HashMap<>(torConfig);
    }

    /**
     * Updates Tor configuration and restarts if necessary.
     *
     * @param newConfig New configuration options
     * @return CompletableFuture that completes when configuration is updated
     */
    public CompletableFuture<Boolean> updateConfiguration(Map<String, String> newConfig) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                torConfig.putAll(newConfig);

                if (isRunning.get()) {
                    return restart().get(60, TimeUnit.SECONDS);
                } else {
                    writeConfigFile();
                    return true;
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to update Tor configuration", e);
                return false;
            }
        }, executorService);
    }

    /**
     * Gets the SOCKS proxy port.
     *
     * @return SOCKS proxy port
     */
    public int getSocksPort() {
        return Integer.parseInt(torConfig.getOrDefault("SocksPort", String.valueOf(DEFAULT_SOCKS_PORT)));
    }

    /**
     * Gets the control port.
     *
     * @return Control port
     */
    public int getControlPort() {
        return Integer.parseInt(torConfig.getOrDefault("ControlPort", String.valueOf(DEFAULT_CONTROL_PORT)));
    }

    /**
     * Adds a service listener.
     *
     * @param listener The listener to add
     */
    public void addListener(TorServiceListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a service listener.
     *
     * @param listener The listener to remove
     */
    public void removeListener(TorServiceListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    /**
     * Shuts down the service and releases resources.
     */
    public void shutdown() {
        if (isShuttingDown.getAndSet(true)) {
            return;
        }

        LOGGER.info("Shutting down Tor service...");

        stop();

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Clean up temporary files
        try {
            if (configFile != null && Files.exists(configFile)) {
                Files.deleteIfExists(configFile);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to clean up config file", e);
        }

        shutdownFuture.complete(null);
        LOGGER.info("Tor service shutdown complete");
    }

    /**
     * Returns a future that completes when shutdown is finished.
     */
    public CompletableFuture<Void> getShutdownFuture() {
        return shutdownFuture;
    }

    // Private helper methods
    private Map<String, String> getDefaultConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("SocksPort", String.valueOf(DEFAULT_SOCKS_PORT));
        config.put("ControlPort", String.valueOf(DEFAULT_CONTROL_PORT));
        config.put("DataDirectory", DEFAULT_DATA_DIR);
        config.put("Log", DEFAULT_LOG_LEVEL + " stdout");
        config.put("SafeLogging", "1");
        config.put("StrictNodes", "1");
        config.put("CookieAuthentication", "1");
        return config;
    }

    private Path createTempConfigFile() {
        try {
            return Files.createTempFile("torrc-odm-", ".conf");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temporary config file", e);
        }
    }

    private void createDataDirectory() {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Tor data directory: " + dataDirectory, e);
        }
    }

    private void writeConfigFile() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : torConfig.entrySet()) {
            lines.add(entry.getKey() + " " + entry.getValue());
        }

        Files.write(configFile, lines, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        LOGGER.fine("Tor configuration written to: " + configFile);
    }

    private List<String> buildTorCommand() {
        List<String> command = new ArrayList<>();
        command.add(torExecutablePath);
        command.add("-f");
        command.add(configFile.toString());
        return command;
    }

    private void startOutputMonitoring(Process process) {
        // Monitor stdout
        executorService.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && !isShuttingDown.get()) {
                    LOGGER.fine("Tor stdout: " + line);
                    LOGGER.info(line);

                    // Notify listeners of important events
                    if (line.contains("Bootstrapped 100%")) {
                        notifyListeners(TorServiceEvent.BOOTSTRAP_COMPLETE);
                    } else if (line.contains("Bootstrapped")) {
                        notifyListeners(TorServiceEvent.BOOTSTRAP_PROGRESS);
                    } else if (line.contains("[err]") || line.contains("[warn]")) {
                        notifyListeners(TorServiceEvent.ERROR);
                    }
                }
            } catch (IOException e) {
                if (!isShuttingDown.get()) {
                    LOGGER.log(Level.WARNING, "Error reading Tor stdout", e);
                }
            }
        });

        // Monitor stderr
        executorService.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null && !isShuttingDown.get()) {
                    LOGGER.warning("Tor stderr: " + line);

                    // All stderr output is considered an error condition
                    notifyListeners(TorServiceEvent.ERROR);
                }
            } catch (IOException e) {
                if (!isShuttingDown.get()) {
                    LOGGER.log(Level.WARNING, "Error reading Tor stderr", e);
                }
            }
        });
    }

    private boolean waitForTorReady(int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (testSocksConnection()) {
                return true;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    private boolean testSocksConnection() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", getSocksPort()), 5000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean stopInternal() {
        isShuttingDown.set(true);

        Process process = torProcess.getAndSet(null);
        if (process != null) {
            try {
                // Try graceful shutdown first
                process.destroy();

                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    // Force kill if needed
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }

                isRunning.set(false);
                notifyListeners(TorServiceEvent.STOPPED);
                LOGGER.info("Tor service stopped");
                return true;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                isRunning.set(false);
                return false;
            }
        }

        isRunning.set(false);
        return true;
    }

    private void notifyListeners(TorServiceEvent event) {
        synchronized (listeners) {
            for (TorServiceListener listener : listeners) {
                try {
                    listener.onServiceEvent(event);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error notifying listener", e);
                }
            }
        }
    }

    /**
     * Service event types.
     */
    public enum TorServiceEvent {
        STARTED,
        STOPPED,
        BOOTSTRAP_PROGRESS,
        BOOTSTRAP_COMPLETE,
        ERROR
    }

    /**
     * Interface for listening to Tor service events.
     */
    public interface TorServiceListener {

        void onServiceEvent(TorServiceEvent event);
    }
}
