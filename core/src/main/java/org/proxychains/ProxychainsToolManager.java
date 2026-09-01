package org.proxychains;

import org.manager.GlobalSettings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.manager.tools.AbstractToolManager;

/**
 * Tool manager for Proxychains proxy wrapper.
 * Handles discovery, validation, and feature detection for proxychains.
 */
public class ProxychainsToolManager extends AbstractToolManager {

    public static final String TOOL_ID = "proxychains";
    public static final String EXECUTABLE_NAME = "proxychains4";

    // Common locations where proxychains might be installed
    private static final List<String> COMMON_LOCATIONS = Arrays.asList(
            "/usr/bin/proxychains4",
            "/usr/bin/proxychains",
            "/usr/local/bin/proxychains4",
            "/usr/local/bin/proxychains"
    );

    /**
     * Creates a new ProxychainsToolManager.
     *
     * @param settings The global settings
     * @param executor The executor service for async operations
     */
    public ProxychainsToolManager(GlobalSettings settings, ExecutorService executor) {
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
        return settings.getProxychainsPath();
    }

    @Override
    protected void updateSettingsPath(String path) {
        settings.setProxychainsPath(path);
        settings.setProxychainsAvailable(path != null && isValidExecutable(path));
    }

    @Override
    protected List<String> getCommonLocations() {
        return COMMON_LOCATIONS;
    }

    @Override
    protected String[] getVersionCommand(String toolPath) {
        return new String[]{toolPath, "-h"}; // proxychains shows version in help
    }

    @Override
    protected String parseVersion(String output) {
        // proxychains version output format: "ProxyChains-NG 4.14"
        if (output != null) {
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.toLowerCase().contains("proxychains") &&
                    (line.contains("ng") || line.matches(".*\\d+\\.\\d+.*"))) {
                    String[] parts = line.split("\\s+");
                    for (String part : parts) {
                        if (part.matches("\\d+\\.\\d+.*")) {
                            return part; // Extract version number
                        }
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
            Process process = Runtime.getRuntime().exec(new String[]{toolPath, "-h"});
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                String output = readProcessOutput(process);
                String lowerOutput = output.toLowerCase();

                // Chain modes
                features.put("dynamic-chain", lowerOutput.contains("dynamic") ||
                                            lowerOutput.contains("-d"));
                features.put("strict-chain", lowerOutput.contains("strict") ||
                                           lowerOutput.contains("-s"));
                features.put("random-chain", lowerOutput.contains("random") ||
                                           lowerOutput.contains("-r"));

                // Proxy protocol support
                features.put("socks4", true); // Usually supported
                features.put("socks5", true); // Usually supported
                features.put("http", true);   // Usually supported

                // DNS features
                features.put("proxy-dns", lowerOutput.contains("proxy_dns") ||
                                        lowerOutput.contains("remote dns"));
                features.put("tcp-only", lowerOutput.contains("tcp"));

                // Output control
                features.put("quiet", lowerOutput.contains("quiet") ||
                                    lowerOutput.contains("-q"));

                // Configuration
                features.put("config-file", lowerOutput.contains("config") ||
                                          lowerOutput.contains("-f"));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to detect proxychains features", e);
        }

        return features;
    }

    @Override
    protected boolean supportsEmbeddedBinary() {
        return false; // proxychains is typically system-installed
    }

    @Override
    protected String getEmbeddedBinaryResourcePath() {
        return null; // No embedded binary for proxychains
    }

    @Override
    protected boolean executeBasicCheck(String toolPath) {
        try {
            // Test basic functionality with help command
            Process process = Runtime.getRuntime().exec(new String[]{
                toolPath, "-h"
            });

            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : -1;

            // proxychains help command typically returns 0 or 1
            if (exitCode == 0 || exitCode == 1) {
                LOGGER.debug("proxychains basic check passed");
                return true;
            } else {
                LOGGER.warn("proxychains basic check failed with exit code: " + exitCode);
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("proxychains basic check failed", e);
            return false;
        }
    }

    /**
     * Checks if proxychains supports specific proxy protocols.
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
     * Checks if proxychains supports DNS proxying.
     *
     * @return true if DNS proxying is supported
     */
    public boolean isDnsProxySupported() {
        return checkFeatureSupport("proxy-dns");
    }

    /**
     * Gets proxychains-specific configuration recommendations.
     *
     * @return Map of recommended configuration options
     */
    public Map<String, String> getRecommendedConfig() {
        Map<String, String> config = new HashMap<>();

        if (checkFeatureSupport("dynamic-chain")) {
            config.put("chain-mode", "dynamic_chain");
        }

        if (checkFeatureSupport("proxy-dns")) {
            config.put("proxy_dns", "true");
        }

        if (checkFeatureSupport("tcp-only")) {
            config.put("tcp_read_time_out", "15000");
            config.put("tcp_connect_time_out", "8000");
        }

        if (checkFeatureSupport("quiet")) {
            config.put("quiet_mode", "true");
        }

        return config;
    }

    /**
     * Tests basic proxy functionality.
     *
     * @return true if basic test passes
     */
    public boolean testBasicFunction() {
        String toolPath = getToolPath();
        if (toolPath == null) {
            return false;
        }

        try {
            // Test with a simple echo command
            Process process = Runtime.getRuntime().exec(new String[]{
                toolPath, "echo", "proxychains test"
            });

            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : -1;

            return exitCode == 0;
        } catch (Exception e) {
            LOGGER.warn("proxychains basic function test failed", e);
            return false;
        }
    }
}
