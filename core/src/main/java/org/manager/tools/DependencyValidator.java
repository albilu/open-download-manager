package org.manager.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.aria2.Aria2ToolManager;
import org.curl.CurlToolManager;
import org.httrack.HttrackToolManager;

import org.proxychains.ProxychainsToolManager;
import org.tor.TorToolManager;
import org.ytdlp.YtDlpToolManager;

/**
 * Validates tool dependencies using the modular ToolManager architecture. This
 * replaces the old monolithic DependencyManager approach.
 */
public class DependencyValidator {

    private static final Logger LOGGER = Logger.getLogger(DependencyValidator.class.getName());

    private final ToolManagerFactory toolManagerFactory;

    /**
     * Creates a new DependencyValidator with the given tool manager factory.
     *
     * @param toolManagerFactory The tool manager factory to use for checks
     */
    public DependencyValidator(ToolManagerFactory toolManagerFactory) {
        this.toolManagerFactory = toolManagerFactory;
    }

    /**
     * Validation result containing detailed information about dependency
     * checks.
     */
    public static class ValidationResult {

        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;
        private final Map<String, Object> details;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings,
                Map<String, Object> details) {
            this.valid = valid;
            this.errors = new ArrayList<>(errors);
            this.warnings = new ArrayList<>(warnings);
            this.details = new HashMap<>(details);
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return new ArrayList<>(errors);
        }

        public List<String> getWarnings() {
            return new ArrayList<>(warnings);
        }

        public Map<String, Object> getDetails() {
            return new HashMap<>(details);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ValidationResult{valid=").append(valid);
            if (hasErrors()) {
                sb.append(", errors=").append(errors);
            }
            if (hasWarnings()) {
                sb.append(", warnings=").append(warnings);
            }
            sb.append("}");
            return sb.toString();
        }
    }

