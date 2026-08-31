package org.manager.download.action;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.manager.download.Download;
import org.manager.download.DownloadSettings;
import org.manager.download.ExternalToolSettings;
import org.manager.tools.ExternalProcessRegistry;
import org.ytdlp.YtDlpSettings;

/**
 * Downloads preferred-language subtitles after a transfer completes.
 * yt-dlp performs subtitle-only retrieval for media-platform downloads;
 * Subliminal handles ordinary downloaded video files. Existing
 * language-tagged subtitle files are excluded before either tool runs, and
 * both native commands retain their own no-overwrite behavior as a second
 * guard.
 */
public final class SubtitleDownloadAction implements AfterCompletionAction {

    private static final Logger LOGGER = Logger.getLogger(SubtitleDownloadAction.class.getName());
    private static final Pattern LANGUAGE_TAG = Pattern.compile(
            "(?i)[a-z]{2,3}(?:-[a-z0-9]{2,8})*");
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

    private final List<String> languages;
    private final String subliminalPath;
    private final String ytDlpPath;
    private final Duration timeout;
    private final CommandExecutor commandExecutor;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Creates a subtitle action backed by real external processes.
     */
    public SubtitleDownloadAction(List<String> languages, String subliminalPath,
            String ytDlpPath, Duration timeout) {
        this(languages, subliminalPath, ytDlpPath, timeout,
                new ProcessCommandExecutor());
    }

    SubtitleDownloadAction(List<String> languages, String subliminalPath,
            String ytDlpPath, Duration timeout, CommandExecutor commandExecutor) {
        this.languages = normalizeLanguages(languages);
        this.subliminalPath = executableOrDefault(subliminalPath, "subliminal");
        this.ytDlpPath = executableOrDefault(ytDlpPath, "yt-dlp");
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofMinutes(5) : timeout;
        this.commandExecutor = java.util.Objects.requireNonNull(commandExecutor,
                "commandExecutor");
    }

