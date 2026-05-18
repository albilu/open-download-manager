package org.httrack;

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
 * Tool manager for HTTrack website copier.
 * Handles discovery, validation, and feature detection for httrack.
 */
public class HttrackToolManager extends AbstractToolManager {

    public static final String TOOL_ID = "httrack";
    public static final String EXECUTABLE_NAME = "httrack";

    // Common locations where httrack might be installed
    private static final List<String> COMMON_LOCATIONS = Arrays.asList(
            "/usr/bin/httrack",
            "/usr/local/bin/httrack",
            "/opt/httrack/bin/httrack"
    );

    /**
     * Creates a new HttrackToolManager.
     *
     * @param settings The global settings
     * @param executor The executor service for async operations
     */
    public HttrackToolManager(GlobalSettings settings, ExecutorService executor) {
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
        return settings.getHttrackPath();
    }

    @Override
    protected void updateSettingsPath(String path) {
        settings.setHttrackPath(path);
        settings.setHttrackAvailable(path != null && isValidExecutable(path));
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
        // httrack version output format: "HTTrack version 3.49.2"
        if (output != null) {
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.toLowerCase().contains("httrack version")) {
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

        String toolPath = getToolPath();
        if (toolPath == null) {
            return features;
        }

        try {
            // Get help output to check available options
            Process process = Runtime.getRuntime().exec(new String[]{toolPath, "--help"});
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                String output = readProcessOutput(process);
                String lowerOutput = output.toLowerCase();

                // Basic website copying features
                features.put("recursive", lowerOutput.contains("recursive") ||
                                        lowerOutput.contains("-r"));
                features.put("robots-txt", lowerOutput.contains("robots"));
                features.put("external-links", lowerOutput.contains("external"));
                features.put("images", true); // Always supported
                features.put("stylesheets", true); // Always supported
                features.put("javascript", true); // Always supported

                // Connection features
                features.put("max-connections", lowerOutput.contains("connection") ||
                                              lowerOutput.contains("-c"));
                features.put("rate-limit", lowerOutput.contains("rate") ||
                                         lowerOutput.contains("limit"));
                features.put("timeout", lowerOutput.contains("timeout"));

                // Filter features
                features.put("include-patterns", lowerOutput.contains("include") ||
                                               lowerOutput.contains("+"));
                features.put("exclude-patterns", lowerOutput.contains("exclude") ||
                                               lowerOutput.contains("-"));
                features.put("mime-types", lowerOutput.contains("mime"));

                // Advanced features
                features.put("proxy", lowerOutput.contains("proxy"));
                features.put("cookies", lowerOutput.contains("cookie"));
                features.put("user-agent", lowerOutput.contains("user-agent"));
                features.put("resume", lowerOutput.contains("continue") ||
                                     lowerOutput.contains("update"));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to detect httrack features", e);
        }

        return features;
    }

    @Override
    protected boolean supportsEmbeddedBinary() {
        return false; // httrack is typically system-installed
    }

    @Override
    protected String getEmbeddedBinaryResourcePath() {
        return null; // No embedded binary for httrack
    }

    @Override
    protected boolean executeBasicCheck(String toolPath) {
        try {
            // Test basic functionality with version check
            Process process = Runtime.getRuntime().exec(new String[]{
                toolPath, "--version"
            });

            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : -1;

            if (exitCode == 0) {
                LOGGER.fine("httrack basic check passed");
                return true;
            } else {
                LOGGER.warning("httrack basic check failed with exit code: " + exitCode);
                return false;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "httrack basic check failed", e);
            return false;
        }
    }

    /**
     * Checks if httrack supports recursive downloading.
     *
     * @return true if recursive downloading is supported
     */
    public boolean isRecursiveSupported() {
        return checkFeatureSupport("recursive");
    }

    /**
     * Checks if httrack respects robots.txt.
     *
     * @return true if robots.txt support is available
     */
    public boolean isRobotsTxtSupported() {
        return checkFeatureSupport("robots-txt");
    }

    /**
     * Gets httrack-specific configuration recommendations.
     *
     * @return Map of recommended configuration options
     */
    public Map<String, String> getRecommendedConfig() {
        Map<String, String> config = new HashMap<>();

        if (checkFeatureSupport("max-connections")) {
            config.put("max-connections", "5");
        }

        if (checkFeatureSupport("recursive")) {
            config.put("depth", "2");
        }

        if (checkFeatureSupport("rate-limit")) {
            config.put("max-rate", "0"); // No limit by default
        }

        if (checkFeatureSupport("robots-txt")) {
            config.put("robots", "1"); // Respect robots.txt
        }

        // Default include patterns for common web assets
        config.put("include-images", "*.png,*.gif,*.jpg,*.jpeg");
        config.put("include-styles", "*.css");
        config.put("include-scripts", "*.js");

        return config;
    }

    /**
     * Tests basic website copying functionality.
     *
     * @return true if basic test passes
     */
    public boolean testBasicFunction() {
        String toolPath = getToolPath();
        if (toolPath == null) {
            return false;
        }

        try {
            // Test with a dry run that doesn't actually download
            Process process = Runtime.getRuntime().exec(new String[]{
                toolPath, "--quiet", "--dry-run", "https://example.com"
            });

            boolean completed = process.waitFor(15, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : -1;

            return exitCode == 0;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "httrack basic function test failed", e);
            return false;
        }
    }
}
