package org.manager.download.action;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.manager.download.Download;
import org.manager.download.DownloadSettings;
import org.subliminal.SubliminalClient;
import org.subliminal.SubliminalSettings;
import org.ytdlp.YtDlpClient;
import org.ytdlp.YtDlpSettings;

/**
 * Downloads preferred-language subtitles after a transfer completes.
 * Tool-specific command construction and process ownership live in their
 * respective clients; this action only chooses the engine, detects existing
 * subtitles, and adapts the completed download into client settings.
 */
public final class SubtitleDownloadAction implements AfterCompletionAction {

    private static final Logger LOGGER = Logger.getLogger(SubtitleDownloadAction.class.getName());

    // Keep this aligned with the video extensions recognized by Subliminal's
    // scanner so valid generic videos are not silently treated as no-ops.
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "3g2", "3gp", "3gp2", "3gpp", "60d", "ajp", "asf", "asx",
            "avchd", "avi", "bik", "bix", "box", "cam", "dat", "divx",
            "dmf", "dv", "dvr-ms", "evo", "flc", "fli", "flic", "flv",
            "flx", "gvi", "gvp", "h264", "m1v", "m2p", "m2ts", "m2v",
            "m4e", "m4v", "mjp", "mjpeg", "mjpg", "mk3d", "mkv", "moov",
            "mov", "movhd", "movie", "movx", "mp4", "mpe", "mpeg", "mpg",
            "mpv", "mpv2", "mxf", "nsv", "nut", "ogg", "ogm", "ogv",
            "omf", "ps", "qt", "ram", "rm", "rmvb", "swf", "ts", "vfw",
            "vid", "video", "viv", "vivo", "vob", "vro", "webm", "wm",
            "wmv", "wmx", "wrap", "wvx", "wx", "x264", "xvid");
    private static final Set<String> YTDLP_MEDIA_EXTENSIONS = Set.of(
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv",
            "mpg", "mpeg", "ts", "m2ts", "ogv", "3gp",
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus");
    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of(
            "srt", "vtt", "ass", "ssa", "lrc", "ttml", "dfxp", "smi");

    private final SubliminalSettings settings;
    private final SubliminalClient subliminalClient;
    private final YtDlpClient ytDlpClient;
    private final Set<String> activeYtDlpOperations = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Creates an action using the configured tool-manager paths. */
    public SubtitleDownloadAction(SubliminalSettings settings) {
        this(settings, new SubliminalClient(), new YtDlpClient());
    }

    /** Dependency-injection seam for clients and focused tests. */
    public SubtitleDownloadAction(SubliminalSettings settings,
            SubliminalClient subliminalClient, YtDlpClient ytDlpClient) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings").copy();
        this.subliminalClient = java.util.Objects.requireNonNull(
                subliminalClient, "subliminalClient");
        this.ytDlpClient = java.util.Objects.requireNonNull(ytDlpClient, "ytDlpClient");
    }

    /**
     * Compatibility constructor retained for callers that still provide tool
     * paths directly. New code should construct tool settings and clients.
     */
    public SubtitleDownloadAction(List<String> languages, String subliminalPath,
            String ytDlpPath, Duration timeout) {
        this(new SubliminalSettings().setLanguages(languages).setTimeout(timeout),
                new SubliminalClient(subliminalPath), new YtDlpClient(ytDlpPath));
    }

    /** Compatibility seam used by the settings UI. */
    public static List<String> parseLanguages(String value) {
        return SubliminalSettings.parseLanguages(value);
    }

    public List<String> getLanguages() {
        return settings.getLanguages();
    }

    @Override
    public boolean execute(Download download) {
        if (download == null || cancelled.get()) {
            return false;
        }
        try {
            return download.getType() == Download.Type.YOUTUBE
                    ? downloadWithYtDlp(download)
                    : downloadWithSubliminal(download);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Subtitle completion action failed", e);
            return false;
        }
    }

    private boolean downloadWithSubliminal(Download download) {
        List<Path> videos = completedMediaFiles(download, VIDEO_EXTENSIONS);
        boolean success = true;
        for (int index = 0; index < videos.size() && !cancelled.get(); index++) {
            Path video = videos.get(index);
            List<String> missing = missingLanguages(video);
            if (missing.isEmpty()) {
                continue;
            }
            SubliminalSettings operationSettings = settings.copy().setLanguages(missing);
            success &= subliminalClient.download(video, operationSettings,
                    download.getId() + ":subliminal:" + index);
        }
        return !cancelled.get() && success;
    }

    private boolean downloadWithYtDlp(Download download) {
        if (download.getUri() == null) {
            return false;
        }
        List<Path> outputs = completedMediaFiles(download, YTDLP_MEDIA_EXTENSIONS);
        if (outputs.isEmpty()) {
            return false;
        }

        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (Path output : outputs) {
            missing.addAll(missingLanguages(output));
        }
        if (missing.isEmpty()) {
            return true;
        }

        Path subtitleDirectory = download.getDestination() != null
                ? download.getDestination() : outputs.getFirst().getParent();
        if (subtitleDirectory == null) {
            return false;
        }

        YtDlpSettings ytSettings = subtitleSettings(download.getSettings());
        ytSettings.setWriteSubtitles(true)
                .setWriteAutoSubs(true)
                .setEmbedSubs(false)
                .setSubtitleLanguages(List.copyOf(missing));
        if (outputs.size() == 1) {
            ytSettings.setOutputTemplate(stem(outputs.getFirst()) + ".%(ext)s");
        }

        String operationId = download.getId() + ":yt-dlp-subtitles";
        activeYtDlpOperations.add(operationId);
        try {
            CompletableFuture<Void> future = ytDlpClient.downloadSubtitles(
                    download.getUri().toString(), ytSettings, subtitleDirectory, operationId);
            // cancel() may have won the tiny gap before the client installed
            // its launch reservation; close that race after reservation.
            if (cancelled.get()) {
                ytDlpClient.cancelDownload(operationId);
                future.cancel(true);
                return false;
            }
            future.get(settings.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return !cancelled.get();
        } catch (TimeoutException e) {
            ytDlpClient.cancelDownload(operationId);
            LOGGER.warning("yt-dlp subtitle download timed out for " + download.getUri());
            return false;
        } catch (CancellationException e) {
            return false;
        } catch (ExecutionException e) {
            LOGGER.log(Level.WARNING, "yt-dlp subtitle download failed", e.getCause());
            return false;
        } catch (InterruptedException e) {
            ytDlpClient.cancelDownload(operationId);
            Thread.currentThread().interrupt();
            return false;
        } finally {
            activeYtDlpOperations.remove(operationId);
        }
    }

    private static YtDlpSettings subtitleSettings(DownloadSettings source) {
        if (source instanceof YtDlpSettings ytDlpSettings) {
            return (YtDlpSettings) ytDlpSettings.copy();
        }
        YtDlpSettings result = new YtDlpSettings();
        if (source != null) {
            result.setConnections(source.getConnections());
            result.setUseProxy(source.isUseProxy());
            result.setProxyAddress(source.getProxyAddress());
            result.setReferer(source.getReferer());
            result.setUserAgent(source.getUserAgent());
            result.setCookieHeader(source.getCookieHeader());
            result.setMaxRetries(source.getMaxRetries());
            result.setRetryDelaySeconds(source.getRetryDelaySeconds());
        }
        return result;
    }

    private List<String> missingLanguages(Path mediaFile) {
        List<String> missing = new ArrayList<>();
        for (String language : settings.getLanguages()) {
            if (!hasSubtitle(mediaFile, language)) {
                missing.add(language);
            }
        }
        return missing;
    }

    private static boolean hasSubtitle(Path mediaFile, String language) {
        Path directory = mediaFile.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }
        String prefix = stem(mediaFile).toLowerCase(Locale.ROOT) + ".";
        String wanted = language.toLowerCase(Locale.ROOT);
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).anyMatch(candidate -> {
                String name = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
                String extension = extension(candidate);
                if (!name.startsWith(prefix) || !SUBTITLE_EXTENSIONS.contains(extension)) {
                    return false;
                }
                int suffixLength = extension.length() + 1;
                String languagePart = name.substring(prefix.length(), name.length() - suffixLength);
                return languagePart.equals(wanted)
                        || languagePart.startsWith(wanted + "-")
                        || languagePart.startsWith(wanted + ".");
            });
        } catch (java.io.IOException e) {
            LOGGER.log(Level.FINE, "Could not inspect existing subtitle files", e);
            return false;
        }
    }

    private static List<Path> completedMediaFiles(Download download, Set<String> extensions) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>(download.getOutputPaths());
        Path primary = download.getPrimaryOutputPath();
        if (primary != null) {
            candidates.add(primary);
        }
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .filter(path -> extensions.contains(extension(path)))
                .toList();
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @Override
    public ActionType getType() {
        return ActionType.DOWNLOAD_SUBTITLES;
    }

    @Override
    public String getDescription() {
        return "Download subtitles (" + String.join(", ", settings.getLanguages()) + ")";
    }

    @Override
    public Severity getSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public boolean cancel() {
        cancelled.set(true);
        subliminalClient.shutdown();
        for (String operationId : List.copyOf(activeYtDlpOperations)) {
            ytDlpClient.cancelDownload(operationId);
        }
        return true;
    }
}
