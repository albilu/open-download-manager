package org.aria2;

import org.manager.GlobalSettings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.manager.tools.AbstractToolManager;

/**
 * Tool manager for aria2 download accelerator.
 * Handles discovery, validation, and feature detection for aria2c.
 */
public class Aria2ToolManager extends AbstractToolManager {

    public static final String TOOL_ID = "aria2";
    public static final String EXECUTABLE_NAME = "aria2c";

    // Common locations where aria2c might be installed
    private static final List<String> COMMON_LOCATIONS = Arrays.asList(
            "/usr/bin/aria2c",
            "/usr/local/bin/aria2c",
            "/opt/aria2/bin/aria2c",
            "/snap/bin/aria2c"
    );

    /**
     * Creates a new Aria2ToolManager.
     *
     * @param settings The global settings
     * @param executor The executor service for async operations
     */
    public Aria2ToolManager(GlobalSettings settings, ExecutorService executor) {
        super(settings, executor);
    }

    @Override
    public String getToolId() {
        return TOOL_ID;
    }

    @Override
    public String getExecutableName() {
        return EXECUTABLE_NAME;
    }

    @Override
    protected String getConfiguredPath() {
        return settings.getAria2Path();
    }

    @Override
    protected void updateSettingsPath(String path) {
        settings.setAria2Path(path);
        settings.setAria2Available(path != null && isValidExecutable(path));
    }

    @Override
    protected List<String> getCommonLocations() {
        return COMMON_LOCATIONS;
    }

    /** Shared prefix for every aria2 discovery and validation operation. */
    private String[] command(String toolPath, String... arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(toolPath);
        if (!settings.isHonorExternalAria2Configuration()) {
            command.add("--no-conf");
        }
        command.addAll(Arrays.asList(arguments));
        return command.toArray(String[]::new);
    }

    @Override
    protected String[] getVersionCommand(String toolPath) {
        return command(toolPath, "--version");
    }

    @Override
    protected String parseVersion(String output) {
        // aria2 version output format: "aria2 version 1.36.0"
        if (output != null) {
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.toLowerCase().contains("aria2 version")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        return parts[2]; // Extract version number
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected Map<String, Boolean> detectFeatures() {
        Map<String, Boolean> features = new HashMap<>();

        // Get detailed version information
        String toolPath = getToolPath();
        if (toolPath == null) {
            return features;
        }

        try {
            // Get help output to check available options
            Process process = Runtime.getRuntime().exec(command(toolPath, "--help"));
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                String output = readProcessOutput(process);
                String lowerOutput = output.toLowerCase();

                // Check protocol support
                features.put("http", true); // aria2 always supports HTTP/HTTPS
                features.put("https", true);
                features.put("ftp", lowerOutput.contains("ftp") || true); // Usually supported
                features.put("bittorrent", lowerOutput.contains("bittorrent") ||
                                         lowerOutput.contains("torrent") || true);
                features.put("metalink", lowerOutput.contains("metalink") || true);

                // Check advanced features
                features.put("rpc", lowerOutput.contains("rpc-listen") ||
                                   lowerOutput.contains("enable-rpc"));
                features.put("websocket", lowerOutput.contains("rpc-listen") &&
                                         lowerOutput.contains("rpc-allow-origin"));
                features.put("dht", lowerOutput.contains("enable-dht"));
                features.put("peer-exchange", lowerOutput.contains("enable-peer-exchange"));
                features.put("local-peer-discovery", lowerOutput.contains("bt-enable-lpd"));

                // Connection features
                features.put("multi-connection", lowerOutput.contains("split") ||
                                               lowerOutput.contains("max-connection-per-server"));
                features.put("resume", lowerOutput.contains("continue"));
                features.put("proxy", lowerOutput.contains("all-proxy") ||
                                    lowerOutput.contains("http-proxy"));

                // File allocation methods
                features.put("file-allocation", lowerOutput.contains("file-allocation"));
                features.put("preallocation", lowerOutput.contains("prealloc"));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to detect aria2 features", e);
        }

        return features;
    }

    @Override
    protected boolean supportsEmbeddedBinary() {
        return false; // aria2 is typically system-installed
    }

    @Override
    protected String getEmbeddedBinaryResourcePath() {
        return null; // No embedded binary for aria2
    }

    @Override
    protected boolean executeBasicCheck(String toolPath) {
        try {
            // Test basic functionality with a simple command
            Process process = Runtime.getRuntime().exec(command(toolPath, "--version"));

            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : -1;

            if (exitCode == 0) {
                LOGGER.debug("aria2 basic check passed");
                return true;
            } else {
                LOGGER.warn("aria2 basic check failed with exit code: " + exitCode);
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("aria2 basic check failed", e);
            return false;
        }
    }

    /**
     * Checks if aria2 supports specific protocols.
     *
     * @param protocols List of protocols to check
     * @return true if all protocols are supported
     */
    public boolean checkProtocolSupport(String... protocols) {
        Map<String, Boolean> features = getSupportedFeatures();
        return Arrays.stream(protocols)
                .allMatch(protocol -> features.getOrDefault(protocol.toLowerCase(), false));
    }

    /**
     * Checks if aria2 RPC interface is available.
     *
     * @return true if RPC is supported
     */
    public boolean isRpcSupported() {
        return checkFeatureSupport("rpc");
    }

    /**
     * Checks if aria2 supports BitTorrent DHT.
     *
     * @return true if DHT is supported
     */
    public boolean isDhtSupported() {
        return checkFeatureSupport("dht");
    }

    /**
     * Gets aria2-specific configuration recommendations.
     *
     * @return Map of recommended configuration options
     */
    public Map<String, String> getRecommendedConfig() {
        Map<String, String> config = new HashMap<>();

        if (checkFeatureSupport("multi-connection")) {
            config.put("max-connection-per-server", "5");
            config.put("split", "5");
        }

        if (checkFeatureSupport("resume")) {
            config.put("continue", "true");
        }

        if (checkFeatureSupport("file-allocation")) {
            config.put("file-allocation", "prealloc");
        }

        if (checkFeatureSupport("rpc")) {
            config.put("enable-rpc", "true");
            config.put("rpc-listen-all", "false");
            config.put("rpc-listen-port",
                    String.valueOf(GlobalSettings.DEFAULT_ARIA2_RPC_PORT));
        }

        return config;
    }
}
