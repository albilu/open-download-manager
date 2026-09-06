package org.manager.download.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.subliminal.SubliminalClient;
import org.subliminal.SubliminalSettings;
import org.ytdlp.YtDlpClient;
import org.ytdlp.YtDlpSettings;

class SubtitleDownloadActionTest {

    @TempDir
    Path tempDir;

    @Test
    void languageParserRemainsACompatibilitySeamForTheSettingsUi() {
        assertEquals(List.of("fr", "it", "pt-BR"),
                SubtitleDownloadAction.parseLanguages(" fr, it, FR, pt-br "));
        assertEquals(List.of("en"), SubtitleDownloadAction.parseLanguages("  "));
        assertThrows(IllegalArgumentException.class,
                () -> SubtitleDownloadAction.parseLanguages("fr;rm -rf"));
    }

    @Test
    void genericVideoDelegatesOnlyMissingLanguagesToSubliminalClient() throws Exception {
        Path video = Files.writeString(tempDir.resolve("movie.mkv"), "video");
        Files.writeString(tempDir.resolve("movie.fr.srt"), "French");
        Download download = completedDownload(
                URI.create("https://example.com/movie.mkv"), video, Download.Type.ARIA2);
        RecordingSubliminalClient subliminal = new RecordingSubliminalClient(true);
        RecordingYtDlpClient ytDlp = new RecordingYtDlpClient(true);

        SubtitleDownloadAction action = action(List.of("fr", "it"), subliminal, ytDlp);
        assertTrue(action.execute(download));

        assertEquals(List.of(video), subliminal.mediaFiles);
        assertEquals(List.of(List.of("it")), subliminal.languageCalls);
        assertTrue(ytDlp.settingsCalls.isEmpty());
        assertTrue(action.getOutput().contains("Engine: Subliminal"));
        assertTrue(action.getOutput().contains("Media: " + video));
        assertTrue(action.getOutput().contains("Requested languages: it"));
        assertTrue(action.getOutput().contains("Process output:\nSubtitle saved"));
        assertTrue(action.getOutput().contains("Overall result: Succeeded"));
    }

    @Test
    void existingPreferredSubtitlesSkipTheGenericEngineEntirely() throws Exception {
        Path video = Files.writeString(tempDir.resolve("movie.mp4"), "video");
        Files.writeString(tempDir.resolve("movie.fr.srt"), "French");
        Files.writeString(tempDir.resolve("movie.it.vtt"), "Italian");
        Download download = completedDownload(
                URI.create("https://example.com/movie.mp4"), video, Download.Type.ARIA2);
        RecordingSubliminalClient subliminal = new RecordingSubliminalClient(true);

        SubtitleDownloadAction action = action(List.of("fr", "it"), subliminal,
                new RecordingYtDlpClient(true));
        assertTrue(action.execute(download));
        assertTrue(subliminal.mediaFiles.isEmpty());
        assertTrue(action.getOutput().contains("All preferred subtitles already exist"));
    }

    @Test
    void mediaPlatformDownloadDelegatesSubtitleOnlySettingsToYtDlpClient() throws Exception {
        Path video = Files.writeString(tempDir.resolve("clip.webm"), "video");
        Files.writeString(tempDir.resolve("clip.fr.vtt"), "French");
        Download download = completedDownload(
                URI.create("https://www.youtube.com/watch?v=abc123"),
                video, Download.Type.YOUTUBE);
        YtDlpSettings original = new YtDlpSettings();
        original.setUseProxy(true);
        original.setProxyAddress("socks5://127.0.0.1:1080");
        original.setCookieFile("/tmp/cookies.txt");
        original.setSubtitleLanguages(List.of("fr", "it"));
        download.setSettings(original);
        RecordingSubliminalClient subliminal = new RecordingSubliminalClient(true);
        RecordingYtDlpClient ytDlp = new RecordingYtDlpClient(true);

        SubtitleDownloadAction action = action(List.of("fr", "it"), subliminal, ytDlp);
        assertTrue(action.execute(download));

        assertTrue(subliminal.mediaFiles.isEmpty());
        assertEquals(List.of(download.getUri().toString()), ytDlp.urls);
        assertEquals(List.of(tempDir), ytDlp.outputDirectories);
        YtDlpSettings settings = ytDlp.settingsCalls.getFirst();
        assertTrue(settings.isWriteSubtitles());
        assertTrue(settings.isWriteAutoSubs());
        assertFalse(settings.isEmbedSubs());
        assertEquals(List.of("it"), settings.getSubtitleLanguages());
        assertEquals("clip.%(ext)s", settings.getOutputTemplate());
        assertEquals("socks5://127.0.0.1:1080", settings.getProxyAddress());
        assertEquals("/tmp/cookies.txt", settings.getCookieFile());
        assertTrue(action.getOutput().contains("Engine: yt-dlp"));
        assertTrue(action.getOutput().contains("Output directory: " + tempDir));
        assertTrue(action.getOutput().contains("Requested languages: it"));
        assertTrue(action.getOutput().contains("Overall result: Succeeded"));
    }

    @Test
    void nonVideoGenericDownloadIsACompletedNoOp() throws Exception {
        Path archive = Files.writeString(tempDir.resolve("archive.zip"), "archive");
        Download download = completedDownload(
                URI.create("https://example.com/archive.zip"), archive, Download.Type.ARIA2);
        RecordingSubliminalClient subliminal = new RecordingSubliminalClient(true);

        assertTrue(action(List.of("fr"), subliminal,
                new RecordingYtDlpClient(true)).execute(download));
        assertTrue(subliminal.mediaFiles.isEmpty());
    }