    /**
     * Validates all core dependencies required for basic functionality. This
     * includes aria2c with minimum version and basic protocol support.
     *
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateCoreRequirements() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();

        Aria2ToolManager aria2Manager = toolManagerFactory.getAria2Manager();
        if (aria2Manager == null) {
            errors.add("Aria2 tool manager not available");
            return new ValidationResult(false, errors, warnings, details);
        }

        // Check aria2c availability and version
        if (!aria2Manager.isAvailable()) {
            errors.add("aria2c is not available - this is required for download functionality");
        } else {
            String currentVersion = aria2Manager.getVersion();
            if (currentVersion == null || !aria2Manager.checkMinimumVersion("1.34.0")) {
                errors.add("aria2c version is too old. Current: " + currentVersion
                        + ", Required: 1.34.0 or higher");
            }

            // Check aria2c core features
            Map<String, Boolean> aria2Features = aria2Manager.getSupportedFeatures();
            details.put("aria2_features", aria2Features);

            if (!aria2Features.getOrDefault("http", false)) {
                errors.add("aria2c does not support HTTP protocol");
            }
            if (!aria2Features.getOrDefault("https", false)) {
                warnings.add("aria2c does not support HTTPS protocol");
            }
            if (!aria2Features.getOrDefault("rpc", false)) {
                warnings.add("aria2c does not support RPC - some advanced features may not work");
            }

            details.put("aria2_version", currentVersion);
            details.put("aria2_path", aria2Manager.getToolPath());
        }

        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, errors, warnings, details);
    }

    /**
     * Validates curl as a fallback download tool.
     *
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateCurlFallback() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();

        CurlToolManager curlManager = toolManagerFactory.getCurlManager();
        if (curlManager == null) {
            warnings.add("Curl tool manager not available");
            return new ValidationResult(true, errors, warnings, details);
        }

        if (!curlManager.isAvailable()) {
            warnings.add("curl is not available - fallback downloads will not work");
        } else {
            String currentVersion = curlManager.getVersion();
            if (currentVersion == null || !curlManager.checkMinimumVersion("7.50.0")) {
                warnings.add("curl version is old. Current: " + currentVersion
                        + ", Recommended: 7.50.0 or higher");
            }

            // Check curl features
            Map<String, Boolean> curlFeatures = curlManager.getSupportedFeatures();
            details.put("curl_features", curlFeatures);

            if (!curlFeatures.getOrDefault("https", false)) {
                warnings.add("curl does not support HTTPS - secure downloads may fail");
            }
            if (!curlFeatures.getOrDefault("ssl", false)) {
                warnings.add("curl does not support SSL/TLS");
            }
            if (!curlFeatures.getOrDefault("proxy", false)) {
                warnings.add("curl does not support proxies");
            }

            details.put("curl_version", currentVersion);
            details.put("curl_path", curlManager.getToolPath());
        }

        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, errors, warnings, details);
    }

    /**
     * Validates video downloading capabilities (yt-dlp).
     *
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateVideoDownloading() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();

        YtDlpToolManager ytDlpManager = toolManagerFactory.getYtDlpManager();
        if (ytDlpManager == null) {
            warnings.add("yt-dlp tool manager not available");
            return new ValidationResult(true, errors, warnings, details);
        }

        if (!ytDlpManager.isAvailable()) {
            warnings.add("yt-dlp is not available - video downloading will not work");
        } else {
            String currentVersion = ytDlpManager.getVersion();
            if (currentVersion == null || !ytDlpManager.checkMinimumVersion("2023.01.01")) {
                warnings.add("yt-dlp version is old. Current: " + currentVersion
                        + ", Recommended: 2023.01.01 or higher");
            }

            details.put("yt_dlp_version", currentVersion);
            details.put("yt_dlp_path", ytDlpManager.getToolPath());
            details.put("yt_dlp_features", ytDlpManager.getSupportedFeatures());
        }

        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, errors, warnings, details);
    }

    /**
     * Validates website scraping capabilities (httrack).
     *
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateWebsiteScraping() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();

        HttrackToolManager httrackManager = toolManagerFactory.getHttrackManager();
        if (httrackManager == null) {
            warnings.add("HTTrack tool manager not available");
            return new ValidationResult(true, errors, warnings, details);
        }

        if (!httrackManager.isAvailable()) {
            warnings.add("httrack is not available - website scraping will not work");
        } else {
            String version = httrackManager.getVersion();
            details.put("httrack_version", version);
            details.put("httrack_path", httrackManager.getToolPath());
            details.put("httrack_features", httrackManager.getSupportedFeatures());
        }

        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, errors, warnings, details);
    }

    /**
     * Validates proxy support capabilities.
     *
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateProxySupport() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();

        ProxychainsToolManager proxychainsManager = toolManagerFactory.getProxychainsManager();
        if (proxychainsManager == null) {
            warnings.add("Proxychains tool manager not available");
            return new ValidationResult(true, errors, warnings, details);
        }

        if (!proxychainsManager.isAvailable()) {
            warnings.add("proxychains is not available - proxy support will be limited");
        } else {
            String version = proxychainsManager.getVersion();
            details.put("proxychains_version", version);
            details.put("proxychains_path", proxychainsManager.getToolPath());
            details.put("proxychains_features", proxychainsManager.getSupportedFeatures());
        }

        // Also check Tor if needed for advanced proxy support
        TorToolManager torManager = toolManagerFactory.getTorManager();
        if (torManager != null && torManager.isAvailable()) {
            details.put("tor_version", torManager.getVersion());
            details.put("tor_path", torManager.getToolPath());
            details.put("tor_features", torManager.getSupportedFeatures());
        } else {
            warnings.add("tor is not available - advanced anonymity features will not work");
        }

        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, errors, warnings, details);
    }

    /**
     * Validates aria2 protocol support.
     *
     * @param requiredProtocols List of required protocols
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateAria2Protocols(List<String> requiredProtocols) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();

        Aria2ToolManager aria2Manager = toolManagerFactory.getAria2Manager();
        if (aria2Manager == null || !aria2Manager.isAvailable()) {
            errors.add("aria2c is not available");
            return new ValidationResult(false, errors, warnings, details);
        }

        Map<String, Boolean> features = aria2Manager.getSupportedFeatures();
        details.put("aria2_features", features);

        for (String protocol : requiredProtocols) {
            if (!features.getOrDefault(protocol.toLowerCase(), false)) {
                errors.add("aria2c does not support required protocol: " + protocol);
            }
        }

        boolean valid = aria2Manager.checkFeatureSupport(requiredProtocols.toArray(new String[0]));
        return new ValidationResult(valid, errors, warnings, details);
    }

    /**
     * Validates aria2 protocol support with default HTTP/HTTPS requirements.
     *
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateAria2Protocols() {
        return validateAria2Protocols(Arrays.asList("http", "https"));
    }

    /**
     * Validates curl protocol support.
     *
     * @param requiredProtocols List of required protocols
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateCurlProtocols(List<String> requiredProtocols) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();

        CurlToolManager curlManager = toolManagerFactory.getCurlManager();
        if (curlManager == null || !curlManager.isAvailable()) {
            errors.add("curl is not available");
            return new ValidationResult(false, errors, warnings, details);
        }

        Map<String, Boolean> features = curlManager.getSupportedFeatures();
        details.put("curl_features", features);

        for (String protocol : requiredProtocols) {
            if (!features.getOrDefault(protocol.toLowerCase(), false)) {
                errors.add("curl does not support required protocol: " + protocol);
            }
        }

        boolean valid = curlManager.checkFeatureSupport(requiredProtocols.toArray(new String[0]));
        return new ValidationResult(valid, errors, warnings, details);
    }

    /**
     * Validates curl protocol support with default HTTP/HTTPS requirements.
     *
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateCurlProtocols() {
        return validateCurlProtocols(Arrays.asList("http", "https"));
    }

    /**
     * Validates BitTorrent download support.
     *
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateBitTorrentSupport() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();

        Aria2ToolManager aria2Manager = toolManagerFactory.getAria2Manager();
        if (aria2Manager == null || !aria2Manager.isAvailable()) {
            errors.add("aria2c is not available - BitTorrent downloads require aria2c");
        } else {
            Map<String, Boolean> features = aria2Manager.getSupportedFeatures();
            details.put("aria2_features", features);

            if (!features.getOrDefault("bittorrent", false)) {
                errors.add("aria2c does not support BitTorrent protocol");
            }
            if (!features.getOrDefault("dht", false)) {
                warnings.add("aria2c does not support DHT - some torrents may not work optimally");
            }
            if (!features.getOrDefault("peer-exchange", false)) {
                warnings.add("aria2c does not support peer exchange");
            }
        }

        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, errors, warnings, details);
    }

    /**
     * Validates secure download capabilities (HTTPS/SSL support).
     *
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateSecureDownloads() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();

        // Check aria2c HTTPS support
        Aria2ToolManager aria2Manager = toolManagerFactory.getAria2Manager();
        if (aria2Manager != null && aria2Manager.isAvailable()) {
            Map<String, Boolean> aria2Features = aria2Manager.getSupportedFeatures();
            details.put("aria2_features", aria2Features);

            if (!aria2Features.getOrDefault("https", false)) {
                warnings.add("aria2c does not support HTTPS");
            }
        }

        // Check curl SSL/HTTPS support
        CurlToolManager curlManager = toolManagerFactory.getCurlManager();
        if (curlManager != null && curlManager.isAvailable()) {
            Map<String, Boolean> curlFeatures = curlManager.getSupportedFeatures();
            details.put("curl_features", curlFeatures);

            if (!curlFeatures.getOrDefault("https", false)) {
                warnings.add("curl does not support HTTPS");
            }
            if (!curlFeatures.getOrDefault("ssl", false)) {
                warnings.add("curl does not support SSL/TLS");
            }
        }

        // If neither tool supports HTTPS, that's a problem
        boolean hasHttpsSupport = false;
        if (aria2Manager != null && aria2Manager.isAvailable()) {
            hasHttpsSupport |= aria2Manager.checkFeatureSupport("https");
        }
        if (curlManager != null && curlManager.isAvailable()) {
            hasHttpsSupport |= curlManager.checkFeatureSupport("https");
        }

        if (!hasHttpsSupport) {
            errors.add("No available tool supports HTTPS - secure downloads will not work");
        }

        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, errors, warnings, details);
    }

    /**
     * Validates all dependencies and provides a comprehensive report.
     *
     * @return ValidationResult with detailed information about all checks
     */
    public ValidationResult validateAll() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();

