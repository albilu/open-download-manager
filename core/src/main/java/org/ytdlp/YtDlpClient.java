package org.ytdlp;

import org.manager.tools.ExternalProcessRegistry;
import org.manager.tools.ToolPaths;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.manager.ApplicationContext;
import org.manager.tools.ToolManagerFactory;

/**
 * Java client for yt-dlp command-line tool. Provides methods to extract video
 * information, download videos, and monitor progress.
 */
public class YtDlpClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(YtDlpClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_METADATA_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final long METADATA_TIMEOUT_SECONDS = 60;

    // Progress patterns for parsing yt-dlp output
    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "\\[download\\]\\s+(\\d+\\.?\\d*)%\\s+of\\s+~?([\\d\\.]+)([KMG]?i?B).*?at\\s+([\\d\\.]+)([KMG]?i?B)/s");
    /** Speed in the template's human-readable prefix: "at 1.23MiB/s". */
    private static final Pattern SPEED_SUFFIX_PATTERN = Pattern.compile(
            "at\\s+([\\d\\.]+)\\s*([KMG]?i?B)/s");
    private static final Pattern FILE_SIZE_PATTERN = Pattern.compile(
            "\\[info\\].*?filesize:\\s*([\\d\\.]+)\\s*([KMG]?i?B)");
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "\\[info\\]\\s+(.+?):\\s*Downloading\\s+webpage");

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final String ytDlpPath;
    private final ExecutorService executor;
    private final org.manager.tools.ExternalProcessRegistry activeProcesses;

    /**
     * Creates a new YtDlpClient with default yt-dlp path from
     * ToolManagerFactory.
     */
    public YtDlpClient() {
        this(ToolPaths.ytDlp());
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
        this.activeProcesses = new org.manager.tools.ExternalProcessRegistry("yt-dlp");
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
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(ytDlpPath, "--version");
            pb.redirectErrorStream(true);
            process = pb.start();
            // Bounded wait: this probe runs on startup paths where a hung
            // binary must degrade to "unavailable", not block forever
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOGGER.warn("yt-dlp at path '" + ytDlpPath
                        + "' did not respond to --version within 10 seconds");
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            LOGGER.warn("yt-dlp not available", e);
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
            LOGGER.warn("Failed to get yt-dlp version", e);
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
            LOGGER.debug("aria2c not available at path: " + aria2cPath, e);
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
            LOGGER.warn("Failed to get aria2c version", e);
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
        return extractInfo(url, null);
    }

    /** Extracts metadata through the supplied proxy when non-blank. */
    public CompletableFuture<VideoInfo> extractInfo(String url, String proxyAddress) {
        String processId = "metadata-" + UUID.randomUUID();
        ExternalProcessRegistry.LaunchReservation launch = activeProcesses.reserve(processId);
        CompletableFuture<VideoInfo> result = CompletableFuture.supplyAsync(() -> {
            try {
                List<String> command = new ArrayList<>();
                command.add(ytDlpPath);
                command.add("--dump-json");
                command.add("--no-download");
                command.add("--no-playlist");
                addProxy(command, proxyAddress);
                command.add(url);

                String output = runMetadataCommand(command, processId, launch);
                String jsonLine = null;
                for (String line : output.lines().toList()) {
                    // yt-dlp prints WARNING/ERROR lines ahead of the JSON
                    // payload; the first JSON line carries the video info.
                    if (line.trim().startsWith("{")) {
                        jsonLine = line;
                        break;
                    }
                }
                if (jsonLine == null) {
                    throw new RuntimeException("yt-dlp produced no JSON metadata");
                }

                // Parse JSON output
                JsonNode jsonNode = OBJECT_MAPPER.readTree(jsonLine);
                return parseVideoInfo(jsonNode);

            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                LOGGER.error("Failed to extract video info", e);
                throw new RuntimeException("Failed to extract video info: " + e.getMessage(), e);
            }
        }, executor);
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                activeProcesses.terminate(processId, 1);
            }
        });
        return result;
    }

    /**
     * Lists available formats for a video.
     *
     * @param url The video URL
     * @return CompletableFuture containing list of available formats
     */
    public CompletableFuture<List<VideoFormat>> listFormats(String url) {
        return listFormats(url, null);
    }

    /** Lists formats through the supplied proxy when non-blank. */
    public CompletableFuture<List<VideoFormat>> listFormats(String url, String proxyAddress) {
        String processId = "formats-" + UUID.randomUUID();
        ExternalProcessRegistry.LaunchReservation launch = activeProcesses.reserve(processId);
        CompletableFuture<List<VideoFormat>> result = CompletableFuture.supplyAsync(() -> {
            try {
                List<String> command = new ArrayList<>();
                command.add(ytDlpPath);
                command.add("-F");
                command.add("--dump-json");
                command.add("--no-playlist");
                addProxy(command, proxyAddress);
                command.add(url);

                List<String> lines = runMetadataCommand(command, processId, launch).lines().toList();

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

            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                LOGGER.error("Failed to list formats", e);
                throw new RuntimeException("Failed to list formats: " + e.getMessage(), e);
            }
        }, executor);
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                activeProcesses.terminate(processId, 1);
            }
        });
        return result;
    }

    private static void addProxy(List<String> command, String proxyAddress) {
        if (proxyAddress != null && !proxyAddress.isBlank()) {
            command.add("--proxy");
            command.add(proxyAddress);
        }
    }

    /** Runs a short-lived metadata command with strict ownership, size and time bounds. */
    private String runMetadataCommand(List<String> command, String processId,
            ExternalProcessRegistry.LaunchReservation launch) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        ExternalProcessRegistry.Registration registration = null;
        try {
            registration = launch.start(pb);
            Process process = registration.process();
            StringBuilder output = new StringBuilder();
            java.util.concurrent.atomic.AtomicReference<Throwable> readFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread reader = new Thread(() -> {
                try (var input = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        total += count;
                        if (total > MAX_METADATA_OUTPUT_BYTES) {
                            throw new IOException("yt-dlp metadata exceeded the 4 MiB limit");
                        }
                        output.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                    }
                } catch (Throwable e) {
                    readFailure.set(e);
                    activeProcesses.terminate(processId, 1);
                }
            }, "yt-dlp-metadata-reader");
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                activeProcesses.terminate(processId, 1);
                throw new RuntimeException("yt-dlp metadata request timed out after 60 seconds");
            }
            reader.join(TimeUnit.SECONDS.toMillis(5));
            Throwable readerError = readFailure.get();
            if (readerError != null) {
                throw new RuntimeException(readerError.getMessage(), readerError);
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("yt-dlp metadata command failed with exit code "
                        + process.exitValue());
            }
            return output.toString();
        } finally {
            if (registration != null) {
                registration.unregister();
            } else {
                launch.unregister();
            }
        }
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
        return download(url, settings, outputPath, callback,
                "ytdlp-" + java.util.UUID.randomUUID());
    }

    /**
     * Downloads a video with a caller-controlled process key. The caller
     * (e.g. YtDlpDownloadTask) uses the same key for cancelDownload, so the
     * registry entry and the cancel key can never diverge.
     *
     * @param url        The video URL
     * @param settings   The download settings
     * @param outputPath The output directory
     * @param callback   Progress callback (optional)
     * @param processId  Registry key used for cancelDownload; must be unique
     *                   per started process
     * @return CompletableFuture that completes when download finishes
     */
    public CompletableFuture<String> download(String url, YtDlpSettings settings, Path outputPath,
            ProgressCallback callback, String processId) {
        ExternalProcessRegistry.LaunchReservation launch = activeProcesses.reserve(processId);
        return CompletableFuture.supplyAsync(() -> {
            org.manager.tools.ExternalProcessRegistry.Registration registration = null;
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

                LOGGER.info("Starting yt-dlp process " + processId);
                registration = launch.start(pb);
                Process process = registration.process();

                // Monitor progress
                String filename = null;
                boolean progressReported = false;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (!Thread.currentThread().isInterrupted()
                            && (line = reader.readLine()) != null) {
                        // Track both the early destination and the final
                        // after-move path (post-processing may change the
                        // extension). Publishing each distinct path also
                        // keeps cancellation cleanup authoritative.
                        String reportedFilename = extractFilename(line);
                        if (reportedFilename != null && !reportedFilename.equals(filename)) {
                            filename = reportedFilename;
                            // Publish the destination the moment yt-dlp
                            // reports it: a mid-flight cancel needs the
                            // output path for cleanup, and the completion
                            // callback alone would deliver it too late (or
                            // never, when the process gets killed).
                            if (callback != null) {
                                callback.onStart(filename);
                            }
                        }

                        // Parse progress
                        if (callback != null) {
                            progressReported |= parseProgress(line, callback);
                        }

                        // Check for errors
                        if (line.contains("ERROR:") && callback != null) {
                            callback.onError("yt-dlp reported an error");
                        }
                    }
                }

                int exitCode = process.waitFor();
                if (launch.isCancelled()) {
                    throw new CancellationException("yt-dlp download was cancelled");
                } else if (exitCode == 0) {
                    if (filename == null || filename.isBlank()) {
                        throw new IllegalStateException(
                                "yt-dlp exited successfully without reporting an output file");
                    }
                    if (callback != null) {
                        // Very small and already-present files can complete
                        // without yt-dlp emitting an intermediate progress
                        // line. Preserve the callback contract by publishing
                        // a terminal snapshot before completion.
                        if (!progressReported) {
                            long completedBytes = completedFileSize(filename, outputPath);
                            callback.onProgress(100.0f, completedBytes, completedBytes, 0.0f);
                        }
                        callback.onComplete(filename);
                    }
                    LOGGER.info("yt-dlp download completed successfully");
                    return filename;
                } else {
                    String error = "yt-dlp failed with exit code: " + exitCode;
                    if (callback != null) {
                        callback.onError(error);
                    }
                    throw new RuntimeException(error);
                }

            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                if (launch.isCancelled()) {
                    throw new CancellationException("yt-dlp download was cancelled");
                }
                LOGGER.error("Download failed", e);
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
                throw new RuntimeException("Download failed: " + e.getMessage(), e);
            } finally {
                if (registration != null) {
                    registration.unregister();
                } else {
                    launch.unregister();
                }
            }
        }, executor);
    }

    /**
     * Runs yt-dlp in subtitle-only mode using the same process registry,
     * cancellation key, executor, and network settings as normal downloads.
     */
    public CompletableFuture<Void> downloadSubtitles(String url, YtDlpSettings settings,
            Path outputDirectory, String processId) {
        ExternalProcessRegistry.LaunchReservation launch = activeProcesses.reserve(processId);
        return CompletableFuture.runAsync(() -> {
            ExternalProcessRegistry.Registration registration = null;
            try {
                if (outputDirectory != null) {
                    Files.createDirectories(outputDirectory);
                }
                ProcessBuilder builder = new ProcessBuilder(
                        buildSubtitleCommand(url, settings, outputDirectory))
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD);
                registration = launch.start(builder);
                int exitCode = registration.process().waitFor();
                if (launch.isCancelled()) {
                    throw new CancellationException(
                            "yt-dlp subtitle download was cancelled");
                }
                if (exitCode != 0) {
                    throw new IllegalStateException(
                            "yt-dlp subtitle download failed with exit code: " + exitCode);
                }
            } catch (CancellationException e) {
                throw e;
            } catch (InterruptedException e) {
                activeProcesses.terminate(processId, 5);
                Thread.currentThread().interrupt();
                throw new CancellationException(
                        "yt-dlp subtitle download was interrupted");
            } catch (Exception e) {
                if (launch.isCancelled()) {
                    throw new CancellationException(
                            "yt-dlp subtitle download was cancelled");
                }
                throw new RuntimeException("Subtitle download failed: " + e.getMessage(), e);
            } finally {
                if (registration != null) {
                    registration.unregister();
                } else {
                    launch.unregister();
                }
            }
        }, executor);
    }

    /**
     * Number of currently registered download processes. Exposed for
     * lifecycle tests.
     *
     * @return the active process count
     */
    int getActiveProcessCount() {
        return activeProcesses.size();
    }

    /**
     * Cancels an active download process.
     *
     * @param processId The process ID to cancel
     * @return true if the process was found and canceled, false otherwise
     */
    public boolean cancelDownload(String processId) {
        // Uniform termination: SIGTERM (yt-dlp cleans up .part files),
        // then bounded SIGKILL. The old path went straight to SIGKILL.
        return activeProcesses.terminate(processId, 5);
    }

    /**
     * Shuts down the client and cleanup resources.
     */
    public void shutdown() {
        // Cancel all active processes (SIGTERM -> bounded wait -> SIGKILL)
        activeProcesses.terminateAll(5);

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
    List<String> buildDownloadCommand(String url, YtDlpSettings settings, Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);

        // Output template
        String outputTemplate = settings.getOutputTemplate() != null
                ? settings.getOutputTemplate().replace("%", "%%")
                : "%(title)s-%(id)s.%(ext)s";
        command.add("-o");
        command.add(outputTemplate);

        // Progress and logging. The template emits machine-parsable lines
        // with EXACT downloaded/total byte counts; the legacy "[download]
        // 42.3% of ~10.00MiB" output only carries rounded values, and
        // deriving downloadedBytes from percent x total drifted.
        command.add("--newline");
        command.add("--progress-template");
        command.add("download:[download] %(progress._percent_str)s of "
                + "%(progress._total_bytes_estimate_str)s at %(progress._speed_str)s "
                + "|odmbytes|%(progress.downloaded_bytes)s|%(progress.total_bytes_estimate)s");
        // A stable machine marker covers merged/post-processed files and
        // already-present outputs without depending on localized prose.
        command.add("--print");
        command.add("after_move:|odmfile|%(filepath)s");
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
        if (settings.getMaxRetries() > 0) {
            command.add("--retries");
            command.add(String.valueOf(settings.getMaxRetries()));
        }
        if (settings.getRetryDelaySeconds() > 0) {
            command.add("--retry-sleep");
            command.add(String.valueOf(settings.getRetryDelaySeconds()));
        }

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
        if (settings.getCookieHeader() != null) {
            command.add("--add-header");
            command.add(settings.getCookieHeader());
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

        // Concurrent fragment downloads from the shared connections field
        if (settings.getConnections() > 1) {
            command.add("--concurrent-fragments");
            command.add(String.valueOf(settings.getConnections()));
        }

        // Add aria2c external downloader if configured. The dedicated
        // useAria2c flag is authoritative: the old wiring consulted the
        // additional-options map ("use-aria2c"), which setUseAria2c(true)
        // never populated, so the external downloader never engaged.
        if (settings.isUseAria2c()) {
            command.add("--external-downloader");
            command.add("aria2c");

            String aria2cArgs = settings.buildAria2cArgs();
            if (aria2cArgs != null && !aria2cArgs.isEmpty()) {
                command.add("--external-downloader-args");
                command.add(aria2cArgs);
            }
        }

        // Add any additional options; imported settings are untrusted, so
        // only allowlisted keys may become yt-dlp flags (--exec & friends
        // execute commands)
        Map<String, String> nativeAdditionalOptions = settings.getAdditionalOptions();
        nativeAdditionalOptions.keySet().removeIf(key -> key.startsWith("odm."));
        for (Map.Entry<String, String> entry : org.manager.tools.ToolOptionFilter
                .filter(org.manager.tools.ToolOptionFilter.Tool.YTDLP,
                        nativeAdditionalOptions)
                .entrySet()) {
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

    /** Builds a no-overwrite, subtitle-only yt-dlp invocation. */
    List<String> buildSubtitleCommand(String url, YtDlpSettings settings,
            Path outputDirectory) {
        java.util.Objects.requireNonNull(url, "url");
        java.util.Objects.requireNonNull(settings, "settings");
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--skip-download");
        if (settings.isWriteSubtitles()) {
            command.add("--write-subs");
        }
        if (settings.isWriteAutoSubs()) {
            command.add("--write-auto-subs");
        }
        if (!settings.getSubtitleLanguages().isEmpty()) {
            command.add("--sub-langs");
            command.add(String.join(",", settings.getSubtitleLanguages()));
        }
        command.add("--sub-format");
        command.add("srt/best");
        command.add("--no-overwrites");
        if (outputDirectory != null) {
            command.add("--paths");
            command.add("subtitle:" + outputDirectory.toAbsolutePath().normalize());
        }
        if (settings.getOutputTemplate() != null) {
            command.add("--output");
            command.add("subtitle:" + settings.getOutputTemplate());
        }
        if (settings.isUseProxy() && settings.getProxyAddress() != null
                && !settings.getProxyAddress().isBlank()) {
            command.add("--proxy");
            command.add(settings.getProxyAddress());
        }
        if (settings.getReferer() != null && !settings.getReferer().isBlank()) {
            command.add("--referer");
            command.add(settings.getReferer());
        }
        if (settings.getUserAgent() != null && !settings.getUserAgent().isBlank()) {
            command.add("--user-agent");
            command.add(settings.getUserAgent());
        }
        if (settings.getCookieHeader() != null && !settings.getCookieHeader().isBlank()) {
            command.add("--add-header");
            command.add(settings.getCookieHeader());
        }
        if (settings.getCookieFile() != null && !settings.getCookieFile().isBlank()) {
            command.add("--cookies");
            command.add(settings.getCookieFile());
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
    String extractFilename(String line) {
        int marker = line.indexOf("|odmfile|");
        if (marker >= 0) {
            String path = line.substring(marker + "|odmfile|".length()).trim();
            return path.isEmpty() ? null : path;
        }
        if (line.contains("[download] Destination:")) {
            String path = line.substring(line.indexOf("Destination:")
                    + "Destination:".length()).trim();
            return path.isEmpty() ? null : path;
        }
        String alreadyDownloaded = " has already been downloaded";
        if (line.startsWith("[download] ") && line.endsWith(alreadyDownloaded)) {
            String path = line.substring("[download] ".length(),
                    line.length() - alreadyDownloaded.length()).trim();
            return path.isEmpty() ? null : path;
        }
        return null;
    }

    /**
     * Parses progress information from yt-dlp output. Prefers the exact
     * byte counts from the {@code |odmbytes|downloaded|total} suffix of the
     * custom progress template; falls back to the legacy rounded-output
     * regex (older yt-dlp without --progress-template).
     */
    private boolean parseProgress(String line, ProgressCallback callback) {
        int exactMarker = line.indexOf("|odmbytes|");
        if (exactMarker >= 0) {
            try {
                String[] parts = line.substring(exactMarker + "|odmbytes|".length()).split("\\|");
                long downloadedBytes = Long.parseLong(parts[0].trim());
                String totalRaw = parts.length > 1 ? parts[1].trim() : "";
                // total_bytes_estimate is "NA"/empty until yt-dlp knows
                long totalBytes = totalRaw.isEmpty() || "NA".equals(totalRaw) || "None".equals(totalRaw)
                        ? 0 : Long.parseLong(totalRaw);

                // Percentage and speed still come from the human-readable
                // prefix when present
                float percentage = totalBytes > 0
                        ? (float) (downloadedBytes * 100.0 / totalBytes) : 0;
                float speedBps = 0;
                Matcher speedMatcher = SPEED_SUFFIX_PATTERN.matcher(line.substring(0, exactMarker));
                if (speedMatcher.find()) {
                    speedBps = convertToBytes(Float.parseFloat(speedMatcher.group(1)),
                            speedMatcher.group(2));
                }

                callback.onProgress(percentage, downloadedBytes, totalBytes, speedBps);
                return true;
            } catch (NumberFormatException e) {
                // Fall through to the legacy parser
            }
        }

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
                return true;
            } catch (NumberFormatException e) {
                // Ignore parsing errors
            }
        }
        return false;
    }

    /** Test seam for the progress-line parser. */
    void parseProgressForTest(String line, ProgressCallback callback) {
        parseProgress(line, callback);
    }

    private long completedFileSize(String filename, Path outputPath) {
        try {
            Path completedPath = Path.of(filename);
            if (!completedPath.isAbsolute() && outputPath != null) {
                completedPath = outputPath.resolve(completedPath);
            }
            return Files.isRegularFile(completedPath) ? Files.size(completedPath) : 0L;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            LOGGER.debug("Unable to inspect completed yt-dlp output size", e);
            return 0L;
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
