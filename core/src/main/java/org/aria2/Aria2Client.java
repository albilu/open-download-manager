package org.aria2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
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
    private final String rpcUrl;
    private final String rpcToken;
    private Process aria2Process;
    private String httpProxy;
    private String configFile;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        // Configure ObjectMapper
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    private boolean useWebSocket = false;
    private WebSocketClient wsClient;
    private final ConcurrentHashMap<Integer, CompletableFuture<String>> wsResponses = new ConcurrentHashMap<>();
    private int wsRequestId = 1;
    private final List<Aria2NotificationListener> listeners = new CopyOnWriteArrayList<>();
    private List<String> lastExtraArgs;
    private static final int WS_CONNECTION_TIMEOUT = 10000; // 10 seconds timeout
    private static final int WS_RECONNECT_ATTEMPTS = 3;
    private ScheduledExecutorService wsHealthCheckExecutor;
    private boolean isReconnecting = false;
    private volatile boolean isShuttingDown = false;

    /**
     * Gets the ToolManagerFactory instance using ApplicationContext.
     */
    private static ToolManagerFactory getToolManagerFactory() {
        return ApplicationContext.getToolManagerFactory();
    }

    /**
     * Creates a new Aria2Client with the default aria2c path from
     * ToolManagerFactory.
     */
    public Aria2Client() {
        this(getAria2Path());
    }

    /**
     * Gets the aria2c path using the ToolManagerFactory.
     */
    private static String getAria2Path() {
        try {
            ToolManagerFactory factory = getToolManagerFactory();
            if (factory != null) {
                Aria2ToolManager aria2Manager = factory.getAria2Manager();
                if (aria2Manager != null) {
                    return aria2Manager.getToolPath();
                }
            }

            // Final fallback - try system aria2c
            return "aria2c";
        } catch (Exception e) {
            // Final fallback - try system aria2c
            return "aria2c";
        }
    }

    public Aria2Client(String aria2cPath) {
        this(aria2cPath, "http://localhost:6800/jsonrpc", null);
    }

    public Aria2Client(String aria2cPath, String rpcUrl, String rpcToken) {
        this.aria2cPath = aria2cPath;
        this.rpcUrl = rpcUrl;
        this.rpcToken = rpcToken;
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
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.addUri", String.class, (Object) new String[] { url }).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.addUri", (Object) new String[] { url });
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Pause a download by GID.
     */
    public String pause(String gid) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.pause", String.class, gid).result;
            } catch (Aria2RpcException e) {
                throw e; // Re-throw Aria2RpcException as-is
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.pause", gid);
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Resume a download by GID.
     */
    public String unpause(String gid) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.unpause", String.class, gid).result;
            } catch (Aria2RpcException e) {
                throw e; // Re-throw Aria2RpcException as-is
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.unpause", gid);
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Remove a download by GID.
     */
    public String remove(String gid) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.remove", String.class, gid).result;
            } catch (Aria2RpcException e) {
                throw e; // Re-throw Aria2RpcException as-is
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.remove", gid);
            return sendRpcHttp(payload, String.class).result;
        }
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
        if (useWebSocket) {
            try {
                if (keys == null || keys.length == 0) {
                    Object result = sendRpcWebSocket("aria2.tellStatus", Object.class, gid).result;
                    return OBJECT_MAPPER.writeValueAsString(result);
                } else {
                    Object result = sendRpcWebSocket("aria2.tellStatus", Object.class, gid, keys).result;
                    return OBJECT_MAPPER.writeValueAsString(result);
                }
            } catch (Aria2RpcException e) {
                throw e; // Re-throw Aria2RpcException as-is
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload;
            if (keys == null || keys.length == 0) {
                payload = buildPayload("aria2.tellStatus", gid);
            } else {
                payload = buildPayload("aria2.tellStatus", gid, keys);
            }
            Object result = sendRpcHttp(payload, Object.class).result;
            return OBJECT_MAPPER.writeValueAsString(result);
        }
    }

    /**
     * Helper to build JSON-RPC payloads.
     */
    private String buildPayload(String method, Object... params) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jsonrpc", "2.0");
        map.put("id", 1);
        map.put("method", method);
        if (params != null && params.length > 0) {
            Object[] p = params;
            if (rpcToken != null) {
                Object[] withToken = new Object[params.length + 1];
                withToken[0] = "token:" + rpcToken;
                System.arraycopy(params, 0, withToken, 1, params.length);
                p = withToken;
            }
            map.put("params", p);
        } else if (rpcToken != null) {
            map.put("params", new Object[] { "token:" + rpcToken });
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
     * @param extraArgs Additional arguments for aria2c (can be null)
     * @throws IOException if aria2c fails to start
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
                return false;
            }
        }
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(aria2cPath);
            cmd.add("--enable-rpc");
            cmd.add("--rpc-listen-all=true");
            cmd.add("--daemon=true");
            if (httpProxy != null && !httpProxy.isEmpty()) {
                cmd.add("--all-proxy=" + httpProxy);
            }
            if (configFile != null && !configFile.isEmpty()) {
                cmd.add("--conf-path=" + configFile);
            }
            if (extraArgs != null) {
                cmd.addAll(extraArgs);
                this.lastExtraArgs = new ArrayList<>(extraArgs); // Store for restart
            } else {
                this.lastExtraArgs = new ArrayList<>();
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            aria2Process = pb.start();

            // Wait for aria2c to become responsive (max 10 seconds)
            if (waitForAria2State(true, 10000, 200)) {
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
     * Stop aria2c process started by this client.
     */
    public boolean stopAria2c() {
        if (aria2Process != null /* && aria2Process.isAlive() as its starts as dameon, its exit immediately */) {
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
        }

        // Verify it's actually stopped
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
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.addTorrent", String.class, params.toArray()).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.addTorrent", params.toArray());
            return sendRpcHttp(payload, String.class).result;
        }
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
        if (useWebSocket) {
            try {
                gids = sendRpcWebSocket("aria2.addMetalink", List.class, params.toArray()).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.addMetalink", params.toArray());
            gids = sendRpcHttp(payload, List.class).result;
        }
        if (gids == null || gids.isEmpty()) {
            throw new IOException("aria2.addMetalink returned no GIDs");
        }
        return gids.get(0).toString();
    }

    /**
     * Force remove a download by GID.
     */
    public String forceRemove(String gid) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.forceRemove", String.class, gid).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.forceRemove", gid);
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Pause all downloads.
     */
    public String pauseAll() throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.pauseAll", String.class).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.pauseAll");
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Force pause a download by GID.
     */
    public String forcePause(String gid) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.forcePause", String.class, gid).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.forcePause", gid);
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Force pause all downloads.
     */
    public String forcePauseAll() throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.forcePauseAll", String.class).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.forcePauseAll");
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Unpause all downloads.
     */
    public String unpauseAll() throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.unpauseAll", String.class).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.unpauseAll");
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Change position of a download in the queue.
     */
    public String changePosition(String gid, int pos, String how) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.changePosition", String.class, gid, pos, how).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.changePosition", gid, pos, how);
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Change options for a specific download by GID.
     */
    public String changeOption(String gid, Map<String, Object> options) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.changeOption", String.class, gid, options).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.changeOption", gid, options);
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Change global options for aria2.
     */
    public String changeGlobalOption(Map<String, Object> options) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.changeGlobalOption", String.class, options).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.changeGlobalOption", options);
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Shutdown aria2c daemon.
     */
    public String shutdown() throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.shutdown", String.class).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.shutdown");
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Force shutdown aria2c daemon.
     */
    public String forceShutdown() throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.forceShutdown", String.class).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.forceShutdown");
            return sendRpcHttp(payload, String.class).result;
        }
    }

    /**
     * Save session to file.
     */
    public String saveSession() throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.saveSession", String.class).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.saveSession");
            return sendRpcHttp(payload, String.class).result;
        }
    }

    // Implement aria2.getUris
    public List<String> getUris(String gid) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.getUris", List.class, gid).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.getUris", gid);
            return sendRpcHttp(payload, List.class).result;
        }
    }

    // Implement aria2.getFiles
    public List<Map<String, Object>> getFiles(String gid) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.getFiles", new TypeReference<List<Map<String, Object>>>() {
                }, gid).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.getFiles", gid);
            return sendRpcHttp(payload, new TypeReference<List<Map<String, Object>>>() {
            }).result;
        }
    }

    // Implement aria2.getPeers
    public List<Map<String, Object>> getPeers(String gid) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.getPeers", new TypeReference<List<Map<String, Object>>>() {
                }, gid).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.getPeers", gid);
            return sendRpcHttp(payload, new TypeReference<List<Map<String, Object>>>() {
            }).result;
        }
    }

    // Implement aria2.getServers
    public List<Map<String, Object>> getServers(String gid) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.getServers", new TypeReference<List<Map<String, Object>>>() {
                }, gid).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.getServers", gid);
            return sendRpcHttp(payload, new TypeReference<List<Map<String, Object>>>() {
            }).result;
        }
    }

    // Implement aria2.getOption
    public Map<String, Object> getOption(String gid) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.getOption", new TypeReference<Map<String, Object>>() {
                }, gid).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.getOption", gid);
            return sendRpcHttp(payload, new TypeReference<Map<String, Object>>() {
            }).result;
        }
    }

    // Implement aria2.getGlobalOption
    public Map<String, Object> getGlobalOption() throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.getGlobalOption", new TypeReference<Map<String, Object>>() {
                }).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.getGlobalOption");
            return sendRpcHttp(payload, new TypeReference<Map<String, Object>>() {
            }).result;
        }
    }

    // Implement aria2.getGlobalStat
    public Map<String, Object> getGlobalStat() throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.getGlobalStat", new TypeReference<Map<String, Object>>() {
                }).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.getGlobalStat");
            return sendRpcHttp(payload, new TypeReference<Map<String, Object>>() {
            }).result;
        }
    }

    // Implement aria2.purgeDownloadResult
    public String purgeDownloadResult() throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.purgeDownloadResult", String.class).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.purgeDownloadResult");
            return sendRpcHttp(payload, String.class).result;
        }
    }

    // Implement aria2.removeDownloadResult
    public String removeDownloadResult(String gid) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.removeDownloadResult", String.class, gid).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.removeDownloadResult", gid);
            return sendRpcHttp(payload, String.class).result;
        }
    }

    // Implement aria2.getVersion
    public Map<String, Object> getVersion() throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.getVersion", new TypeReference<Map<String, Object>>() {
                }).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.getVersion");
            return sendRpcHttp(payload, new TypeReference<Map<String, Object>>() {
            }).result;
        }
    }

    // Implement aria2.getSessionInfo
    public Map<String, Object> getSessionInfo() throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("aria2.getSessionInfo", new TypeReference<Map<String, Object>>() {
                }).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("aria2.getSessionInfo");
            return sendRpcHttp(payload, new TypeReference<Map<String, Object>>() {
            }).result;
        }
    }

    // Implement system.multicall
    public List<Map<String, Object>> systemMulticall(List<Map<String, Object>> calls)
            throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                return sendRpcWebSocket("system.multicall", new TypeReference<List<Map<String, Object>>>() {
                }, calls).result;
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload = buildPayload("system.multicall", calls);
            return sendRpcHttp(payload, new TypeReference<List<Map<String, Object>>>() {
            }).result;
        }
    }

    /**
     * Enable or disable WebSocket transport for JSON-RPC.
     */
    public void setUseWebSocket(boolean useWebSocket) {
        this.useWebSocket = useWebSocket;
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
                    e.printStackTrace();
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
                LOGGER.severe("WebSocket error: " + ex.getMessage());
                ex.printStackTrace();
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
     * Disconnect from aria2 WebSocket JSON-RPC server.
     */
    public void disconnectWebSocket() {
        isShuttingDown = true;
        useWebSocket = false; // Prevent reconnection attempts
        stopWebSocketHealthCheck();

        if (wsClient != null) {
            try {
                // Send close frame with normal closure code
                wsClient.close(1000, "Application shutdown");

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
                new IOException("WebSocket disconnected during shutdown")));
        wsResponses.clear();
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
                // Clear stale responses older than 30 seconds
                List<Integer> staleIds = new ArrayList<>();

                wsResponses.forEach((id, future) -> {
                    if (future.isDone() || future.isCompletedExceptionally()) {
                        staleIds.add(id);
                    }
                });

                for (Integer id : staleIds) {
                    CompletableFuture<String> future = wsResponses.remove(id);
                    if (future != null && !future.isDone() && !future.isCompletedExceptionally()) {
                        future.completeExceptionally(new TimeoutException("Request timed out"));
                    }
                }

                // Check if connection is still alive with a ping
                if (wsClient != null && wsClient.isOpen()) {
                    wsClient.sendPing();
                }
            } catch (Exception e) {
                LOGGER.severe("Error in WebSocket health check: " + e.getMessage());
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
                    disconnectWebSocket();
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

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes("UTF-8"));
            os.flush();
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
    private <T> Aria2RpcResponse<T> sendRpcWebSocket(String method, Class<T> resultType, Object... params)
            throws Exception {
        if (isShuttingDown) {
            throw new IOException("Cannot send WebSocket RPC during shutdown");
        }
        if (wsClient == null || !wsClient.isOpen()) {
            connectWebSocket();
        }

        int id = wsRequestId++;
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

        int id = wsRequestId++;
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
            if (rpcToken != null) {
                Object[] withToken = new Object[params.length + 1];
                withToken[0] = "token:" + rpcToken;
                System.arraycopy(params, 0, withToken, 1, params.length);
                p = withToken;
            }
            map.put("params", p);
        } else if (rpcToken != null) {
            map.put("params", new Object[] { "token:" + rpcToken });
        }
        return OBJECT_MAPPER.writeValueAsString(map);
    }

    public String addUriRpc(String url, Map<String, Object> options) throws IOException, Aria2RpcException {
        if (useWebSocket) {
            try {
                if (options != null && !options.isEmpty()) {
                    return sendRpcWebSocket("aria2.addUri", String.class, new String[] { url }, options).result;
                } else {
                    return sendRpcWebSocket("aria2.addUri", String.class, (Object) new String[] { url }).result;
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            String payload;
            if (options != null && !options.isEmpty()) {
                payload = buildPayload("aria2.addUri", new String[] { url }, options);
            } else {
                payload = buildPayload("aria2.addUri", (Object) new String[] { url });
            }
            return sendRpcHttp(payload, String.class).result;
        }
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
