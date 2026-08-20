package org.ytdlp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.manager.ApplicationContext;
import org.manager.tools.ToolManagerFactory;

/**
 * Java client for yt-dlp command-line tool. Provides methods to extract video
 * information, download videos, and monitor progress.
 */
public class YtDlpClient {

    private static final Logger LOGGER = Logger.getLogger(YtDlpClient.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Progress patterns for parsing yt-dlp output
    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "\\[download\\]\\s+(\\d+\\.?\\d*)%\\s+of\\s+~?([\\d\\.]+)([KMG]?i?B).*?at\\s+([\\d\\.]+)([KMG]?i?B)/s");
    private static final Pattern FILE_SIZE_PATTERN = Pattern.compile(
            "\\[info\\].*?filesize:\\s*([\\d\\.]+)\\s*([KMG]?i?B)");
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "\\[info\\]\\s+(.+?):\\s*Downloading\\s+webpage");

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final String ytDlpPath;
    private final ExecutorService executor;
    private final Map<String, Process> activeProcesses;

    /**
     * Gets the ToolManagerFactory instance using ApplicationContext.
     */
    private static ToolManagerFactory getToolManagerFactory() {
        return ApplicationContext.getToolManagerFactory();
    }

    /**
     * Creates a new YtDlpClient with default yt-dlp path from
     * ToolManagerFactory.
     */
    public YtDlpClient() {
        this(getYtDlpPath());
    }

    /**
     * Gets the yt-dlp path using the ToolManagerFactory.
     */
    private static String getYtDlpPath() {
        try {
            ToolManagerFactory factory = getToolManagerFactory();
            if (factory != null) {
                YtDlpToolManager ytDlpManager = factory.getYtDlpManager();
                if (ytDlpManager != null) {
                    return ytDlpManager.getToolPath();
                }
            }

            // Final fallback - try system yt-dlp
            return "yt-dlp";
        } catch (Exception e) {
            // Final fallback - try system yt-dlp
            return "yt-dlp";
        }
    }

    /**
     * Creates a new YtDlpClient with specified yt-dlp path.
     *
     * @param ytDlpPath Path to the yt-dlp executable
     */
    public YtDlpClient(String ytDlpPath) {
        this.ytDlpPath = ytDlpPath;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "YtDlpClient-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        this.activeProcesses = new ConcurrentHashMap<>();
    }

    /**
     * Video information extracted from yt-dlp.
     */
    public static class VideoInfo {

        private String id;
        private String title;
        private String description;
        private String uploader;
        private String uploadDate;
        private long duration;
        private long filesize;
        private String format;
        private String url;
        private String thumbnail;
        private List<VideoFormat> formats;
        private List<Subtitle> subtitles;

        // Getters and setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getUploader() {
            return uploader;
        }

        public void setUploader(String uploader) {
            this.uploader = uploader;
        }

        public String getUploadDate() {
            return uploadDate;
        }

        public void setUploadDate(String uploadDate) {
            this.uploadDate = uploadDate;
        }

        public long getDuration() {
            return duration;
        }

        public void setDuration(long duration) {
            this.duration = duration;
        }

        public long getFilesize() {
            return filesize;
        }

        public void setFilesize(long filesize) {
            this.filesize = filesize;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getThumbnail() {
            return thumbnail;
        }

        public void setThumbnail(String thumbnail) {
            this.thumbnail = thumbnail;
        }

        public List<VideoFormat> getFormats() {
            return formats;
        }

        public void setFormats(List<VideoFormat> formats) {
            this.formats = formats;
        }

        public List<Subtitle> getSubtitles() {
            return subtitles;
        }

        public void setSubtitles(List<Subtitle> subtitles) {
            this.subtitles = subtitles;
        }
    }

    /**
     * Represents an available video format.
     */
    public static class VideoFormat {

        private String formatId;
        private String ext;
        private long filesize;
        private String resolution;
        private int fps;
        private String acodec;
        private String vcodec;
        private int abr;
        private int vbr;

        // Getters and setters
        public String getFormatId() {
            return formatId;
        }

        public void setFormatId(String formatId) {
            this.formatId = formatId;
        }

        public String getExt() {
            return ext;
        }

        public void setExt(String ext) {
            this.ext = ext;
        }

        public long getFilesize() {
            return filesize;
        }

        public void setFilesize(long filesize) {
            this.filesize = filesize;
        }

        public String getResolution() {
            return resolution;
        }

        public void setResolution(String resolution) {
            this.resolution = resolution;
        }

        public int getFps() {
            return fps;
        }

        public void setFps(int fps) {
            this.fps = fps;
        }

        public String getAcodec() {
            return acodec;
        }

        public void setAcodec(String acodec) {
            this.acodec = acodec;
        }

        public String getVcodec() {
            return vcodec;
        }

        public void setVcodec(String vcodec) {
            this.vcodec = vcodec;
        }

        public int getAbr() {
            return abr;
        }

        public void setAbr(int abr) {
            this.abr = abr;
        }

        public int getVbr() {
            return vbr;
        }

        public void setVbr(int vbr) {
            this.vbr = vbr;
        }
    }

    /**
     * Represents available subtitles.
     */
    public static class Subtitle {

        private String language;
        private String ext;
        private String url;

        // Getters and setters
        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public String getExt() {
            return ext;
        }

        public void setExt(String ext) {
            this.ext = ext;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    /**
     * Progress callback interface for download monitoring.
     */
    public interface ProgressCallback {

        void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed);

        void onStart(String filename);

        void onComplete(String filename);

        void onError(String error);
    }

    /**
     * Checks if yt-dlp is available and functional.
     *
     * @return true if yt-dlp is available, false otherwise
     */
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ytDlpPath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "yt-dlp not available", e);
            return false;
        }
    }

    /**
     * Gets the version of yt-dlp.
     *
     * @return The version string, or null if unavailable
     */
    public String getVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ytDlpPath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String version = reader.readLine();
                int exitCode = process.waitFor();
                return exitCode == 0 ? version : null;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get yt-dlp version", e);
            return null;
        }
    }

    /**
     * Checks if aria2c is available on the system.
     *
     * @return true if aria2c is available, false otherwise
     */
    public boolean isAria2cAvailable() {
        return isAria2cAvailable("aria2c");
    }

    /**
     * Checks if aria2c is available at the specified path.
     *
     * @param aria2cPath Path to aria2c executable
     * @return true if aria2c is available, false otherwise
     */
    public boolean isAria2cAvailable(String aria2cPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(aria2cPath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "aria2c not available at path: " + aria2cPath, e);
            return false;
        }
    }

    /**
     * Gets the version of aria2c.
     *
     * @return The aria2c version string, or null if unavailable
     */
    public String getAria2cVersion() {
        return getAria2cVersion("aria2c");
    }

    /**
     * Gets the version of aria2c at the specified path.
     *
     * @param aria2cPath Path to aria2c executable
     * @return The aria2c version string, or null if unavailable
     */
    public String getAria2cVersion(String aria2cPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(aria2cPath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String firstLine = reader.readLine();
                int exitCode = process.waitFor();
                if (exitCode == 0 && firstLine != null) {
                    // aria2 version output format: "aria2 version 1.36.0"
                    return firstLine;
                }
                return null;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get aria2c version", e);
            return null;
        }
    }

    /**
     * Extracts video information without downloading.
     *
     * @param url The video URL
     * @return CompletableFuture containing video information
     */
    public CompletableFuture<VideoInfo> extractInfo(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> command = new ArrayList<>();
                command.add(ytDlpPath);
                command.add("--dump-json");
                command.add("--no-download");
                command.add(url);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                StringBuilder output = new StringBuilder();
                String jsonLine = null;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        // yt-dlp prints WARNING/ERROR lines ahead of the JSON
                        // payload; the first JSON line carries the video info
                        if (jsonLine == null && line.trim().startsWith("{")) {
                            jsonLine = line;
                        }
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("yt-dlp failed with exit code: " + exitCode + "\nOutput: " + output);
                }
                if (jsonLine == null) {
                    throw new RuntimeException("yt-dlp produced no JSON output\nOutput: " + output);
                }

                // Parse JSON output
                JsonNode jsonNode = OBJECT_MAPPER.readTree(jsonLine);
                return parseVideoInfo(jsonNode);

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to extract video info", e);
                throw new RuntimeException("Failed to extract video info: " + e.getMessage(), e);
            }
        }, executor);
    }

    /**
     * Lists available formats for a video.
     *
     * @param url The video URL
     * @return CompletableFuture containing list of available formats
     */
    public CompletableFuture<List<VideoFormat>> listFormats(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> command = new ArrayList<>();
                command.add(ytDlpPath);
                command.add("-F");
                command.add("--dump-json");
                command.add(url);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                List<String> lines = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("yt-dlp failed with exit code: " + exitCode);
                }

                // Parse formats from JSON or text output
                List<VideoFormat> formats = new ArrayList<>();
                for (String line : lines) {
                    if (line.startsWith("{") && line.endsWith("}")) {
                        try {
                            JsonNode jsonNode = OBJECT_MAPPER.readTree(line);
                            if (jsonNode.has("formats")) {
                                JsonNode formatsNode = jsonNode.get("formats");
                                if (formatsNode.isArray()) {
                                    for (JsonNode formatNode : formatsNode) {
                                        formats.add(parseVideoFormat(formatNode));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Skip malformed JSON
                        }
                    }
                }

                return formats;

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to list formats", e);
                throw new RuntimeException("Failed to list formats: " + e.getMessage(), e);
            }
        }, executor);
    }

    /**
     * Downloads a video with the specified settings.
     *
     * @param url        The video URL
     * @param settings   The download settings
     * @param outputPath The output directory
     * @param callback   Progress callback (optional)
     * @return CompletableFuture that completes when download finishes
     */
    public CompletableFuture<String> download(String url, YtDlpSettings settings, Path outputPath,
            ProgressCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            String processId = "ytdlp-" + System.currentTimeMillis();
            try {
                // Build command
                List<String> command = buildDownloadCommand(url, settings, outputPath);

                // Create output directory if it doesn't exist
                if (outputPath != null) {
                    Files.createDirectories(outputPath);
                }

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                if (outputPath != null) {
                    pb.directory(outputPath.toFile());
                }

                LOGGER.info("Starting yt-dlp download: " + String.join(" ", command));
                Process process = pb.start();
                activeProcesses.put(processId, process);

                // Monitor progress
                String filename = null;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        LOGGER.fine("yt-dlp output: " + line);

                        // Extract filename if not yet known
                        if (filename == null) {
                            filename = extractFilename(line);
                        }

                        // Parse progress
                        if (callback != null) {
                            parseProgress(line, callback);
                        }

                        // Check for errors
                        if (line.contains("ERROR:") && callback != null) {
                            callback.onError(line);
                        }
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    if (callback != null && filename != null) {
                        callback.onComplete(filename);
                    }
                    LOGGER.info("yt-dlp download completed successfully");
                    return filename != null ? filename : "download-complete";
                } else {
                    String error = "yt-dlp failed with exit code: " + exitCode;
                    if (callback != null) {
                        callback.onError(error);
                    }
                    throw new RuntimeException(error);
                }

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Download failed", e);
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
                throw new RuntimeException("Download failed: " + e.getMessage(), e);
            } finally {
                activeProcesses.remove(processId);
            }
        }, executor);
    }

    /**
     * Cancels an active download process.
     *
     * @param processId The process ID to cancel
     * @return true if the process was found and canceled, false otherwise
     */
    public boolean cancelDownload(String processId) {
        Process process = activeProcesses.remove(processId);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        }
        return false;
    }

    /**
     * Shuts down the client and cleanup resources.
     */
    public void shutdown() {
        // Cancel all active processes
        activeProcesses.values().forEach(process -> {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        });
        activeProcesses.clear();

        // Shutdown executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Builds the yt-dlp command with settings.
     */
    private List<String> buildDownloadCommand(String url, YtDlpSettings settings, Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);

        // Output template
        String outputTemplate = "%(title)s-%(id)s.%(ext)s";
        command.add("-o");
        command.add(outputTemplate);

        // Progress and logging
        command.add("--newline");
        if (settings.isVerboseOutput()) {
            command.add("--verbose");
        }

        // Format selection
        if (settings.getFormat() != null && !settings.getFormat().isEmpty()) {
            command.add("-f");
            command.add(settings.getFormat());
        }

        // Audio extraction
        if (settings.isExtractAudio()) {
            command.add("-x");
            if (settings.getAudioFormat() != null) {
                command.add("--audio-format");
                command.add(settings.getAudioFormat());
            }
            if (settings.getAudioQuality() != null) {
                command.add("--audio-quality");
                command.add(settings.getAudioQuality());
            }
        }

        // Metadata and thumbnails
        if (settings.isEmbedThumbnail()) {
            command.add("--embed-thumbnail");
        }
        if (settings.isEmbedMetadata()) {
            command.add("--embed-metadata");
        }

        // Subtitles
        if (settings.isWriteSubtitles()) {
            command.add("--write-sub");
        }
        if (settings.isWriteAutoSubs()) {
            command.add("--write-auto-sub");
        }
        if (settings.isEmbedSubs()) {
            command.add("--embed-subs");
        }
        if (!settings.getSubtitleLanguages().isEmpty()) {
            command.add("--sub-langs");
            command.add(String.join(",", settings.getSubtitleLanguages()));
        }

        // Network settings
        command.add("--fragment-retries");
        command.add(String.valueOf(settings.getFragmentRetries()));

        if (settings.isLimitRate() && settings.getRateLimit() > 0) {
            command.add("--limit-rate");
            command.add(settings.getRateLimit() + "K");
        }

        // Proxy settings
        if (settings.isUseProxy() && settings.getProxyAddress() != null) {
            command.add("--proxy");
            command.add(settings.getProxyAddress());
        }

        // Cookie file
        if (settings.getCookieFile() != null) {
            command.add("--cookies");
            command.add(settings.getCookieFile());
        }

        // Playlist settings
        if (settings.isNoPlaylist()) {
            command.add("--no-playlist");
        }

        // Error handling
        if (settings.isIgnoreErrors()) {
            command.add("--ignore-errors");
        }
        if (settings.isSkipUnavailableFragments()) {
            command.add("--skip-unavailable-fragments");
        }

        // Geo bypass
        if (settings.isGeoBypass()) {
            command.add("--geo-bypass");
        }

        // Add aria2c external downloader if configured
        if (settings.getOption("use-aria2c") != null && "true".equals(settings.getOption("use-aria2c"))) {
            command.add("--external-downloader");
            command.add("aria2c");

            // Add aria2c specific arguments
            String aria2cArgs = settings.getOption("aria2c-args");
            if (aria2cArgs != null && !aria2cArgs.isEmpty()) {
                command.add("--external-downloader-args");
                command.add(aria2cArgs);
            } else {
                // Default aria2c arguments for optimal performance
                command.add("--external-downloader-args");
                command.add("-x 16 -s 16 -k 1M");
            }
        }

        // Add any additional options
        for (Map.Entry<String, String> entry : settings.getAdditionalOptions().entrySet()) {
            // Skip aria2c options as they're handled above
            if (!entry.getKey().equals("use-aria2c") && !entry.getKey().equals("aria2c-args")) {
                command.add("--" + entry.getKey());
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    command.add(entry.getValue());
                }
            }
        }

        command.add(url);
        return command;
    }

    /**
     * Parses video information from JSON node.
     */
    private VideoInfo parseVideoInfo(JsonNode node) {
        VideoInfo info = new VideoInfo();

        if (node.has("id")) {
            info.setId(node.get("id").asText());
        }
        if (node.has("title")) {
            info.setTitle(node.get("title").asText());
        }
        if (node.has("description")) {
            info.setDescription(node.get("description").asText());
        }
        if (node.has("uploader")) {
            info.setUploader(node.get("uploader").asText());
        }
        if (node.has("upload_date")) {
            info.setUploadDate(node.get("upload_date").asText());
        }
        if (node.has("duration")) {
            info.setDuration(node.get("duration").asLong());
        }
        if (node.has("filesize")) {
            info.setFilesize(node.get("filesize").asLong());
        }
        if (node.has("format")) {
            info.setFormat(node.get("format").asText());
        }
        if (node.has("url")) {
            info.setUrl(node.get("url").asText());
        }
        if (node.has("thumbnail")) {
            info.setThumbnail(node.get("thumbnail").asText());
        }

        // Parse formats
        if (node.has("formats") && node.get("formats").isArray()) {
            List<VideoFormat> formats = new ArrayList<>();
            for (JsonNode formatNode : node.get("formats")) {
                formats.add(parseVideoFormat(formatNode));
            }
            info.setFormats(formats);
        }

        return info;
    }

    /**
     * Parses video format from JSON node.
     */
    private VideoFormat parseVideoFormat(JsonNode node) {
        VideoFormat format = new VideoFormat();

        if (node.has("format_id")) {
            format.setFormatId(node.get("format_id").asText());
        }
        if (node.has("ext")) {
            format.setExt(node.get("ext").asText());
        }
        if (node.has("filesize")) {
            format.setFilesize(node.get("filesize").asLong());
        }
        if (node.has("resolution")) {
            format.setResolution(node.get("resolution").asText());
        }
        if (node.has("fps")) {
            format.setFps(node.get("fps").asInt());
        }
        if (node.has("acodec")) {
            format.setAcodec(node.get("acodec").asText());
        }
        if (node.has("vcodec")) {
            format.setVcodec(node.get("vcodec").asText());
        }
        if (node.has("abr")) {
            format.setAbr(node.get("abr").asInt());
        }
        if (node.has("vbr")) {
            format.setVbr(node.get("vbr").asInt());
        }

        return format;
    }

    /**
     * Extracts filename from yt-dlp output.
     */
    private String extractFilename(String line) {
        if (line.contains("[download] Destination:")) {
            String[] parts = line.split("Destination:");
            if (parts.length > 1) {
                return parts[1].trim();
            }
        }
        return null;
    }

    /**
     * Parses progress information from yt-dlp output.
     */
    private void parseProgress(String line, ProgressCallback callback) {
        Matcher matcher = PROGRESS_PATTERN.matcher(line);
        if (matcher.find()) {
            try {
                float percentage = Float.parseFloat(matcher.group(1));
                float size = Float.parseFloat(matcher.group(2));
                String sizeUnit = matcher.group(3);
                float speed = Float.parseFloat(matcher.group(4));
                String speedUnit = matcher.group(5);

                // Convert to bytes
                long totalBytes = convertToBytes(size, sizeUnit);
                long downloadedBytes = (long) (totalBytes * percentage / 100.0);
                float speedBps = convertToBytes(speed, speedUnit);

                callback.onProgress(percentage, downloadedBytes, totalBytes, speedBps);
            } catch (NumberFormatException e) {
                // Ignore parsing errors
            }
        }
    }

    /**
     * Converts size with unit to bytes.
     */
    private long convertToBytes(float size, String unit) {
        return switch (unit.toLowerCase()) {
            case "b", "" ->
                (long) size;
            case "kb", "kib" ->
                (long) (size * 1024);
            case "mb", "mib" ->
                (long) (size * 1024 * 1024);
            case "gb", "gib" ->
                (long) (size * 1024 * 1024 * 1024);
            default ->
                (long) size;
        };
    }
}
