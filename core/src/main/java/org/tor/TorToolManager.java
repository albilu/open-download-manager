package org.tor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.manager.GlobalSettings;
import org.manager.tools.AbstractToolManager;

/**
 * Tool manager for Tor anonymity network.
 * Handles discovery, validation, and feature detection for tor.
 */
public class TorToolManager extends AbstractToolManager {

    public static final String TOOL_ID = "tor";
    public static final String EXECUTABLE_NAME = "tor";

    // Common locations where tor might be installed
    private static final List<String> COMMON_LOCATIONS = Arrays.asList(
            "/usr/bin/tor",
            "/usr/local/bin/tor",
            "/opt/tor/bin/tor");

    /**
     * Creates a new TorToolManager.
     *
     * @param settings The global settings
     * @param executor The executor service for async operations
     */
    public TorToolManager(GlobalSettings settings, ExecutorService executor) {
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
        return settings.getTorPath();
    }

    @Override
    protected void updateSettingsPath(String path) {
        settings.setTorPath(path);
        settings.setTorAvailable(path != null && isValidExecutable(path));
    }

    @Override
    protected List<String> getCommonLocations() {
        return COMMON_LOCATIONS;
    }

    @Override
    protected String[] getVersionCommand(String toolPath) {
        return new String[] { toolPath, "--version" };
    }

    @Override
    protected String parseVersion(String output) {
        // tor version output formats:
        // "Tor version 0.4.6.10"
        // "Jan 01 00:00:00.000 [notice] Tor 0.4.7.7 running on Linux"
        if (output != null) {
            String[] lines = output.split("\n");
            for (String line : lines) {
                String lowerLine = line.toLowerCase();

                // Handle standard version format: "Tor version X.Y.Z"
                if (lowerLine.contains("tor version")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        return parts[2]; // Extract version number
                    }
                }

                // Handle log format: "[notice] Tor X.Y.Z running on"
                if (lowerLine.contains("tor") && lowerLine.contains("running on")) {
                    // Use regex to extract version pattern
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                            "tor\\s+(\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE);
                    java.util.regex.Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected Map<String, Boolean> detectFeatures() {
        Map<String, Boolean> features = new HashMap<>();

        String toolPath = getToolPath();
        if (toolPath == null) {
            return features;
        }

        try {
            // Get help output to check available options
            Process process = Runtime.getRuntime().exec(new String[] { toolPath, "--help" });
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                String output = readProcessOutput(process);
                String lowerOutput = output.toLowerCase();

                // SOCKS proxy features
                features.put("socks-proxy", lowerOutput.contains("socksport") ||
                        lowerOutput.contains("socks"));
                features.put("socks5", true); // Tor always supports SOCKS5
                features.put("socks4", lowerOutput.contains("socks4"));

                // Control features
                features.put("control-port", lowerOutput.contains("controlport"));
                features.put("control-auth", lowerOutput.contains("hashedcontrolpassword") ||
                        lowerOutput.contains("cookieauthentication"));

                // Hidden services
                features.put("hidden-services", lowerOutput.contains("hiddenservice"));
                features.put("onion-services", lowerOutput.contains("hiddenservice") ||
                        lowerOutput.contains("onion"));

                // Bridge support
                features.put("bridges", lowerOutput.contains("bridge") ||
                        lowerOutput.contains("usebridges"));
                features.put("pluggable-transports", lowerOutput.contains("pluggabletransports") ||
                        lowerOutput.contains("clienttransport"));

                // Network features
                features.put("ipv6", lowerOutput.contains("ipv6"));
                features.put("dns", lowerOutput.contains("dnsport"));
                features.put("transparent-proxy", lowerOutput.contains("transparentproxy") ||
                        lowerOutput.contains("transport"));

                // Security features
                features.put("strict-nodes", lowerOutput.contains("strictnodes"));
                features.put("exclude-nodes", lowerOutput.contains("excludenodes"));
                features.put("exit-policy", lowerOutput.contains("exitpolicy"));

                // Configuration
                features.put("config-file", lowerOutput.contains("torrc") ||
                        lowerOutput.contains("-f"));
                features.put("data-directory", lowerOutput.contains("datadirectory"));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to detect tor features", e);
        }

        return features;
    }

    @Override
    protected boolean supportsEmbeddedBinary() {
        return false; // Tor is typically system-installed and requires service management
    }

    @Override
    protected String getEmbeddedBinaryResourcePath() {
        return null; // No embedded binary for tor
    }

    @Override
    protected boolean executeBasicCheck(String toolPath) {
        try {
            // Test basic functionality with version check
            Process process = Runtime.getRuntime().exec(new String[] {
                    toolPath, "--version"
            });

            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : -1;

            if (exitCode == 0) {
                LOGGER.debug("tor basic check passed");
                return true;
            } else {
                LOGGER.warn("tor basic check failed with exit code: " + exitCode);
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("tor basic check failed", e);
            return false;
        }
    }

    /**
     * Checks if Tor SOCKS proxy is available on default port.
     *
     * @return true if SOCKS proxy is available
     */
    public boolean isSocksProxyAvailable() {
        return checkFeatureSupport("socks-proxy");
    }

    /**
     * Checks if Tor control port is supported.
     *
     * @return true if control port is supported
     */
    public boolean isControlPortSupported() {
        return checkFeatureSupport("control-port");
    }

    /**
     * Checks if Tor hidden services are supported.
     *
     * @return true if hidden services are supported
     */
    public boolean areHiddenServicesSupported() {
        return checkFeatureSupport("hidden-services");
    }

    /**
     * Gets Tor-specific configuration recommendations.
     *
     * @return Map of recommended configuration options
     */
    public Map<String, String> getRecommendedConfig() {
        Map<String, String> config = new HashMap<>();

        if (checkFeatureSupport("socks-proxy")) {
            config.put("SocksPort", "9050");
        }

        if (checkFeatureSupport("control-port")) {
            config.put("ControlPort", "9051");
        }

        if (checkFeatureSupport("data-directory")) {
            config.put("DataDirectory", "/var/lib/tor");
        }

        if (checkFeatureSupport("dns")) {
            config.put("DNSPort", "5353");
        }

        // Security recommendations
        config.put("StrictNodes", "1");
        config.put("SafeLogging", "1");
        config.put("Log", "notice file /var/log/tor/notices.log");

        return config;
    }

    /**
     * Tests if Tor service is running and accessible.
     *
     * @return true if Tor service is accessible
     */
    public boolean testTorService() {
        try {
            // Try to connect to default SOCKS port
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 9050), 5000);
            socket.close();
            return true;
        } catch (Exception e) {
            LOGGER.debug("Tor service test failed", e);
            return false;
        }
    }

    /**
     * Gets the default SOCKS proxy configuration for Tor.
     *
     * @return Map with proxy configuration
     */
    public Map<String, String> getSocksProxyConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("proxy-type", "socks5");
        config.put("proxy-host", "127.0.0.1");
        config.put("proxy-port", "9050");
        return config;
    }
}
