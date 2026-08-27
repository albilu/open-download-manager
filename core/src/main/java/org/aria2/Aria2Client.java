package org.aria2;

import org.manager.tools.ToolPaths;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.manager.ApplicationContext;
import org.manager.tools.ToolManagerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Java wrapper for aria2c CLI and JSON-RPC, supporting download, pause, resume,
 * remove, and status.
 */
public class Aria2Client {

    private static final Logger LOGGER = Logger.getLogger(Aria2Client.class.getName());

    private final String aria2cPath;
    private volatile String rpcUrl;
    private final String rpcToken;
    /**
     * Secret actually sent with RPC payloads. Equals {@link #rpcToken} when
     * one was configured; for a self-launched daemon a random secret is
     * generated so the RPC endpoint is never unauthenticated.
     */
    private volatile String rpcSecret;
    /** Whether RPC payloads carry {@link #rpcSecret} as the aria2 token. */
    private volatile boolean sendToken;
    private Process aria2Process;
    /**
     * Ownership state of the daemon this client talks to: not started by us,
     * started and authenticated by us, or an external daemon adopted after
     * an authenticated probe with the user-configured secret.
     */
    public enum DaemonOwnership {
        STOPPED, ODM_STARTED, EXTERNAL_AUTHENTICATED
    }

    private volatile DaemonOwnership daemonOwnership = DaemonOwnership.STOPPED;
    private String httpProxy;
    private String configFile;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        // Configure ObjectMapper
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    /**
     * Whether RPC payloads use the WebSocket transport. Volatile: written
     * by {@link #stopAria2c()} to guarantee no reconnect-resurrect while a
     * daemon is being stopped; the restart path lacks the permanent
     * shutdown latch, so visibility of that write must not be assumed.
     */
    private volatile boolean useWebSocket = false;
    private WebSocketClient wsClient;
    private final ConcurrentHashMap<Integer, CompletableFuture<String>> wsResponses = new ConcurrentHashMap<>();
    private final AtomicInteger wsRequestId = new AtomicInteger(1);
    private final List<Aria2NotificationListener> listeners = new CopyOnWriteArrayList<>();
    private List<String> lastExtraArgs;
    private static final int WS_CONNECTION_TIMEOUT = 10000; // 10 seconds timeout
    private static final int WS_RECONNECT_ATTEMPTS = 3;
    /** HTTP RPC connect timeout; a wedged daemon must not pin callers indefinitely. */
    private static final int HTTP_CONNECT_TIMEOUT_MS = 5000;
    /** HTTP RPC read timeout; matches the WebSocket RPC future timeout scale. */
    private static final int HTTP_READ_TIMEOUT_MS = 30000;
    private ScheduledExecutorService wsHealthCheckExecutor;
    private boolean isReconnecting = false;
    private volatile boolean isShuttingDown = false;

    /**
     * Creates a new Aria2Client with the default aria2c path from
     * ToolManagerFactory.
     */
    public Aria2Client() {
        this(ToolPaths.aria2c());
    }


    public Aria2Client(String aria2cPath) {
        this(aria2cPath, "http://localhost:6800/jsonrpc", null);
    }

    public Aria2Client(String aria2cPath, String rpcUrl, String rpcToken) {
        this.aria2cPath = aria2cPath;
        this.rpcUrl = rpcUrl;
        this.rpcToken = rpcToken;
        this.rpcSecret = rpcToken;
        this.sendToken = rpcToken != null;
    }

    /**
     * Add a notification listener
     */
    public void addNotificationListener(Aria2NotificationListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a notification listener
     */
    public void removeNotificationListener(Aria2NotificationListener listener) {
        listeners.remove(listener);
    }

    /**
     * Add a URI via JSON-RPC (returns GID).
     */
    public String addUriRpc(String url) throws IOException, Aria2RpcException {
        return call("aria2.addUri", String.class, (Object) new String[] { url });
    }

    /**
     * Pause a download by GID.
     */
    public String pause(String gid) throws IOException, Aria2RpcException {
        return call("aria2.pause", String.class, gid);
    }

    /**
     * Resume a download by GID.
     */
    public String unpause(String gid) throws IOException, Aria2RpcException {
        return call("aria2.unpause", String.class, gid);
    }

    /**
     * Remove a download by GID.
     */
    public String remove(String gid) throws IOException, Aria2RpcException {
        return call("aria2.remove", String.class, gid);
    }

    /**
     * Query status of a download by GID.
     */
    public String tellStatus(String gid) throws IOException, Aria2RpcException {
        return tellStatus(gid, null);
    }

    /**
     * Query status of a download by GID with optional keys filter.
     *
     * @param gid  The GID of the download
     * @param keys Array of keys to retrieve. If null or empty, all keys are
     *             returned. Common keys: "status", "completedLength",
     *             "totalLength",
     *             "downloadSpeed", "connections", "numSeeders", "seeder",
     *             "pieceLength",
     *             "numPieces"
     * @return JSON string containing the requested status information
     * @throws IOException       if an I/O error occurs
     * @throws Aria2RpcException if an RPC error occurs
     */
    public String tellStatus(String gid, String[] keys) throws IOException, Aria2RpcException {
        Object result = (keys == null || keys.length == 0)
                ? call("aria2.tellStatus", Object.class, gid)
                : call("aria2.tellStatus", Object.class, gid, keys);
        return OBJECT_MAPPER.writeValueAsString(result);
    }

    /**
     * Helper to build JSON-RPC payloads.
     */
    String buildPayload(String method, Object... params) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jsonrpc", "2.0");
        map.put("id", 1);
        map.put("method", method);
        if (params != null && params.length > 0) {
            Object[] p = params;
            if (sendToken && rpcSecret != null) {
                Object[] withToken = new Object[params.length + 1];
                withToken[0] = "token:" + rpcSecret;
                System.arraycopy(params, 0, withToken, 1, params.length);
                p = withToken;
            }
            map.put("params", p);
        } else if (sendToken && rpcSecret != null) {
            map.put("params", new Object[] { "token:" + rpcSecret });
        }
        return OBJECT_MAPPER.writeValueAsString(map);
    }