    /** Parses comma-separated IETF language tags, defaulting to English. */
    public static List<String> parseLanguages(String value) {
        if (value == null || value.isBlank()) {
            return List.of("en");
        }
        List<String> parsed = new ArrayList<>();
        for (String token : value.split(",")) {
            String trimmed = token.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!LANGUAGE_TAG.matcher(trimmed).matches()) {
                throw new IllegalArgumentException("Invalid subtitle language tag: " + trimmed);
            }
            parsed.add(normalizeLanguageTag(trimmed));
        }
        return normalizeLanguages(parsed);
    }

    public List<String> getLanguages() {
        return languages;
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
            List<String> command = new ArrayList<>();
            command.add(subliminalPath);
            command.add("download");
            for (String language : missing) {
                command.add("-l");
                command.add(language);
            }
            // Subliminal intentionally receives no --force flag: its native
            // scan skips external/embedded subtitles in the requested language.
            command.add(video.toString());
            success &= commandExecutor.execute(
                    download.getId() + ":subliminal:" + index, command, timeout);
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

        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--skip-download");
        command.add("--write-subs");
        command.add("--write-auto-subs");
        command.add("--sub-langs");
        command.add(String.join(",", missing));
        command.add("--sub-format");
        command.add("srt/best");
        command.add("--no-overwrites");

        Path subtitleDirectory = download.getDestination() != null
                ? download.getDestination() : outputs.getFirst().getParent();
        if (subtitleDirectory != null) {
            command.add("--paths");
            command.add("subtitle:" + subtitleDirectory);
        }
        if (outputs.size() == 1) {
            command.add("--output");
            command.add("subtitle:" + stem(outputs.getFirst()) + ".%(ext)s");
        } else if (download.getSettings() instanceof YtDlpSettings settings
                && settings.getOutputTemplate() != null) {
            command.add("--output");
            command.add("subtitle:" + settings.getOutputTemplate());
        }
        appendYtDlpNetworkOptions(command, download.getSettings());
        command.add(download.getUri().toString());

        return !cancelled.get() && commandExecutor.execute(
                download.getId() + ":yt-dlp-subtitles", command, timeout);
    }

    private static void appendYtDlpNetworkOptions(List<String> command,
            DownloadSettings settings) {
        if (settings == null) {
            return;
        }
        if (settings.isUseProxy() && settings.getProxyAddress() != null
                && !settings.getProxyAddress().isBlank()) {
            command.add("--proxy");
            command.add(settings.getProxyAddress());
        }
        ExternalToolSettings external = settings;
        if (external.getReferer() != null && !external.getReferer().isBlank()) {
            command.add("--referer");
            command.add(external.getReferer());
        }
        if (external.getUserAgent() != null && !external.getUserAgent().isBlank()) {
            command.add("--user-agent");
            command.add(external.getUserAgent());
        }
        if (external.getCookieHeader() != null && !external.getCookieHeader().isBlank()) {
            command.add("--add-header");
            command.add(external.getCookieHeader());
        }
        if (settings instanceof YtDlpSettings ytDlpSettings
                && ytDlpSettings.getCookieFile() != null
                && !ytDlpSettings.getCookieFile().isBlank()) {
            command.add("--cookies");
            command.add(ytDlpSettings.getCookieFile());
        }
    }

    private List<String> missingLanguages(Path mediaFile) {
        List<String> missing = new ArrayList<>();
        for (String language : languages) {
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

    private static List<String> normalizeLanguages(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String tag = value.strip();
                if (!LANGUAGE_TAG.matcher(tag).matches()) {
                    throw new IllegalArgumentException("Invalid subtitle language tag: " + tag);
                }
                normalized.add(normalizeLanguageTag(tag));
            }
        }
        return normalized.isEmpty() ? List.of("en") : List.copyOf(normalized);
    }

    private static String normalizeLanguageTag(String value) {
        String[] parts = value.split("-");
        StringBuilder normalized = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];
            normalized.append('-');
            if (part.length() == 2 || (part.length() == 3 && part.chars().allMatch(Character::isDigit))) {
                normalized.append(part.toUpperCase(Locale.ROOT));
            } else if (part.length() == 4) {
                normalized.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.substring(1).toLowerCase(Locale.ROOT));
            } else {
                normalized.append(part.toLowerCase(Locale.ROOT));
            }
        }
        return normalized.toString();
    }

    private static String executableOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public ActionType getType() {
        return ActionType.DOWNLOAD_SUBTITLES;
    }

    @Override
    public String getDescription() {
        return "Download subtitles (" + String.join(", ", languages) + ")";
    }

    @Override
    public Severity getSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public boolean cancel() {
        cancelled.set(true);
        commandExecutor.cancelAll();
        return true;
    }

    interface CommandExecutor {
        boolean execute(String operationId, List<String> command, Duration timeout);

        void cancelAll();
    }

    private static final class ProcessCommandExecutor implements CommandExecutor {
        private final ExternalProcessRegistry processes =
                new ExternalProcessRegistry("subtitle-action");

        @Override
        public boolean execute(String operationId, List<String> command, Duration timeout) {
            ExternalProcessRegistry.LaunchReservation launch = processes.reserve(operationId);
            ExternalProcessRegistry.Registration registration = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD);
                registration = launch.start(builder);
                Process process = registration.process();
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    LOGGER.warning("Subtitle command timed out: " + command.getFirst());
                    processes.terminate(operationId, 5);
                    return false;
                }
                if (process.exitValue() != 0) {
                    LOGGER.warning("Subtitle command failed with exit code "
                            + process.exitValue() + ": " + command.getFirst());
                    return false;
                }
                return true;
            } catch (java.util.concurrent.CancellationException e) {
                return false;
            } catch (java.io.IOException e) {
                LOGGER.log(Level.WARNING, "Could not start subtitle engine " + command.getFirst(), e);
                return false;
            } catch (InterruptedException e) {
                processes.terminate(operationId, 5);
                Thread.currentThread().interrupt();
                return false;
            } finally {
                if (registration != null) {
                    registration.unregister();
                } else {
                    launch.unregister();
                }
            }
        }

        @Override
        public void cancelAll() {
            processes.terminateAll(5);
        }
    }
}
