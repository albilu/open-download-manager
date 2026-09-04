package org.ytdlp;

import org.manager.GlobalSettings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.manager.tools.AbstractToolManager;

/**
 * Tool manager for yt-dlp video downloader.
 * Handles discovery, validation, and feature detection for yt-dlp.
 */
public class YtDlpToolManager extends AbstractToolManager {

    public static final String TOOL_ID = "yt-dlp";
    public static final String EXECUTABLE_NAME = "yt-dlp";

    // Common locations where yt-dlp might be installed
    private static final List<String> COMMON_LOCATIONS = Arrays.asList(
            "/usr/bin/yt-dlp",
            "/usr/local/bin/yt-dlp",
            "/opt/yt-dlp/yt-dlp",
            System.getProperty("user.home") + "/.local/bin/yt-dlp",
            "/snap/bin/yt-dlp"
    );

    /**
     * Creates a new YtDlpToolManager.
     *
     * @param settings The global settings
     * @param executor The executor service for async operations
     */
    public YtDlpToolManager(GlobalSettings settings, ExecutorService executor) {
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
        return settings.getYtDlpPath();
    }

    @Override
    protected void updateSettingsPath(String path) {
        settings.setYtDlpPath(path);
        settings.setYtDlpAvailable(path != null && isValidExecutable(path));
    }

    @Override
    protected List<String> getCommonLocations() {
        return COMMON_LOCATIONS;
    }