    /**
     * Parse a JSON-RPC response from aria2.
     */
    private <T> Aria2RpcResponse<T> parseRpcResponse(String json, Class<T> resultType) throws IOException {
        return OBJECT_MAPPER.readValue(json,
                OBJECT_MAPPER.getTypeFactory().constructParametricType(Aria2RpcResponse.class, resultType));
    }

    /**
     * Parse a JSON-RPC response from aria2 with TypeReference.
     */
    private <T> Aria2RpcResponse<T> parseRpcResponse(String json, TypeReference<T> typeRef) throws IOException {
        JavaType type = OBJECT_MAPPER.getTypeFactory().constructType(typeRef);
        return OBJECT_MAPPER.readValue(json,
                OBJECT_MAPPER.getTypeFactory().constructParametricType(Aria2RpcResponse.class, type));
    }

    /**
     * Send a JSON-RPC request to aria2 RPC server and parse the response.
     */
    private <T> Aria2RpcResponse<T> sendRpcHttp(String payload, Class<T> resultType)
            throws IOException, Aria2RpcException {
        URL url = new URL(rpcUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes());
        }
        StringBuilder response = new StringBuilder();
        InputStream inputStream = null;
        try {
            // Try to get normal response stream
            inputStream = conn.getInputStream();
        } catch (IOException e) {
            // If HTTP error response, get error stream instead
            inputStream = conn.getErrorStream();
            if (inputStream == null) {
                throw e; // Re-throw if no error stream available
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        Aria2RpcResponse<T> rpcResponse = parseRpcResponse(response.toString(), resultType);
        if (rpcResponse.error != null) {
            throw new Aria2RpcException(rpcResponse.error.code, rpcResponse.error.message);
        }
        return rpcResponse;
    }

    /**
     * Start aria2c with --enable-rpc if not already running.
     *
     * <p>Ownership rules: an endpoint already occupied by a daemon is
     * adopted only when the user explicitly configured an RPC secret
     * before this call and an authenticated probe with that secret
     * succeeds — the client then records
     * {@link DaemonOwnership#EXTERNAL_AUTHENTICATED} and never shuts that
     * daemon down. Any other occupied endpoint (no configured secret, or
     * credentials the daemon rejects) fails startup with an actionable
     * exception: no download data is ever sent to a daemon ODM cannot
     * authenticate. A free endpoint is self-launched; ownership becomes
     * {@link DaemonOwnership#ODM_STARTED} only after an authenticated RPC
     * probe against the child succeeds.
     *
     * @param extraArgs Additional arguments for aria2c (can be null);
     *                  RPC-control arguments are filtered out
     * @return true when a daemon is usable (adopted or self-launched)
     * @throws IOException if the endpoint is occupied without valid
     *                     configured credentials, or aria2c fails to start
     */
    public boolean startAria2cWithRpc(List<String> extraArgs) throws IOException {
        if (isShuttingDown) {
            return false; // Don't start aria2 during shutdown
        }

        if (aria2Process != null /* && aria2Process.isAlive() */) {
            // Check if aria2 is actually running via RPC
            try {
                getVersion();
                return true; // Already running and responsive
            } catch (Exception e) {
                // Process exists but not responsive, clean it up
                aria2Process = null;
                daemonOwnership = DaemonOwnership.STOPPED;
                return false;
            }
        }

        // An external daemon may already own the RPC port (e.g. the user
        // runs their own aria2). This probe happens BEFORE any child secret
        // is generated. Adoption requires an explicitly configured secret
        // AND a successful authenticated probe; anything else occupying the
        // endpoint is a hard failure — ODM must not send download data to
        // an unauthenticated or foreign daemon, nor shut it down later.
        boolean occupied = false;
        boolean authenticated = false;
        try {
            getVersion();
            occupied = true;
            authenticated = true;
        } catch (Aria2RpcException e) {
            // The endpoint answered with an RPC-level rejection (e.g.
            // Unauthorized): something owns it
            occupied = true;
        } catch (IOException e) {
            // Connection refused / nothing listening: the endpoint is free
        }

        if (occupied) {
            if (rpcToken != null && authenticated) {
                daemonOwnership = DaemonOwnership.EXTERNAL_AUTHENTICATED;
                LOGGER.info("Adopting authenticated external aria2 RPC daemon at " + rpcUrl);
                return true;
            }
            // Refusing the endpoint: the probe may have opened a WebSocket
            // (and its health check) to the daemon — close it so nothing
            // of ours stays attached to a daemon we reject
            closeWebSocketSocket("refusing aria2 endpoint without valid credentials");
            if (authenticated) {
                // rpcToken == null yet our own generated secret was
                // accepted: this is an ODM child that survived a failed
                // stop, not a credentials problem
                throw new IOException(
                        "The aria2 RPC endpoint " + rpcUrl + " is still held by a daemon "
                                + "previously started by ODM (it accepted ODM's generated secret). "
                                + "Stop the stale daemon or free the port before starting again. "
                                + "No download data has been sent to it.");
            }
            throw new IOException(
                    "The aria2 RPC endpoint " + rpcUrl + " is already occupied by a daemon "
                            + "ODM cannot authenticate. Configure a matching RPC secret "
                            + "(aria2.rpcSecret) to adopt it, or free the port so ODM can "
                            + "start its own daemon. No download data has been sent to it.");
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(buildRpcLaunchCommand(extraArgs));
            pb.redirectErrorStream(true);
            aria2Process = pb.start();

            // Wait for aria2c to become responsive (max 10 seconds). The
            // readiness probe is an authenticated getVersion: ODM_STARTED
            // is only recorded once the child accepts our credentials.
            if (waitForAria2State(true, 10000, 200)) {
                daemonOwnership = DaemonOwnership.ODM_STARTED;
                return true; // Success
            }

            // If we get here, startup failed within timeout
            if (aria2Process != null) {
                aria2Process.destroy();
                aria2Process = null;
            }
            return false;
        } catch (IOException e) {
            // If we fail to start aria2c, clean up
            if (aria2Process != null) {
                aria2Process.destroy();
                aria2Process = null;
            }
            return false;
        }
    }

    /**
     * RPC controls owned by ODM. Generic extra arguments are filtered
     * against these so nothing can override the daemon's RPC binding,
     * port, secret, or enable-RPC state.
     */
    private static final List<String> RESERVED_RPC_FLAGS = List.of(
            "--rpc-secret",
            "--rpc-listen-port",
            "--rpc-listen-all",
            "--rpc-listen",
            "--enable-rpc",
            "--disable-rpc");

    /**
     * Builds the command that launches the self-managed aria2 RPC daemon.
     * The daemon binds localhost only and requires an RPC secret: a random
     * one is generated when the caller did not configure a token. The
     * listen port is derived from this client's own RPC endpoint, and
     * reserved RPC-control flags are stripped from {@code extraArgs} so
     * ODM's values stay authoritative. Exposed package-private for the
     * security contract tests.
     *
     * @param extraArgs additional aria2c arguments (can be null)
     * @return the full aria2c command line
     */
    List<String> buildRpcLaunchCommand(List<String> extraArgs) {
        if (rpcToken == null && rpcSecret == null) {
            rpcSecret = generateRpcSecret();
            sendToken = true;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(aria2cPath);
        cmd.add("--enable-rpc");
        // Never expose the RPC endpoint beyond localhost: any LAN process
        // could otherwise add/remove downloads and read filesystem paths.
        cmd.add("--rpc-listen-all=false");
        cmd.add("--daemon=true");
        if (sendToken && rpcSecret != null) {
            cmd.add("--rpc-secret=" + rpcSecret);
        }
        // ODM selects the port: the self-launched daemon must listen where
        // this client's own RPC endpoint points
        int listenPort = rpcUrlListenPort();
        if (listenPort > 0 && listenPort != 6800) {
            cmd.add("--rpc-listen-port=" + listenPort);
        }
        if (httpProxy != null && !httpProxy.isEmpty()) {
            cmd.add("--all-proxy=" + httpProxy);
        }
        if (configFile != null && !configFile.isEmpty()) {
            cmd.add("--conf-path=" + configFile);
        }
        List<String> filteredArgs = filterReservedRpcArgs(extraArgs);
        cmd.addAll(filteredArgs);
        this.lastExtraArgs = new ArrayList<>(filteredArgs); // Store for restart
        return cmd;
    }

    /** The listen port of this client's own RPC endpoint; -1 when unset. */
    private int rpcUrlListenPort() {
        try {
            return URI.create(rpcUrl).getPort();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Reserved flags that consume a separate value token in the
     * two-argument form. The other reserved flags are boolean-style:
     * consuming their following token would swallow the NEXT flag and leak
     * that flag's real value as a stray positional.
     */
    private static final List<String> RESERVED_RPC_VALUE_FLAGS = List.of(
            "--rpc-secret",
            "--rpc-listen-port");

    /**
     * Drops reserved RPC-control arguments (both {@code --flag=value} and
     * two-argument {@code --flag value} forms) so generic arguments can
     * never override the RPC binding, port, secret, or enable-RPC state
     * ODM owns.
     */
    private static List<String> filterReservedRpcArgs(List<String> extraArgs) {
        if (extraArgs == null || extraArgs.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> filtered = new ArrayList<>(extraArgs.size());
        for (int i = 0; i < extraArgs.size(); i++) {
            String arg = extraArgs.get(i);
            String flagName = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
            if (RESERVED_RPC_FLAGS.contains(flagName)) {
                LOGGER.warning("Filtered reserved aria2 RPC argument \"" + arg
                        + "\": RPC binding, port, secret, and enable-RPC are ODM-owned");
                // Only value-taking flags consume the next token
                // unconditionally; a boolean-style reserved flag must never
                // swallow a following FLAG (that would leak the next
                // flag's real value as a stray positional), but a plain
                // value token (e.g. "false") is still consumed with it
                boolean valueTaking = RESERVED_RPC_VALUE_FLAGS.contains(flagName);
                if (!arg.contains("=") && i + 1 < extraArgs.size()
                        && (valueTaking || !extraArgs.get(i + 1).startsWith("--"))) {
                    i++; // also drop the value of the two-argument form
                }
                continue;
            }
            filtered.add(arg);
        }
        return filtered;
    }

    /** Generates a random RPC secret for a self-launched daemon. */
    private static String generateRpcSecret() {
        SecureRandom random = new SecureRandom();
        return Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
    }

    /**
     * Stop the aria2c lifecycle attachment according to daemon ownership:
     *
     * <ul>
     * <li>{@link DaemonOwnership#ODM_STARTED}: send the authenticated
     * shutdown RPC (with a forceShutdown escalation) and reap the child
     * process.</li>
     * <li>{@link DaemonOwnership#EXTERNAL_AUTHENTICATED}: close ODM's own
     * transports only. An aria2.shutdown RPC is never sent to a daemon ODM
     * did not start.</li>
     * </ul>
     */
    public boolean stopAria2c() {
        if (daemonOwnership == DaemonOwnership.EXTERNAL_AUTHENTICATED) {
            // Not our daemon: close ODM's own transports and detach. The
            // external daemon keeps running for whoever started it.
            closeWebSocketSocket("ODM detached from external aria2 daemon");
            daemonOwnership = DaemonOwnership.STOPPED;
            return true;
        }

        if (aria2Process != null /* && aria2Process.isAlive() as its starts as dameon, its exit immediately */) {
            // An open WebSocket auto-reconnects (and restarts the daemon!)
            // when the daemon closes the connection on shutdown. Disable
            // the transport and close the socket BEFORE shutting down, or
            // the reconnect path resurrects the daemon we are stopping.
            useWebSocket = false;
            closeWebSocketSocket("stopping ODM-owned daemon");
            try {
                // Try to shutdown gracefully first
                shutdown();

                // Wait for graceful shutdown (max 10 seconds)
                if (!waitForAria2State(false, 10000, 200)) {
                    // Still running, try force shutdown
                    try {
                        forceShutdown();
                        // Wait for force shutdown (max 10 seconds)
                        waitForAria2State(false, 10000, 200);
                    } catch (Exception e) {
                        // Ignore force shutdown errors
                    }
                }

            } catch (Exception e) {
                // If graceful shutdown fails, force destroy
                if (aria2Process != null) {
                    aria2Process.destroy();
                }
            }
            aria2Process = null;
            daemonOwnership = DaemonOwnership.STOPPED;
        }

        // Verify it's actually stopped. This probe cannot distinguish a
        // foreign daemon on the endpoint from our own; no current caller
        // double-stops an adopted daemon (ownership resets to STOPPED
        // above), so a false return here means an ODM child survived.
        try {
            getVersion();
            return false; // Still running
        } catch (Exception e) {
            return true; // Successfully stopped
        }
    }

    /**
     * Restarts the aria2c process with the same configuration as before. If
     * WebSocket was connected, it will be reconnected after restart.
     *
     * @return true if restart was successful, false otherwise
     * @throws IOException if there's an error starting the new process
     */
    public boolean restartAria2c() throws IOException {
        if (isShuttingDown) {
            return false; // Don't restart aria2 during shutdown
        }

        boolean wasUsingWebSocket = useWebSocket && wsClient != null;

        // Save session before stopping if possible
        try {
            saveSession();
        } catch (Exception e) {
            // Ignore if we can't save the session
        }

        // Stop the current aria2c process
        boolean stopSuccess = stopAria2c();
        if (!stopSuccess) {
            return false;
        }

        // Wait for process to fully terminate (max 10 seconds)
        waitForAria2State(false, 10000, 200);

        // Start a new aria2c process with the same arguments
        boolean startSuccess = startAria2cWithRpc(lastExtraArgs);
        if (!startSuccess) {
            return false;
        }

        // If we were using WebSocket before, reconnect
        if (wasUsingWebSocket) {
            try {
                disconnectWebSocket();
                connectWebSocket();
            } catch (Exception e) {
                return false;
            }
        }

        return true;
    }

    /**
     * Set HTTP proxy for aria2c (e.g., http://host:port or
     * http://user:pass@host:port)
     */
    public void setHttpProxy(String proxy) {
        this.httpProxy = proxy;
    }

    /**
     * Start aria2c with RPC enabled using default arguments.
     *
     * @return true if aria2c started successfully, false otherwise
     * @throws IOException if aria2c fails to start
     */
    public boolean startAria2cWithRpc() throws IOException {
        return startAria2cWithRpc(null);
    }

    /**
     * Set configuration file for aria2c (e.g., /path/to/aria2.conf).
     */
    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    /**
     * Add a torrent file via JSON-RPC (returns GID list).
     */
    public String addTorrent(byte[] torrent, List<String> uris, String dir, Map<String, Object> options)
            throws IOException, Aria2RpcException {
        String torrentBase64 = java.util.Base64.getEncoder().encodeToString(torrent);
        Map<String, Object> opts = new LinkedHashMap<>();
        if (options != null) {
            opts.putAll(options);
        }
        if (dir != null) {
            opts.put("dir", dir);
        }
        List<Object> params = new ArrayList<>();
        params.add(torrentBase64);
        params.add(uris != null ? uris : new ArrayList<>());
        params.add(opts);
        return call("aria2.addTorrent", String.class, params.toArray());
    }

    /**
     * Add a Metalink file via JSON-RPC. aria2 returns a list of GIDs (one per
     * file in the metalink); this method returns the first GID, which tracks
     * the primary download of typical single-file metalinks.
     */
    public String addMetalink(byte[] metalink, Map<String, Object> options) throws IOException, Aria2RpcException {
        String metalinkBase64 = java.util.Base64.getEncoder().encodeToString(metalink);
        List<Object> params = new ArrayList<>();
        params.add(metalinkBase64);
        params.add(options != null ? options : new LinkedHashMap<>());
        List<?> gids;
        gids = call("aria2.addMetalink", List.class, params.toArray());
        if (gids == null || gids.isEmpty()) {
            throw new IOException("aria2.addMetalink returned no GIDs");
        }
        return gids.get(0).toString();
    }

    /**
     * Force remove a download by GID.
     */
    public String forceRemove(String gid) throws IOException, Aria2RpcException {
        return call("aria2.forceRemove", String.class, gid);
    }

    /**
     * Pause all downloads.
     */
    public String pauseAll() throws IOException, Aria2RpcException {
        return call("aria2.pauseAll", String.class);
    }

    /**
     * Force pause a download by GID.
     */
    public String forcePause(String gid) throws IOException, Aria2RpcException {
        return call("aria2.forcePause", String.class, gid);
    }

    /**
     * Force pause all downloads.
     */
    public String forcePauseAll() throws IOException, Aria2RpcException {
        return call("aria2.forcePauseAll", String.class);
    }

    /**
     * Unpause all downloads.
     */
    public String unpauseAll() throws IOException, Aria2RpcException {
        return call("aria2.unpauseAll", String.class);
    }

    /**
     * Change position of a download in the queue.
     */
    public String changePosition(String gid, int pos, String how) throws IOException, Aria2RpcException {
        return call("aria2.changePosition", String.class, gid, pos, how);
    }

    /**
     * Change options for a specific download by GID.
     */
    public String changeOption(String gid, Map<String, Object> options) throws IOException, Aria2RpcException {
        return call("aria2.changeOption", String.class, gid, options);
    }

    /**
     * Change global options for aria2.
     */
    public String changeGlobalOption(Map<String, Object> options) throws IOException, Aria2RpcException {
        return call("aria2.changeGlobalOption", String.class, options);
    }

    /**
     * Shutdown aria2c daemon (raw RPC). Callers must only aim this at a
     * daemon ODM started: for an adopted
     * {@link DaemonOwnership#EXTERNAL_AUTHENTICATED} daemon, transports are
     * closed via {@link #stopAria2c()} without ever sending this request.
     */
    public String shutdown() throws IOException, Aria2RpcException {
        return call("aria2.shutdown", String.class);
    }

    /**
     * Force shutdown aria2c daemon.
     */
    public String forceShutdown() throws IOException, Aria2RpcException {
        return call("aria2.forceShutdown", String.class);
    }

    /**
     * Save session to file.
     */
    public String saveSession() throws IOException, Aria2RpcException {
        return call("aria2.saveSession", String.class);
    }

    // Implement aria2.getUris
    public List<String> getUris(String gid) throws IOException, Aria2RpcException {
        return call("aria2.getUris", List.class, gid);
    }

    // Implement aria2.getFiles
    public List<Map<String, Object>> getFiles(String gid) throws IOException, Aria2RpcException {
        return call("aria2.getFiles", new TypeReference<List<Map<String, Object>>>() {{}}, gid);
    }

    // Implement aria2.getPeers
    public List<Map<String, Object>> getPeers(String gid) throws IOException, Aria2RpcException {
        return call("aria2.getPeers", new TypeReference<List<Map<String, Object>>>() {{}}, gid);
    }

    // Implement aria2.getServers
    public List<Map<String, Object>> getServers(String gid) throws IOException, Aria2RpcException {
        return call("aria2.getServers", new TypeReference<List<Map<String, Object>>>() {{}}, gid);
    }

    // Implement aria2.getOption
    public Map<String, Object> getOption(String gid) throws IOException, Aria2RpcException {
        return call("aria2.getOption", new TypeReference<Map<String, Object>>() {{}}, gid);
    }

    // Implement aria2.getGlobalOption
    public Map<String, Object> getGlobalOption() throws IOException, Aria2RpcException {
        return call("aria2.getGlobalOption", new TypeReference<Map<String, Object>>() {{}});
    }

    // Implement aria2.getGlobalStat
    public Map<String, Object> getGlobalStat() throws IOException, Aria2RpcException {
        return call("aria2.getGlobalStat", new TypeReference<Map<String, Object>>() {{}});
    }

    // Implement aria2.purgeDownloadResult
    public String purgeDownloadResult() throws IOException, Aria2RpcException {
        return call("aria2.purgeDownloadResult", String.class);
    }

    // Implement aria2.removeDownloadResult
    public String removeDownloadResult(String gid) throws IOException, Aria2RpcException {
        return call("aria2.removeDownloadResult", String.class, gid);
    }

    // Implement aria2.getVersion
    public Map<String, Object> getVersion() throws IOException, Aria2RpcException {
        return call("aria2.getVersion", new TypeReference<Map<String, Object>>() {{}});
    }

    // Implement aria2.getSessionInfo
    public Map<String, Object> getSessionInfo() throws IOException, Aria2RpcException {
        return call("aria2.getSessionInfo", new TypeReference<Map<String, Object>>() {{}});
    }

    // Implement system.multicall
    public List<List<Object>> systemMulticall(List<Map<String, Object>> calls)
            throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocketRaw("system.multicall",
                        new TypeReference<List<List<Object>>>() {
                        }, calls).result;
            } catch (IOException | Aria2RpcException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildMulticallPayload(calls);
            return sendRpcHttp(payload, new TypeReference<List<List<Object>>>() {
            }).result;
        }
    }

    /**
     * Builds the wire payload for {@code system.multicall}. aria2 expects
     * params[0] to be the calls array — the RPC token belongs INSIDE each
     * inner call's params, never on the outer params.
     *
     * @param calls the inner method calls
     * @return the JSON-RPC payload
     * @throws IOException if serialization fails
     */
    String buildMulticallPayload(List<Map<String, Object>> calls) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jsonrpc", "2.0");
        map.put("id", 1);
        map.put("method", "system.multicall");
        // aria2 expects params[0] to be the ARRAY of calls
        map.put("params", List.of(calls));
        return OBJECT_MAPPER.writeValueAsString(map);
    }

    /**
     * Builds the inner {@code aria2.tellStatus} calls for a batch progress
     * poll, authenticating each call with this client's token as aria2
     * requires.
     *
     * @param gids the download GIDs to poll
     * @param keys the status keys to request
     * @return one call map per gid
     */
    public List<Map<String, Object>> tellStatusMulticallCalls(List<String> gids, String[] keys) {
        List<Map<String, Object>> calls = new ArrayList<>(gids.size());
        for (String gid : gids) {
            List<Object> params = new ArrayList<>(3);
            if (sendToken && rpcSecret != null) {
                params.add("token:" + rpcSecret);
            }
            params.add(gid);
            params.add(keys);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("methodName", "aria2.tellStatus");
            call.put("params", params);
            calls.add(call);
        }
        return calls;
    }

    /**
     * Enable or disable WebSocket transport for JSON-RPC.
     */
    public void setUseWebSocket(boolean useWebSocket) {
        this.useWebSocket = useWebSocket;
    }

    /**
     * Points the client at a different RPC endpoint. The handler uses this
     * when the configured RPC port differs from the aria2 default (6800).
     *
     * @param url the JSON-RPC URL, e.g. {@code http://localhost:6801/jsonrpc}
     */
    public void setRpcUrl(String url) {
        if (url != null && !url.isBlank()) {
            this.rpcUrl = url;
        }
    }

    /**
     * Check if aria2 daemon is running and responsive.
     *
     * @return true if aria2 is running and responsive, false otherwise
     */
    public boolean isAria2Running() {
        try {
            getVersion();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Wait for aria2 to become responsive or stop responding with retry
     * mechanism.
     *
     * @param expectRunning   true to wait for aria2 to become responsive, false
     *                        to wait for it to stop
     * @param maxWaitTimeMs   maximum time to wait in milliseconds
     * @param retryIntervalMs interval between retries in milliseconds
     * @return true if expected state is reached within timeout, false otherwise
     */
    private boolean waitForAria2State(boolean expectRunning, long maxWaitTimeMs, int retryIntervalMs) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < maxWaitTimeMs) {
            try {
                Thread.sleep(retryIntervalMs);
                boolean isRunning = isAria2Running();

                if (isRunning == expectRunning) {
                    return true; // Expected state reached
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return false; // Timeout reached
    }

    /**
     * Connect to aria2 WebSocket JSON-RPC server.
     */
    public void connectWebSocket() throws Exception {
        if (wsClient != null && wsClient.isOpen()) {
            return;
        }

        if (isReconnecting) {
            return;
        }

        wsClient = new WebSocketClient(new URI(rpcUrl.replaceFirst("^http", "ws"))) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                LOGGER.info("WebSocket connection opened");
                startWebSocketHealthCheck();
            }

            @Override
            public void onMessage(String message) {
                try {
                    JsonNode json = OBJECT_MAPPER.readTree(message);
                    if (json.has("id")) {
                        int id = json.get("id").asInt();
                        CompletableFuture<String> future = wsResponses.get(id);
                        if (future != null) {
                            future.complete(message);
                            wsResponses.remove(id);
                        }
                    } else if (json.has("method")) {
                        handleNotification(json);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to process WebSocket message", e);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                LOGGER.info(
                        "WebSocket connection closed: " + reason + " (code: " + code + ", remote: " + remote + ")");
                stopWebSocketHealthCheck();

                // Attempt to reconnect if closed unexpectedly AND not shutting down
                if (remote && useWebSocket && !isReconnecting && !isShuttingDown) {
                    tryReconnect();
                } else if (isShuttingDown) {
                    // During shutdown, don't attempt reconnection
                    useWebSocket = false;
                }
            }

            @Override
            public void onError(Exception ex) {
                LOGGER.log(Level.WARNING, "WebSocket error: " + ex.getMessage(), ex);
            }
        };

        // Set connection timeout
        wsClient.setConnectionLostTimeout(WS_CONNECTION_TIMEOUT / 1000);

        // Connect with timeout
        boolean connected = wsClient.connectBlocking(WS_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);
        if (!connected) {
            throw new IOException("Failed to connect to WebSocket within timeout");
        }
    }

    private void handleNotification(JsonNode json) {
        String method = json.get("method").asText();
        JsonNode paramsNode = json.get("params");

        if (!paramsNode.isArray() || paramsNode.size() == 0) {
            return;
        }

        String gid = paramsNode.get(0).asText();

        switch (method) {
            case "aria2.onDownloadStart" ->
                listeners.forEach(l -> l.onDownloadStart(gid));
            case "aria2.onDownloadPause" ->
                listeners.forEach(l -> l.onDownloadPause(gid));
            case "aria2.onDownloadStop" ->
                listeners.forEach(l -> l.onDownloadStop(gid));
            case "aria2.onDownloadComplete" ->
                listeners.forEach(l -> l.onDownloadComplete(gid));
            case "aria2.onDownloadError" -> {
                if (paramsNode.size() >= 2) {
                    Aria2RpcError error = new Aria2RpcError();
                    error.code = paramsNode.get(1).asInt();
                    listeners.forEach(l -> l.onDownloadError(gid, error));
                }
            }
            case "aria2.onBtDownloadComplete" ->
                listeners.forEach(l -> l.onBtDownloadComplete(gid));
            // case "aria2.onDownloadProgress":
            // if (paramsNode.size() >= 3) {
            // long numFiles = paramsNode.get(1).asLong();
            // Map<String, Object> status = OBJECT_MAPPER.convertValue(
            // paramsNode.get(2),
            // new TypeReference<Map<String, Object>>() {
            // });
            // listeners.forEach(l -> l.onDownloadProgress(gid, numFiles, status));
            // }
            // break;
        }
    }

    /**
     * Disconnect from aria2 WebSocket JSON-RPC server. Final teardown: latches
     * the shutdown state so no later reconnection or daemon start happens.
     */
    public void disconnectWebSocket() {
        isShuttingDown = true;
        useWebSocket = false; // Prevent reconnection attempts
        closeWebSocketSocket("Application shutdown");
    }

    /**
     * Closes the WebSocket socket and fails all pending responses without
     * latching the final shutdown state, so the reconnect path can re-use
     * it safely.
     */
    void closeWebSocketSocket(String reason) {
        stopWebSocketHealthCheck();

        if (wsClient != null) {
            try {
                // Send close frame with normal closure code
                wsClient.close(1000, reason);

                // Wait briefly for graceful close, then force if needed
                wsClient.closeBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                wsClient.close(); // Force close on interruption
            } catch (Exception e) {
                // Log error but continue with cleanup
                LOGGER.severe("Error during WebSocket close: " + e.getMessage());
                wsClient.close(); // Force close on any error
            } finally {
                wsClient = null;
            }
        }

        // Clear any pending responses
        wsResponses.forEach((id, future) -> future.completeExceptionally(
                new IOException("WebSocket disconnected: " + reason)));
        wsResponses.clear();
    }

    /** Whether the permanent shutdown latch is set. Test/inspection accessor. */
    boolean isShutdownLatched() {
        return isShuttingDown;
    }

    /**
     * The RPC secret this client authenticates with (its own generated one
     * for a self-launched daemon, or the configured token). Same-process
     * access only — never logged or exported.
     *
     * @return the RPC secret, or null when unauthenticated
     */
    public String getRpcSecret() {
        return sendToken ? rpcSecret : null;
    }

    /**
     * The ownership state of the daemon this client is attached to.
     *
     * @return STOPPED, ODM_STARTED, or EXTERNAL_AUTHENTICATED
     */
    public DaemonOwnership getDaemonOwnership() {
        return daemonOwnership;
    }

    /** Whether WebSocket use has been permanently disabled. Test accessor. */
    boolean isWebSocketUseDisabled() {
        return !useWebSocket;
    }

    /**
     * Enables the WebSocket transport preference. Test seam: no production
     * caller currently switches the client to WebSocket RPC.
     */
    void enableWebSocketTransport() {
        useWebSocket = true;
    }

    /**
     * Allocates the next WebSocket request id. Shared across arbitrary
     * caller threads.
     */
    int nextWsRequestId() {
        return wsRequestId.incrementAndGet();
    }

    /**
     * Starts a scheduled task to periodically check WebSocket connection health
     * and clear stale connections/responses.
     */
    private void startWebSocketHealthCheck() {
        stopWebSocketHealthCheck(); // Ensure we don't have multiple running

        wsHealthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-health-check");
            t.setDaemon(true);
            return t;
        });

        wsHealthCheckExecutor.scheduleAtFixedRate(() -> {
            try {
                // Keepalive ping: detects a silently-dead connection through
                // the onClose/onError callbacks. (Futures that complete are
                // already removed in onMessage, so no sweeping is needed.)
                if (wsClient != null && wsClient.isOpen()) {
                    wsClient.sendPing();
                }
            } catch (Exception e) {
                LOGGER.fine("WebSocket health check ping failed: " + e.getMessage());
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    /**
     * Stops the WebSocket health check scheduler.
     */
    private void stopWebSocketHealthCheck() {
        if (wsHealthCheckExecutor != null) {
            wsHealthCheckExecutor.shutdownNow();
            wsHealthCheckExecutor = null;
        }
    }

    /**
     * Attempts to reconnect WebSocket with exponential backoff.
     */
    private void tryReconnect() {
        if (isReconnecting || !useWebSocket || isShuttingDown) {
            return;
        }

        isReconnecting = true;
        Thread reconnectThread = new Thread(() -> {
            int attempts = 0;
            while (attempts < WS_RECONNECT_ATTEMPTS && useWebSocket && !isShuttingDown) {
                try {
                    attempts++;
                    long backoffMs = (long) Math.min(1000 * Math.pow(2, attempts), 30000);
                    LOGGER.info(
                            "Attempting to reconnect WebSocket in " + backoffMs + "ms (attempt " + attempts + ")");

                    Thread.sleep(backoffMs);

                    // Check if aria2 is still running, restart if not
                    if (!isAria2Running()) {
                        try {
                            if (isShuttingDown) {
                                LOGGER.info("Skipping aria2 restart - application is shutting down");
                                break;
                            }

                            boolean restartSuccess = restartAria2c();
                            if (!restartSuccess) {
                                LOGGER.severe("Failed to restart aria2 process");
                                continue;
                            }
                        } catch (IOException e) {
                            LOGGER.severe("Failed to restart aria2 process: " + e.getMessage());
                            continue;
                        }
                    }

                    // Try to reconnect
                    closeWebSocketSocket("reconnect");
                    connectWebSocket();

                    // If we get here, connection succeeded
                    LOGGER.info("Successfully reconnected WebSocket");
                    isReconnecting = false;
                    return;
                } catch (Exception e) {
                    LOGGER.severe("Failed to reconnect WebSocket: " + e.getMessage());
                }
            }

            LOGGER.severe("Failed to reconnect WebSocket after " + attempts + " attempts");
            isReconnecting = false;
        }, "ws-reconnect");

        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /**
     * Send a JSON-RPC request to aria2 RPC server and parse the response using
     * TypeReference.
     */
    private <T> Aria2RpcResponse<T> sendRpcHttp(String payload, TypeReference<T> typeRef)
            throws IOException, Aria2RpcException {
        URL url = new URL(rpcUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("Failed to send RPC request: " + e.getMessage(), e);
        }

        StringBuilder responseString = new StringBuilder();
        InputStream inputStream = null;
        try {
            // Try to get normal response stream
            inputStream = conn.getInputStream();
        } catch (IOException e) {
            // If HTTP error response, get error stream instead
            inputStream = conn.getErrorStream();
            if (inputStream == null) {
                throw e; // Re-throw if no error stream available
            }
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                responseString.append(line);
            }
        }

        Aria2RpcResponse<T> response = parseRpcResponse(responseString.toString(), typeRef);
        if (response.error != null) {
            throw new Aria2RpcException(response.error.code, response.error.message);
        }

        return response;
    }

    /**
     * Send a JSON-RPC request over WebSocket and parse the response.
     */
    /**
     * Sends a pre-built JSON-RPC payload over WebSocket. For methods such as
     * system.multicall whose params must not receive the automatic outer
     * token that {@link #buildPayload} injects.
     */
    private <T> Aria2RpcResponse<T> sendRpcWebSocketRaw(String method, TypeReference<T> typeRef,
            List<Map<String, Object>> calls) throws Exception {
        if (isShuttingDown) {
            throw new IOException("Cannot send WebSocket RPC during shutdown");
        }
        if (wsClient == null || !wsClient.isOpen()) {
            connectWebSocket();
        }

        int id = nextWsRequestId();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jsonrpc", "2.0");
        map.put("id", id);
        map.put("method", method);
        // aria2 expects params[0] to be the ARRAY of calls
        map.put("params", List.of(calls));
        String payload = OBJECT_MAPPER.writeValueAsString(map);

        CompletableFuture<String> future = new CompletableFuture<>();
        wsResponses.put(id, future);
        try {
            wsClient.send(payload);
            String response = future.get(30, TimeUnit.SECONDS);
            Aria2RpcResponse<T> rpcResponse = parseRpcResponse(response, typeRef);
            if (rpcResponse.error != null) {
                throw new Aria2RpcException(rpcResponse.error.code, rpcResponse.error.message);
            }
            return rpcResponse;
        } catch (TimeoutException e) {
            wsResponses.remove(id);
            throw new IOException("RPC request timed out", e);
        } catch (InterruptedException e) {
            wsResponses.remove(id);
            Thread.currentThread().interrupt();
            throw new IOException("RPC request interrupted", e);
        } catch (ExecutionException e) {
            wsResponses.remove(id);
            throw new IOException("RPC execution error", e.getCause() != null ? e.getCause() : e);
        }
    }

    /**
     * Dispatches an RPC over the configured transport (WebSocket when
     * enabled, HTTP otherwise) and unwraps the result. Every aria2 method
     * funnels through one of the {@code call} overloads so error semantics
     * are uniform: {@link Aria2RpcException} (with the daemon's error code)
     * propagates as-is, transport failures surface as {@link IOException}.
     */
    private <T> T call(String method, Class<T> resultType, Object... params)
            throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket(method, resultType, params).result;
            } catch (IOException | Aria2RpcException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(method + " failed", e);
            }
        }
        return sendRpcHttp(buildPayload(method, params), resultType).result;
    }

    /** {@code call} for generic result types (maps, lists). */
    @SuppressWarnings("unchecked")
    private <T> T call(String method, TypeReference<T> typeRef, Object... params)
            throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket(method, typeRef, params).result;
            } catch (IOException | Aria2RpcException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(method + " failed", e);
            }
        }
        return sendRpcHttp(buildPayload(method, params), typeRef).result;
    }