    @Test
    void genericSubliminalVideoExtensionIsNotSilentlyIgnored() throws Exception {
        Path video = Files.writeString(tempDir.resolve("movie.vob"), "video");
        Download download = completedDownload(
                URI.create("https://example.com/movie.vob"), video, Download.Type.ARIA2);
        RecordingSubliminalClient subliminal = new RecordingSubliminalClient(true);

        assertTrue(action(List.of("fr"), subliminal,
                new RecordingYtDlpClient(true)).execute(download));

        assertEquals(List.of(video), subliminal.mediaFiles);
    }

    @Test
    void engineFailureFailsTheCompletionAction() throws Exception {
        Path video = Files.writeString(tempDir.resolve("movie.mkv"), "video");
        Download download = completedDownload(
                URI.create("https://example.com/movie.mkv"), video, Download.Type.ARIA2);

        SubtitleDownloadAction action = action(List.of("fr"),
                new RecordingSubliminalClient(false), new RecordingYtDlpClient(true));

        assertFalse(action.execute(download));
        assertTrue(action.getOutput().contains("Process exit code: 2"));
        assertTrue(action.getOutput().contains("Process output:\nProvider lookup failed"));
        assertTrue(action.getOutput().contains(
                "Result: Subtitle request failed: Subliminal exited with code 2"));
    }

    @Test
    void successfulSubtitleActionPersistsDetailedOutput() throws Exception {
        Path video = Files.writeString(tempDir.resolve("movie.mkv"), "video");
        Download download = completedDownload(
                URI.create("https://example.com/movie.mkv"), video, Download.Type.ARIA2);
        SubtitleDownloadAction subtitles = action(List.of("fr"),
                new RecordingSubliminalClient(true), new RecordingYtDlpClient(true));
        AfterCompletionActionManager manager = new AfterCompletionActionManager();
        try {
            manager.addAction(download, subtitles);

            manager.executeActions(download).get(5, TimeUnit.SECONDS);

            CompletionActionResult result = download.getCompletionActionResults().getFirst();
            assertEquals(CompletionActionResult.Status.SUCCEEDED, result.status());
            assertTrue(result.output().contains("Engine: Subliminal"));
            assertTrue(result.output().contains("Requested languages: fr"));
            assertTrue(result.output().contains("Process output:\nSubtitle saved"));
            assertTrue(result.output().contains("Overall result: Succeeded"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void failedSubtitleActionPersistsSubliminalProcessDiagnostics() throws Exception {
        Path video = Files.writeString(tempDir.resolve("movie.mkv"), "video");
        Download download = completedDownload(
                URI.create("https://example.com/movie.mkv"), video, Download.Type.ARIA2);
        SubtitleDownloadAction subtitles = action(List.of("fr"),
                new RecordingSubliminalClient(false), new RecordingYtDlpClient(true));
        AfterCompletionActionManager manager = new AfterCompletionActionManager();
        try {
            manager.addAction(download, subtitles);

            manager.executeActions(download).get(5, TimeUnit.SECONDS);

            CompletionActionResult result = download.getCompletionActionResults().getFirst();
            assertEquals(CompletionActionResult.Status.FAILED, result.status());
            assertTrue(result.output().contains("Process exit code: 2"));
            assertTrue(result.output().contains("Process output:\nProvider lookup failed"));
            assertTrue(result.output().contains("Subliminal exited with code 2"));
        } finally {
            manager.shutdown();
        }
    }

    private SubtitleDownloadAction action(List<String> languages,
            SubliminalClient subliminal, YtDlpClient ytDlp) {
        SubliminalSettings settings = new SubliminalSettings()
                .setLanguages(languages)
                .setTimeout(Duration.ofSeconds(30));
        return new SubtitleDownloadAction(settings, subliminal, ytDlp);
    }

    private static Download completedDownload(URI uri, Path output, Download.Type type) {
        Download download = new Download(uri);
        download.setType(type);
        download.setDestination(output.getParent());
        download.setName(output.getFileName().toString());
        download.recordOutputPath(output);
        download.setStatus(Download.Status.COMPLETED);
        return download;
    }

    private static final class RecordingSubliminalClient extends SubliminalClient {
        private final List<Path> mediaFiles = new ArrayList<>();
        private final List<List<String>> languageCalls = new ArrayList<>();
        private final boolean result;
        private final String processOutput;

        private RecordingSubliminalClient(boolean result) {
            super("/custom/subliminal");
            this.result = result;
            processOutput = result ? "Subtitle saved\n" : "Provider lookup failed\n";
        }

        @Override
        public DownloadResult downloadWithResult(
                Path mediaFile, SubliminalSettings settings, String operationId) {
            mediaFiles.add(mediaFile);
            languageCalls.add(settings.getLanguages());
            return new DownloadResult(result, result ? 0 : 2, processOutput,
                    result ? "Subliminal completed successfully"
                            : "Subliminal exited with code 2");
        }
    }

    private static final class RecordingYtDlpClient extends YtDlpClient {
        private final List<String> urls = new ArrayList<>();
        private final List<YtDlpSettings> settingsCalls = new ArrayList<>();
        private final List<Path> outputDirectories = new ArrayList<>();
        private final boolean result;

        private RecordingYtDlpClient(boolean result) {
            super("/custom/yt-dlp");
            this.result = result;
        }

        @Override
        public CompletableFuture<Void> downloadSubtitles(String url, YtDlpSettings settings,
                Path outputDirectory, String processId) {
            urls.add(url);
            settingsCalls.add(settings);
            outputDirectories.add(outputDirectory);
            return result ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(new IllegalStateException("failed"));
        }
    }
}