    /** Shared prefix for every yt-dlp discovery and validation operation. */
    private String[] command(String toolPath, String... arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(toolPath);
        if (!settings.isHonorExternalYtDlpConfiguration()) {
            command.add("--ignore-config");
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
        // yt-dlp version output format: "2023.12.30" or similar
        if (output != null) {
            String[] lines = output.split("\n");
            for (String line : lines) {
                line = line.trim();
                // yt-dlp outputs just the version number on first line
                if (line.matches("\\d{4}\\.\\d{2}\\.\\d{2}.*") ||
                    line.matches("\\d+\\.\\d+\\.\\d+.*")) {
                    return line.split("\\s+")[0]; // Extract just version number
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
            Process process = Runtime.getRuntime().exec(command(toolPath, "--help"));
            if (process.waitFor(15, TimeUnit.SECONDS)) {
                String output = readProcessOutput(process);
                String lowerOutput = output.toLowerCase();

                // Basic download features
                features.put("basic-download", true); // Always supported
                features.put("playlist-support", lowerOutput.contains("playlist"));
                features.put("format-selection", lowerOutput.contains("format") &&
                                               lowerOutput.contains("-f"));

                // Audio/Video processing
                features.put("extract-audio", lowerOutput.contains("extract-audio") ||
                                            lowerOutput.contains("-x"));
                features.put("merge-formats", lowerOutput.contains("merge-output-format"));
                features.put("embed-subs", lowerOutput.contains("embed-subs"));
                features.put("embed-thumbnail", lowerOutput.contains("embed-thumbnail"));

                // Subtitle features
                features.put("write-subs", lowerOutput.contains("write-sub"));
                features.put("auto-subs", lowerOutput.contains("write-auto-sub"));
                features.put("sub-lang", lowerOutput.contains("sub-lang"));

                // Network features
                features.put("proxy", lowerOutput.contains("proxy"));
                features.put("cookies", lowerOutput.contains("cookies"));
                features.put("user-agent", lowerOutput.contains("user-agent"));
                features.put("retries", lowerOutput.contains("retries"));

                // Advanced features
                features.put("rate-limit", lowerOutput.contains("rate-limit"));
                features.put("fragment-retries", lowerOutput.contains("fragment-retries"));
                features.put("geo-bypass", lowerOutput.contains("geo-bypass"));
                features.put("age-limit", lowerOutput.contains("age-limit"));

                // Output options
                features.put("output-template", lowerOutput.contains("output") &&
                                              lowerOutput.contains("-o"));
                features.put("metadata", lowerOutput.contains("write-info-json"));
                features.put("thumbnails", lowerOutput.contains("write-thumbnail"));

                // Extractor features
                features.put("youtube", testExtractorSupport(toolPath, "youtube"));
                features.put("generic", testExtractorSupport(toolPath, "generic"));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to detect yt-dlp features", e);
        }

        return features;
    }

    @Override
    protected boolean supportsEmbeddedBinary() {
        return true; // yt-dlp can be distributed as single binary
    }

    @Override
    protected String getEmbeddedBinaryResourcePath() {
        return "/ytdlp/bin/yt-dlp_linux"; // Path to embedded binary in resources
    }

    @Override
    protected boolean executeBasicCheck(String toolPath) {
        try {
            // Test basic functionality with version check
            Process process = Runtime.getRuntime().exec(command(toolPath, "--version"));

            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : -1;

            if (exitCode == 0) {
                LOGGER.debug("yt-dlp basic check passed");
                return true;
            } else {
                LOGGER.warn("yt-dlp basic check failed with exit code: " + exitCode);
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("yt-dlp basic check failed", e);
            return false;
        }
    }

    /**
     * Tests if a specific extractor is supported.
     *
     * @param toolPath Path to yt-dlp executable
     * @param extractorName Name of the extractor to test
     * @return true if extractor is supported
     */
    private boolean testExtractorSupport(String toolPath, String extractorName) {
        try {
            Process process = Runtime.getRuntime().exec(
                    command(toolPath, "--list-extractors"));

            if (process.waitFor(10, TimeUnit.SECONDS)) {
                String output = readProcessOutput(process);
                return output.toLowerCase().contains(extractorName.toLowerCase());
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to test extractor support: " + extractorName, e);
        }
        return false;
    }

    /**
     * Checks if yt-dlp supports specific extractors.
     *
     * @param extractors List of extractor names to check
     * @return true if all extractors are supported
     */
    public boolean checkExtractorSupport(String... extractors) {
        String toolPath = getToolPath();
        if (toolPath == null) {
            return false;
        }

        return Arrays.stream(extractors)
                .allMatch(extractor -> testExtractorSupport(toolPath, extractor));
    }

    /**
     * Gets the list of supported extractors.
     *
     * @return List of supported extractor names
     */
    public List<String> getSupportedExtractors() {
        String toolPath = getToolPath();
        if (toolPath == null) {
            return Arrays.asList();
        }

        try {
            Process process = Runtime.getRuntime().exec(
                    command(toolPath, "--list-extractors"));

            if (process.waitFor(15, TimeUnit.SECONDS)) {
                String output = readProcessOutput(process);
                return Arrays.asList(output.split("\n"));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get supported extractors", e);
        }

        return Arrays.asList();
    }

    /**
     * Gets yt-dlp-specific configuration recommendations.
     *
     * @return Map of recommended configuration options
     */
    public Map<String, String> getRecommendedConfig() {
        Map<String, String> config = new HashMap<>();

        if (checkFeatureSupport("format-selection")) {
            config.put("format", "best[height<=1080]");
        }

        if (checkFeatureSupport("embed-subs")) {
            config.put("embed-subs", "true");
        }

        if (checkFeatureSupport("embed-thumbnail")) {
            config.put("embed-thumbnail", "true");
        }

        if (checkFeatureSupport("fragment-retries")) {
            config.put("fragment-retries", "3");
        }

        if (checkFeatureSupport("output-template")) {
            config.put("output", "%(title)s.%(ext)s");
        }

        return config;
    }

    /**
     * Tests basic download functionality with a test URL.
     *
     * @return true if basic download test passes
     */
    public boolean testBasicFunction() {
        String toolPath = getToolPath();
        if (toolPath == null) {
            return false;
        }

        try {
            // Test with a simple info extraction (no actual download)
            Process process = Runtime.getRuntime().exec(command(toolPath,
                    "--simulate", "--get-title",
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ"));

            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            int exitCode = completed ? process.exitValue() : -1;

            return exitCode == 0;
        } catch (Exception e) {
            LOGGER.warn("yt-dlp basic function test failed", e);
            return false;
        }
    }
}