    private <T> Aria2RpcResponse<T> sendRpcWebSocket(String method, Class<T> resultType, Object... params)
            throws Exception {
        if (isShuttingDown) {
            throw new IOException("Cannot send WebSocket RPC during shutdown");
        }
        if (wsClient == null || !wsClient.isOpen()) {
            connectWebSocket();
        }

        int id = nextWsRequestId();
        String payload = buildPayloadWithId(method, id, params);
        CompletableFuture<String> future = new CompletableFuture<>();
        wsResponses.put(id, future);

        try {
            wsClient.send(payload);
            // Wait for response with timeout
            String response = future.get(30, TimeUnit.SECONDS);
            Aria2RpcResponse<T> rpcResponse = parseRpcResponse(response, resultType);
            if (rpcResponse.error != null) {
                throw new Aria2RpcException(rpcResponse.error.code, rpcResponse.error.message);
            }
            return rpcResponse;
        } catch (TimeoutException e) {
            wsResponses.remove(id);
            throw new IOException("RPC request timed out", e);
        } catch (InterruptedException e) {
            wsResponses.remove(id);
            Thread.currentThread().interrupt();
            throw new IOException("RPC request interrupted", e);
        } catch (ExecutionException e) {
            wsResponses.remove(id);
            throw new IOException("RPC execution error", e.getCause() != null ? e.getCause() : e);
        }
    }

