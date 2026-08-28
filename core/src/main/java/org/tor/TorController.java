package org.tor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller class for managing Tor service operations. Provides methods to
 * change IP addresses, manage circuits, and control Tor behavior.
 */
public class TorController {

    private static final Logger LOGGER = Logger.getLogger(TorController.class.getName());

    // Control connection settings
    private final String controlHost;
    private final int controlPort;
    private final String controlPassword;
    private final int connectionTimeoutMs;
    private final Path cookieDirectory;

    // Connection management
    private final AtomicReference<Socket> controlSocket = new AtomicReference<>();
    private final AtomicReference<BufferedReader> controlReader = new AtomicReference<>();
    private final AtomicReference<PrintWriter> controlWriter = new AtomicReference<>();
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    // Threading
    private final ExecutorService executorService;
    private final Object connectionLock = new Object();

    // Response patterns
    private static final Pattern CIRCUIT_INFO_PATTERN = Pattern.compile(
            "(\\d+)\\s+(\\w+)\\s+([^\\s]+)\\s+BUILD_FLAGS=([^\\s]+)\\s+PURPOSE=([^\\s]+).*");

    /**
     * Creates a new TorController with default settings.
     *
     * @param controlPort The Tor control port
     */
    public TorController(int controlPort) {
        this("127.0.0.1", controlPort, null, 10000, null);
    }

    /**
     * Creates a new TorController that reads the control authentication
     * cookie from the given tor data directory first.
     *
     * @param controlPort The Tor control port
     * @param dataDirectory the tor DataDirectory the daemon was launched with
     */
    public TorController(int controlPort, Path dataDirectory) {
        this("127.0.0.1", controlPort, null, 10000, dataDirectory);
    }

    /**
     * Creates a new TorController with custom settings.
     *
     * @param controlHost         The control interface host
     * @param controlPort         The control interface port
     * @param controlPassword     Optional control password (null for cookie auth)
     * @param connectionTimeoutMs Connection timeout in milliseconds
     */
    public TorController(String controlHost, int controlPort, String controlPassword, int connectionTimeoutMs) {
        this(controlHost, controlPort, controlPassword, connectionTimeoutMs, null);
    }

