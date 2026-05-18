package org.curl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.manager.tools.ToolManagerFactory;
import org.curl.CurlToolManager;
import org.manager.GlobalSettings;
import org.manager.ApplicationContext;

/**
 * Utility class for working with the curl command-line tool.
 */
public class CurlUtils {

    private static final Pattern VERSION_PATTERN = Pattern.compile("curl (\\d+\\.\\d+\\.\\d+)");

    private CurlUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Gets the curl path using the ToolManagerFactory.
     *
     * @return The curl executable path
     */
    private static String getCurlPath() {
        try {
            ToolManagerFactory factory = ApplicationContext.getToolManagerFactory();
            if (factory != null) {
                CurlToolManager curlManager = factory.getCurlManager();
                if (curlManager != null) {
                    return curlManager.getToolPath();
                }
            }

            // Final fallback - try system curl
            return "curl";
        } catch (Exception e) {
            // Final fallback - try system curl
            return "curl";
        }
    }

    /**
     * Checks if curl is installed and available on the system using the tool manager.
     *
     * @return true if curl is available, false otherwise
     */
    public static boolean isCurlAvailable() {
        try {
            String curlPath = getCurlPath();
            return isCurlAvailable(curlPath);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Checks if curl is available at the specified path.
     *
     * @param curlPath The path to the curl executable
     * @return true if curl is available at the specified path, false otherwise
     */
    public static boolean isCurlAvailable(String curlPath) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(curlPath, "--version");
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Gets the installed curl version using the default dependency manager.
     *
     * @return The curl version string, or null if curl is not available
     */
    public static String getCurlVersion() {
        try {
            String curlPath = getCurlPath();
            return getCurlVersion(curlPath);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Gets the curl version for the specified curl executable.
     *
     * @param curlPath The path to the curl executable
     * @return The curl version string, or null if curl is not available
     */
    public static String getCurlVersion(String curlPath) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(curlPath, "--version");
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    Matcher matcher = VERSION_PATTERN.matcher(line);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }

            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Checks if the installed curl version supports the specified feature using
     * the default dependency manager.
     *
     * @param feature The feature to check (e.g., "http2")
     * @return true if the feature is supported, false otherwise
     */
    public static boolean isFeatureSupported(String feature) {
        try {
            String curlPath = getCurlPath();
            return isFeatureSupported(curlPath, feature);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Checks if the curl version at the specified path supports the specified
     * feature.
     *
     * @param curlPath The path to the curl executable
     * @param feature The feature to check (e.g., "http2")
     * @return true if the feature is supported, false otherwise
     */
    public static boolean isFeatureSupported(String curlPath, String feature) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(curlPath, "--version");
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(feature)) {
                        return true;
                    }
                }
            }

            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Gets a list of supported protocols in the installed curl version using
     * the default dependency manager.
     *
     * @return List of supported protocols, or an empty list if curl is not
     * available
     */
    public static List<String> getSupportedProtocols() {
        try {
            String curlPath = getCurlPath();
            return getSupportedProtocols(curlPath);
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Gets a list of supported protocols for the curl version at the specified
     * path.
     *
     * @param curlPath The path to the curl executable
     * @return List of supported protocols, or an empty list if curl is not
     * available
     */
    public static List<String> getSupportedProtocols(String curlPath) {
        List<String> protocols = new ArrayList<>();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(curlPath, "--version");
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Protocols:")) {
                        String protocolsString = line.substring("Protocols:".length()).trim();
                        String[] protocolArray = protocolsString.split("\\s+");
                        for (String protocol : protocolArray) {
                            protocols.add(protocol);
                        }
                        break;
                    }
                }
            }
        } catch (IOException e) {
            // Return empty list on error
        }

        return protocols;
    }

    /**
     * Builds a curl command with appropriate options for a download using the
     * default dependency manager.
     *
     * @param url The URL to download
     * @param outputPath The output file path
     * @param useProxy Whether to use a proxy
     * @param proxyAddress The proxy address (e.g., "socks5h://127.0.0.1:9050")
     * @return List of command arguments
     */
    @Deprecated(forRemoval = true)
    public static List<String> buildCurlCommand(String url, String outputPath, boolean useProxy, String proxyAddress) {
        String curlPath = getCurlPath();
        return buildCurlCommand(curlPath, url, outputPath, useProxy, proxyAddress);
    }