    /**
     * Send a JSON-RPC request over WebSocket and parse the response using
     * TypeReference.
     */
    private <T> Aria2RpcResponse<T> sendRpcWebSocket(String method, TypeReference<T> typeRef, Object... params)
            throws Exception {
        if (isShuttingDown) {
            throw new IOException("Cannot send WebSocket RPC during shutdown");
        }
        if (wsClient == null || !wsClient.isOpen()) {
            connectWebSocket();
        }

        int id = nextWsRequestId();
        String payload = buildPayloadWithId(method, id, params);
        CompletableFuture<String> future = new CompletableFuture<>();
        wsResponses.put(id, future);

        try {
            wsClient.send(payload);
            // Wait for response with timeout
            String response = future.get(30, TimeUnit.SECONDS);
            Aria2RpcResponse<T> rpcResponse = parseRpcResponse(response, typeRef);
            if (rpcResponse.error != null) {
                throw new Aria2RpcException(rpcResponse.error.code, rpcResponse.error.message);
            }
            return rpcResponse;
        } catch (TimeoutException e) {
            wsResponses.remove(id);
            throw new IOException("RPC request timed out", e);
        } catch (ExecutionException e) {
            wsResponses.remove(id);
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            } else {
                throw new IOException("WebSocket request failed: " + method, e);
            }
        } catch (InterruptedException e) {
            wsResponses.remove(id);
            Thread.currentThread().interrupt();
            throw new IOException("WebSocket request interrupted: " + method, e);
        }
    }

    /**
     * Helper to build JSON-RPC payloads with custom id (for WebSocket).
     */
    private String buildPayloadWithId(String method, int id, Object... params) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jsonrpc", "2.0");
        map.put("id", id);
        map.put("method", method);
        if (params != null && params.length > 0) {
            Object[] p = params;
            if (sendToken && rpcSecret != null) {
                Object[] withToken = new Object[params.length + 1];
                withToken[0] = "token:" + rpcSecret;
                System.arraycopy(params, 0, withToken, 1, params.length);
                p = withToken;
            }
            map.put("params", p);
        } else if (sendToken && rpcSecret != null) {
            map.put("params", new Object[] { "token:" + rpcSecret });
        }
        return OBJECT_MAPPER.writeValueAsString(map);
    }

    public String addUriRpc(String url, Map<String, Object> options) throws IOException, Aria2RpcException {
        return addUriRpc(new String[] { url }, options);
    }

    /**
     * Adds a download with multiple URIs pointing to the same resource. aria2
     * treats all URIs as mirrors of a single download with automatic failover.
     *
     * @param uris    URIs of the resource (first is primary, rest are mirrors)
     * @param options optional aria2 options
     * @return the GID of the download
     */
    public String addUriRpc(String[] uris, Map<String, Object> options) throws IOException, Aria2RpcException {
        return (options != null && !options.isEmpty())
                ? call("aria2.addUri", String.class, uris, options)
                : call("aria2.addUri", String.class, (Object) uris);
    }

    /**
     * Aria2 JSON-RPC response wrapper.
     */
    public static class Aria2RpcResponse<T> {

        public String jsonrpc;
        public int id;
        public T result;
        public Aria2RpcError error;

        public Aria2RpcResponse() {
        }

        public Aria2RpcResponse(String jsonrpc, int id, T result, Aria2RpcError error) {
            this.jsonrpc = jsonrpc;
            this.id = id;
            this.result = result;
            this.error = error;
        }
    }

    public static class Aria2RpcError {

        public int code;
        public String message;

        public Aria2RpcError() {
        }

        public Aria2RpcError(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    public static class Aria2RpcException extends Exception {

        private final int code;
        private final String message;

        public Aria2RpcException(int code, String message) {
            super("Aria2 RPC Error " + code + ": " + message);
            this.code = code;
            this.message = message;
        }

        public int getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }

}