    /**
     * Creates a new TorController with custom settings and an explicit tor
     * data directory to read the control authentication cookie from.
     *
     * @param controlHost         The control interface host
     * @param controlPort         The control interface port
     * @param controlPassword     Optional control password (null for cookie auth)
     * @param connectionTimeoutMs Connection timeout in milliseconds
     * @param dataDirectory       tor DataDirectory holding control_auth_cookie
     */
    public TorController(String controlHost, int controlPort, String controlPassword, int connectionTimeoutMs,
            Path dataDirectory) {
        this.controlHost = controlHost;
        this.controlPort = controlPort;
        this.controlPassword = controlPassword;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.cookieDirectory = dataDirectory;
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "TorController-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Connects to the Tor control interface.
     *
     * @return CompletableFuture that completes when connected
     */
    public CompletableFuture<Boolean> connect() {
        if (isShuttingDown.get()) {
            return CompletableFuture.completedFuture(false);
        }

        if (isConnected.get()) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            synchronized (connectionLock) {
                if (isConnected.get()) {
                    return true;
                }

                try {
                    LOGGER.info("Connecting to Tor control interface at " + controlHost + ":" + controlPort);

                    // Create socket connection
                    Socket socket = new Socket();
                    socket.connect(new java.net.InetSocketAddress(controlHost, controlPort), connectionTimeoutMs);
                    socket.setSoTimeout(30000); // 30 second read timeout

                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);

                    // Authenticate
                    if (!authenticate(reader, writer)) {
                        LOGGER.severe("Failed to authenticate with Tor control interface");
                        socket.close();
                        return false;
                    }

                    // Store connections
                    controlSocket.set(socket);
                    controlReader.set(reader);
                    controlWriter.set(writer);
                    isConnected.set(true);

                    LOGGER.info("Successfully connected to Tor control interface");
                    return true;

                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Failed to connect to Tor control interface", e);
                    return false;
                }
            }
        }, executorService);
    }

    /**
     * Disconnects from the Tor control interface.
     */
    public void disconnect() {
        synchronized (connectionLock) {
            if (!isConnected.get()) {
                return;
            }

            LOGGER.info("Disconnecting from Tor control interface");

            try {
                PrintWriter writer = controlWriter.getAndSet(null);
                if (writer != null) {
                    writer.println("QUIT");
                    writer.close();
                }

                BufferedReader reader = controlReader.getAndSet(null);
                if (reader != null) {
                    reader.close();
                }

                Socket socket = controlSocket.getAndSet(null);
                if (socket != null) {
                    socket.close();
                }

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during disconnect", e);
            } finally {
                isConnected.set(false);
            }
        }
    }

    /**
     * Changes the Tor IP by requesting a new circuit.
     *
     * @return CompletableFuture that completes when IP change is requested
     */
    public CompletableFuture<Boolean> changeIp() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!ensureConnected()) {
                    return false;
                }

                LOGGER.info("Requesting new Tor IP address...");

                // Send NEWNYM signal to get a new circuit
                String response = sendCommand("SIGNAL NEWNYM");

                if (response != null && response.startsWith("250")) {
                    LOGGER.info("New IP requested successfully");
                    // No fixed sleep: the 250 OK already acknowledges the
                    // NEWNYM signal, and circuit building is asynchronous —
                    // a caller that needs the new IP polls for it
                    return true;
                } else {
                    LOGGER.warning("Failed to request new IP: " + response);
                    return false;
                }

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error changing IP", e);
                return false;
            }
        }, executorService);
    }

    /**
     * Gets information about active circuits.
     *
     * @return CompletableFuture with list of circuit information
     */
    public CompletableFuture<List<CircuitInfo>> getCircuitInfo() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!ensureConnected()) {
                    return Collections.emptyList();
                }

                String response = sendCommand("GETINFO circuit-status");
                if (response == null || !response.startsWith("250")) {
                    LOGGER.warning("Failed to get circuit info: " + response);
                    return Collections.emptyList();
                }

                return parseCircuitInfo(response);

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error getting circuit info", e);
                return Collections.emptyList();
            }
        }, executorService);
    }

    /**
     * Closes a specific circuit by ID.
     *
     * @param circuitId The circuit ID to close
     * @return CompletableFuture that completes when circuit is closed
     */
    public CompletableFuture<Boolean> closeCircuit(String circuitId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!ensureConnected()) {
                    return false;
                }

                LOGGER.info("Closing circuit: " + circuitId);

                String response = sendCommand("CLOSECIRCUIT " + circuitId);

                if (response != null && response.startsWith("250")) {
                    LOGGER.info("Circuit closed successfully: " + circuitId);
                    return true;
                } else {
                    LOGGER.warning("Failed to close circuit " + circuitId + ": " + response);
                    return false;
                }

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error closing circuit", e);
                return false;
            }
        }, executorService);
    }

    /**
     * Gets the current external IP address through Tor.
     *
     * @return CompletableFuture with the current IP address
     */
    public CompletableFuture<String> getCurrentIp() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Use the leak checker to get current IP through Tor
                TorLeakChecker leakChecker = new TorLeakChecker();
                String ip = leakChecker.getExternalIp().get(30, TimeUnit.SECONDS);
                leakChecker.shutdown();
                return ip;

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to get current IP", e);
                return null;
            }
        }, executorService);
    }

    /**
     * Sets a configuration option in Tor.
     *
     * @param option The configuration option name
     * @param value  The configuration value
     * @return CompletableFuture that completes when configuration is set
     */
    public CompletableFuture<Boolean> setConfiguration(String option, String value) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!ensureConnected()) {
                    return false;
                }

                LOGGER.info("Setting configuration: " + option + " = " + value);

                // Quoted string per the control-port spec: values with
                // spaces, backslashes, or quotes must be escaped, otherwise
                // the command is truncated at the first space (or exploited)
                String command = String.format("SETCONF %s=%s", option, quoteControlString(value));
                String response = sendCommand(command);

                if (response != null && response.startsWith("250")) {
                    LOGGER.info("Configuration set successfully");
                    return true;
                } else {
                    LOGGER.warning("Failed to set configuration: " + response);
                    return false;
                }

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error setting configuration", e);
                return false;
            }
        }, executorService);
    }

    /**
     * Gets a configuration option from Tor.
     *
     * @param option The configuration option name
     * @return CompletableFuture with the configuration value
     */
    public CompletableFuture<String> getConfiguration(String option) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!ensureConnected()) {
                    return null;
                }

                String response = sendCommand("GETCONF " + option);

                if (response != null && response.startsWith("250")) {
                    // Parse response: "250-option=value"
                    String[] lines = response.split("\n");
                    for (String line : lines) {
                        if (line.contains("=")) {
                            return line.substring(line.indexOf("=") + 1).trim();
                        }
                    }
                }

                return null;

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error getting configuration", e);
                return null;
            }
        }, executorService);
    }

    /**
     * Checks if the controller is connected to Tor.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return isConnected.get() && controlSocket.get() != null && !controlSocket.get().isClosed();
    }

    /**
     * Performs an automatic IP change when connection is slow or lost.
     *
     * @param minSpeedKbps Minimum acceptable speed in KB/s
     * @return CompletableFuture that completes when IP change is done
     */
    public CompletableFuture<Boolean> autoChangeIpOnSlowConnection(int minSpeedKbps) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("Checking connection speed and changing IP if necessary...");

                // Simple speed test - try to download a small file and measure time
                long startTime = System.currentTimeMillis();

                TorLeakChecker leakChecker = new TorLeakChecker();
                String ip = leakChecker.getExternalIp().get(10, TimeUnit.SECONDS);
                leakChecker.shutdown();

                long duration = System.currentTimeMillis() - startTime;

                // If the request took too long, change IP
                if (duration > 10000 || ip == null) { // More than 10 seconds
                    LOGGER.info("Connection is slow or failed, changing IP...");
                    return changeIp().get(30, TimeUnit.SECONDS);
                }

                LOGGER.fine("Connection speed is acceptable");
                return true;

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during auto IP change", e);
                // If we can't test, try changing IP anyway
                try {
                    return changeIp().get(30, TimeUnit.SECONDS);
                } catch (Exception e2) {
                    return false;
                }
            }
        }, executorService);
    }

    /**
     * Shuts down the controller and releases resources.
     */
    public void shutdown() {
        if (isShuttingDown.getAndSet(true)) {
            return;
        }

        LOGGER.info("Shutting down Tor controller");

        disconnect();

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Private helper methods
    private boolean authenticate(BufferedReader reader, PrintWriter writer) throws IOException {
        if (controlPassword != null && !controlPassword.isEmpty()) {
            // Password authentication
            writer.println("AUTHENTICATE \"" + controlPassword + "\"");
            return readResponse(reader).startsWith("250");
        } else {
            // Try cookie authentication first
            String cookieAuth = tryReadCookieAuth();
            if (cookieAuth != null) {
                writer.println("AUTHENTICATE " + cookieAuth);
                String response = readResponse(reader);
                if (response.startsWith("250")) {
                    return true;
                }
            }

            // Try simple authentication
            writer.println("AUTHENTICATE");
            String response = readResponse(reader);
            if (response.startsWith("250")) {
                return true;
            }

            // Try with empty password
            writer.println("AUTHENTICATE \"\"");
            return readResponse(reader).startsWith("250");
        }
    }

    private String tryReadCookieAuth() {
        // tor writes <DataDirectory>/control_auth_cookie when
        // CookieAuthentication is enabled; the controller's own data
        // directory is authoritative, legacy locations are fallbacks
        java.util.List<Path> cookiePaths = new ArrayList<>(3);
        if (cookieDirectory != null) {
            cookiePaths.add(cookieDirectory.resolve("control_auth_cookie"));
        }
        cookiePaths.add(Paths.get(System.getProperty("user.home"), ".tor", "control_auth_cookie"));
        cookiePaths.add(Paths.get("/var/lib/tor", "control_auth_cookie"));

        for (Path path : cookiePaths) {
            if (Files.exists(path)) {
                try {
                    byte[] cookieData = Files.readAllBytes(path);
                    String hexCookie = bytesToHex(cookieData);
                    LOGGER.info("Using cookie authentication from: " + path);
                    return hexCookie;
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to read cookie file: " + path, e);
                }
            }
        }

        return null;
    }

    /**
     * Escapes and quotes a value for the Tor control port (quoted-string
     * per control-spec: backslash and double-quote are escaped, the result
     * is wrapped in double quotes).
     */
    private static String quoteControlString(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return '"' + escaped + '"';
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }

    private String readResponse(BufferedReader reader) throws IOException {
        StringBuilder response = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            response.append(line).append("\n");

            // Multi-line responses end with a line starting with the same code followed by
            // a space
            if (line.length() >= 4 && line.charAt(3) == ' ') {
                break;
            }
        }

        return response.toString().trim();
    }

    private boolean ensureConnected() {
        if (isConnected.get()) {
            return true;
        }

        try {
            return connect().get(connectionTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to ensure connection", e);
            return false;
        }
    }

    private String sendCommand(String command) {
        synchronized (connectionLock) {
            try {
                PrintWriter writer = controlWriter.get();
                BufferedReader reader = controlReader.get();

                if (writer == null || reader == null) {
                    LOGGER.warning("No active control connection");
                    return null;
                }

                LOGGER.fine("Sending command: " + command);
                writer.println(command);

                String response = readResponse(reader);
                LOGGER.fine("Received response: " + response);

                return response;

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error sending command: " + command, e);
                // Connection might be broken, mark as disconnected
                isConnected.set(false);
                return null;
            }
        }
    }

    private List<CircuitInfo> parseCircuitInfo(String response) {
        List<CircuitInfo> circuits = new ArrayList<>();

        String[] lines = response.split("\n");
        for (String line : lines) {
            if (line.startsWith("250-") || line.startsWith("250+")) {
                String circuitLine = line.substring(4);
                Matcher matcher = CIRCUIT_INFO_PATTERN.matcher(circuitLine);

                if (matcher.matches()) {
                    String id = matcher.group(1);
                    String status = matcher.group(2);
                    String path = matcher.group(3);
                    String buildFlags = matcher.group(4);
                    String purpose = matcher.group(5);

                    circuits.add(new CircuitInfo(id, status, path, buildFlags, purpose));
                }
            }
        }

        return circuits;
    }

    /**
     * Information about a Tor circuit.
     */
    public static class CircuitInfo {

        public final String id;
        public final String status;
        public final String path;
        public final String buildFlags;
        public final String purpose;

        public CircuitInfo(String id, String status, String path, String buildFlags, String purpose) {
            this.id = id;
            this.status = status;
            this.path = path;
            this.buildFlags = buildFlags;
            this.purpose = purpose;
        }

        @Override
        public String toString() {
            return String.format("Circuit{id='%s', status='%s', path='%s', purpose='%s'}",
                    id, status, path, purpose);
        }
    }
}