    /**
     * Builds a curl command with appropriate options for a download using the
     * specified curl path.
     *
     * @param curlPath The path to the curl executable
     * @param url The URL to download
     * @param outputPath The output file path
     * @param useProxy Whether to use a proxy
     * @param proxyAddress The proxy address (e.g., "socks5h://127.0.0.1:9050")
     * @return List of command arguments
     */
    @Deprecated(forRemoval = true)
    public static List<String> buildCurlCommand(String curlPath, String url, String outputPath, boolean useProxy, String proxyAddress) {
        List<String> command = new ArrayList<>();

        // Add curl executable
        command.add(curlPath);

        // Basic options
        command.add("-L"); // Follow redirects
        command.add("-C");
        command.add("-"); // Resume downloads
        command.add("-#"); // Show progress as hash marks
        command.add("--create-dirs"); // Create directories in output path if needed

        // Add proxy if specified
        if (useProxy && proxyAddress != null && !proxyAddress.isEmpty()) {
            command.add("-x");
            command.add(proxyAddress);
        }

        // Add output file
        command.add("-o");
        command.add(outputPath);

        // Add connection options
        command.add("--connect-timeout");
        command.add("30");

        // Add retry options
        command.add("--retry");
        command.add("3");

        // Add the URL
        command.add(url);

        return command;
    }

    /**
     * Executes a HEAD request using curl to get headers without downloading the
     * content using the default dependency manager.
     *
     * @param url The URL to check
     * @return The headers as a list of strings, or null if the request failed
     */
    public static List<String> getHeaders(String url) {
        try {
            String curlPath = getCurlPath();
            return getHeaders(curlPath, url);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Executes a HEAD request using curl to get headers without downloading the
     * content using the specified curl path.
     *
     * @param curlPath The path to the curl executable
     * @param url The URL to check
     * @return The headers as a list of strings, or null if the request failed
     */
    public static List<String> getHeaders(String curlPath, String url) {
        List<String> headers = new ArrayList<>();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    curlPath,
                    "-I", // Only fetch headers
                    "-L", // Follow redirects
                    "-s", // Silent mode
                    url);
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    headers.add(line);
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0 ? headers : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /**
     * Gets the content length of a URL using a HEAD request with the default
     * dependency manager.
     *
     * @param url The URL to check
     * @return The content length in bytes, or -1 if unknown
     */
    public static long getContentLength(String url) {
        List<String> headers = getHeaders(url);
        if (headers != null) {
            for (String header : headers) {
                if (header.toLowerCase().startsWith("content-length:")) {
                    try {
                        String lengthStr = header.substring("content-length:".length()).trim();
                        return Long.parseLong(lengthStr);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Gets the content length of a URL using a HEAD request with the specified
     * curl path.
     *
     * @param curlPath The path to the curl executable
     * @param url The URL to check
     * @return The content length in bytes, or -1 if unknown
     */
    public static long getContentLength(String curlPath, String url) {
        List<String> headers = getHeaders(curlPath, url);
        if (headers != null) {
            for (String header : headers) {
                if (header.toLowerCase().startsWith("content-length:")) {
                    try {
                        String lengthStr = header.substring("content-length:".length()).trim();
                        return Long.parseLong(lengthStr);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Gets the file name from a URL using the Content-Disposition header with
     * the default dependency manager.
     *
     * @param url The URL to check
     * @return The suggested filename, or null if not available
     */
    public static String getFilenameFromContentDisposition(String url) {
        List<String> headers = getHeaders(url);
        if (headers != null) {
            Pattern filenamePattern = Pattern.compile("filename=[\"']?([^\"']+)[\"']?");

            for (String header : headers) {
                if (header.toLowerCase().startsWith("content-disposition:")) {
                    Matcher matcher = filenamePattern.matcher(header);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Gets the file name from a URL using the Content-Disposition header with
     * the specified curl path.
     *
     * @param curlPath The path to the curl executable
     * @param url The URL to check
     * @return The suggested filename, or null if not available
     */
    public static String getFilenameFromContentDisposition(String curlPath, String url) {
        List<String> headers = getHeaders(curlPath, url);
        if (headers != null) {
            Pattern filenamePattern = Pattern.compile("filename=[\"']?([^\"']+)[\"']?");

            for (String header : headers) {
                if (header.toLowerCase().startsWith("content-disposition:")) {
                    Matcher matcher = filenamePattern.matcher(header);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
        }
        return null;
    }
}
