package org.curl;

import org.manager.GlobalSettings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.manager.tools.AbstractToolManager;

/**
 * Tool manager for cURL HTTP client.
 * Handles discovery, validation, and feature detection for curl.
 */
public class CurlToolManager extends AbstractToolManager {

    public static final String TOOL_ID = "curl";
    public static final String EXECUTABLE_NAME = "curl";

    // Common locations where curl might be installed
    private static final List<String> COMMON_LOCATIONS = Arrays.asList(
            "/usr/bin/curl",
            "/usr/local/bin/curl",
            "/bin/curl",
            "/opt/curl/bin/curl"
    );

    /**
     * Creates a new CurlToolManager.
     *
     * @param settings The global settings
     * @param executor The executor service for async operations
     */
    public CurlToolManager(GlobalSettings settings, ExecutorService executor) {
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
        return settings.getCurlPath();
    }

    @Override
    protected void updateSettingsPath(String path) {
        settings.setCurlPath(path);
        settings.setCurlAvailable(path != null && isValidExecutable(path));
    }

    @Override
    protected List<String> getCommonLocations() {
        return COMMON_LOCATIONS;
    }

    @Override
    protected String[] getVersionCommand(String toolPath) {
        return new String[]{toolPath, "--version"};
    }

    @Override
    protected String parseVersion(String output) {
        // curl version output format: "curl 7.68.0 (x86_64-pc-linux-gnu)"
        if (output != null) {
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.toLowerCase().startsWith("curl ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return parts[1]; // Extract version number
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
            // Get version output which includes feature information
            Process process = Runtime.getRuntime().exec(new String[]{toolPath, "--version"});
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                String output = readProcessOutput(process);
                String lowerOutput = output.toLowerCase();

                // Protocol support
                features.put("http", lowerOutput.contains("http"));
                features.put("https", lowerOutput.contains("https"));
                features.put("ftp", lowerOutput.contains("ftp"));
                features.put("ftps", lowerOutput.contains("ftps"));
                features.put("sftp", lowerOutput.contains("sftp"));
                features.put("scp", lowerOutput.contains("scp"));

                // SSL/TLS support
                features.put("ssl", lowerOutput.contains("ssl") || lowerOutput.contains("tls"));
                features.put("openssl", lowerOutput.contains("openssl"));
                features.put("gnutls", lowerOutput.contains("gnutls"));

                // Compression support
                features.put("gzip", lowerOutput.contains("libz") || lowerOutput.contains("zlib"));
                features.put("brotli", lowerOutput.contains("brotli"));

                // Authentication methods
                features.put("ntlm", lowerOutput.contains("ntlm"));
                features.put("kerberos", lowerOutput.contains("gss") || lowerOutput.contains("krb"));
                features.put("digest-auth", true); // Usually supported

                // Proxy support
                features.put("proxy", true); // curl always supports proxies
                features.put("socks", lowerOutput.contains("socks"));

                // HTTP/2 and HTTP/3 support
                features.put("http2", lowerOutput.contains("http2") || lowerOutput.contains("nghttp2"));
                features.put("http3", lowerOutput.contains("http3") || lowerOutput.contains("quiche"));

                // Additional features
                features.put("ipv6", lowerOutput.contains("ipv6"));
                features.put("unix-sockets", lowerOutput.contains("unix"));
                features.put("cookies", true); // Always supported
                features.put("resume", true); // Always supported with -C
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to detect curl features", e);
        }

        return features;
    }

    @Override
    protected boolean supportsEmbeddedBinary() {
        return true; // curl can be distributed as binary
    }

    @Override
    protected String getEmbeddedBinaryResourcePath() {
        return "/curl/bin/curl"; // Path to embedded binary in resources
    }

    @Override
    protected boolean executeBasicCheck(String toolPath) {
        try {
            // Test basic functionality with a simple request
            Process process = Runtime.getRuntime().exec(new String[]{
                toolPath, "--version"
            });

            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : 0;

            if (exitCode == 0) {
                LOGGER.fine("curl basic check passed");
                return true;
            } else {
                LOGGER.warning("curl basic check failed with exit code: " + exitCode);
                return false;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "curl basic check failed", e);
            return false;
        }
    }

    /**
     * Checks if curl supports specific protocols.
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
     * Checks if curl supports HTTP/2.
     *
     * @return true if HTTP/2 is supported
     */
    public boolean isHttp2Supported() {
        return checkFeatureSupport("http2");
    }

    /**
     * Checks if curl supports HTTP/3.
     *
     * @return true if HTTP/3 is supported
     */
    public boolean isHttp3Supported() {
        return checkFeatureSupport("http3");
    }

    /**
     * Gets the supported SSL/TLS libraries.
     *
     * @return List of supported SSL libraries
     */
    public List<String> getSslLibraries() {
        Map<String, Boolean> features = getSupportedFeatures();
        return features.entrySet().stream()
                .filter(entry -> entry.getKey().contains("ssl") || entry.getKey().contains("tls"))
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Gets curl-specific configuration recommendations.
     *
     * @return Map of recommended configuration options
     */
    public Map<String, String> getRecommendedConfig() {
        Map<String, String> config = new HashMap<>();

        config.put("user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36");
        config.put("connect-timeout", "30");
        config.put("max-time", "300");
        config.put("retry", "3");
        config.put("retry-delay", "1");

        if (checkFeatureSupport("http2")) {
            config.put("http2", "true");
        }

        if (checkFeatureSupport("gzip")) {
            config.put("compressed", "true");
        }

        return config;
    }

    /**
     * Tests basic download functionality.
     *
     * @return true if basic download test passes
     */
    public boolean testBasicDownload() {
        String toolPath = getToolPath();
        if (toolPath == null) {
            return false;
        }

        try {
            // Test with a simple head request to httpbin
            Process process = Runtime.getRuntime().exec(new String[]{
                toolPath, "--head", "--silent", "--fail", "https://httpbin.org/status/200"
            });

            boolean completed = process.waitFor(15, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : -1;

            return exitCode == 0;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "curl basic download test failed", e);
            return false;
        }
    }
}