        // Run all validation checks
        ValidationResult coreResult = validateCoreRequirements();
        ValidationResult curlResult = validateCurlFallback();
        ValidationResult videoResult = validateVideoDownloading();
        ValidationResult scrapingResult = validateWebsiteScraping();
        ValidationResult proxyResult = validateProxySupport();
        ValidationResult btResult = validateBitTorrentSupport();
        ValidationResult secureResult = validateSecureDownloads();

        // Aggregate results
        errors.addAll(coreResult.getErrors());
        errors.addAll(curlResult.getErrors());
        errors.addAll(videoResult.getErrors());
        errors.addAll(scrapingResult.getErrors());
        errors.addAll(proxyResult.getErrors());
        errors.addAll(btResult.getErrors());
        errors.addAll(secureResult.getErrors());

        warnings.addAll(coreResult.getWarnings());
        warnings.addAll(curlResult.getWarnings());
        warnings.addAll(videoResult.getWarnings());
        warnings.addAll(scrapingResult.getWarnings());
        warnings.addAll(proxyResult.getWarnings());
        warnings.addAll(btResult.getWarnings());
        warnings.addAll(secureResult.getWarnings());

        // Add detailed results
        details.put("core", coreResult.getDetails());
        details.put("curl", curlResult.getDetails());
        details.put("video", videoResult.getDetails());
        details.put("scraping", scrapingResult.getDetails());
        details.put("proxy", proxyResult.getDetails());
        details.put("bittorrent", btResult.getDetails());
        details.put("secure", secureResult.getDetails());

        // Add comprehensive status report
        details.put("tool_status_report", toolManagerFactory.getStatusReport());

        boolean valid = errors.isEmpty();
        return new ValidationResult(valid, errors, warnings, details);
    }

    /**
     * Checks if minimum requirements are met for basic functionality.
     *
     * @return true if minimum requirements are met, false otherwise
     */
    public boolean meetsMinimumRequirements() {
        ValidationResult coreResult = validateCoreRequirements();
        return coreResult.isValid();
    }

    /**
     * Gets a summary report of all tools and their status.
     *
     * @param validationResult The validation result to summarize
     * @return A formatted summary string
     */
    public static String getSummary(ValidationResult validationResult) {
        StringBuilder summary = new StringBuilder();
        summary.append("Dependency Validation Summary:\n");
        summary.append("Valid: ").append(validationResult.isValid()).append("\n");

        if (validationResult.hasErrors()) {
            summary.append("Errors (").append(validationResult.getErrors().size()).append("):\n");
            for (String error : validationResult.getErrors()) {
                summary.append("  - ").append(error).append("\n");
            }
        }

        if (validationResult.hasWarnings()) {
            summary.append("Warnings (").append(validationResult.getWarnings().size()).append("):\n");
            for (String warning : validationResult.getWarnings()) {
                summary.append("  - ").append(warning).append("\n");
            }
        }

        return summary.toString();
    }
}
