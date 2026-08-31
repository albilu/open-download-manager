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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;

class SubtitleDownloadActionTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesDeduplicatedIetfLanguagePreferencesWithEnglishDefault() {
        assertEquals(List.of("fr", "it", "pt-BR"),
                SubtitleDownloadAction.parseLanguages(" fr, it, FR, pt-br "));
        assertEquals(List.of("en"), SubtitleDownloadAction.parseLanguages("  "));
        assertThrows(IllegalArgumentException.class,
                () -> SubtitleDownloadAction.parseLanguages("fr;rm -rf"));
    }

    @Test
    void genericVideoUsesSubliminalOnlyForLanguagesNotAlreadyPresent() throws Exception {
        Path video = Files.writeString(tempDir.resolve("movie.mkv"), "video");
        Files.writeString(tempDir.resolve("movie.fr.srt"), "French");
        Download download = completedDownload(
                URI.create("https://example.com/movie.mkv"), video, Download.Type.ARIA2);
        RecordingExecutor executor = new RecordingExecutor(true);
        SubtitleDownloadAction action = action(List.of("fr", "it"), executor);

        assertTrue(action.execute(download));

        assertEquals(List.of(List.of(
                "/custom/subliminal", "download", "-l", "it", video.toString())),
                executor.commands);
    }

    @Test
    void existingPreferredSubtitlesSkipTheGenericEngineEntirely() throws Exception {
        Path video = Files.writeString(tempDir.resolve("movie.mp4"), "video");
        Files.writeString(tempDir.resolve("movie.fr.srt"), "French");
        Files.writeString(tempDir.resolve("movie.it.vtt"), "Italian");
        Download download = completedDownload(
                URI.create("https://example.com/movie.mp4"), video, Download.Type.ARIA2);
        RecordingExecutor executor = new RecordingExecutor(true);

        assertTrue(action(List.of("fr", "it"), executor).execute(download));
        assertTrue(executor.commands.isEmpty());
    }

    @Test
    void mediaPlatformDownloadUsesYtDlpSubtitleOnlyAndNoOverwriteMode() throws Exception {
        Path video = Files.writeString(tempDir.resolve("clip.webm"), "video");
        Files.writeString(tempDir.resolve("clip.fr.vtt"), "French");
        Download download = completedDownload(
                URI.create("https://www.youtube.com/watch?v=abc123"),
                video, Download.Type.YOUTUBE);
        RecordingExecutor executor = new RecordingExecutor(true);

        assertTrue(action(List.of("fr", "it"), executor).execute(download));

        assertEquals(1, executor.commands.size());
        List<String> command = executor.commands.getFirst();
        assertEquals("/custom/yt-dlp", command.getFirst());
        assertTrue(command.contains("--skip-download"));
        assertTrue(command.contains("--write-subs"));
        assertTrue(command.contains("--write-auto-subs"));
        assertTrue(command.contains("--no-overwrites"));
        assertEquals("it", command.get(command.indexOf("--sub-langs") + 1));
        assertEquals(download.getUri().toString(), command.getLast());
        assertFalse(command.contains("/custom/subliminal"));
    }

    @Test
    void nonVideoGenericDownloadIsACompletedNoOp() throws Exception {
        Path archive = Files.writeString(tempDir.resolve("archive.zip"), "archive");
        Download download = completedDownload(
                URI.create("https://example.com/archive.zip"), archive, Download.Type.ARIA2);
        RecordingExecutor executor = new RecordingExecutor(true);

        assertTrue(action(List.of("fr"), executor).execute(download));
        assertTrue(executor.commands.isEmpty());
    }

    @Test
    void genericSubliminalVideoExtensionIsNotSilentlyIgnored() throws Exception {
        Path video = Files.writeString(tempDir.resolve("movie.vob"), "video");
        Download download = completedDownload(
                URI.create("https://example.com/movie.vob"), video, Download.Type.ARIA2);
        RecordingExecutor executor = new RecordingExecutor(true);

        assertTrue(action(List.of("fr"), executor).execute(download));

        assertEquals(1, executor.commands.size());
        assertEquals(video.toString(), executor.commands.getFirst().getLast());
    }

    @Test
    void engineFailureFailsTheCompletionAction() throws Exception {
        Path video = Files.writeString(tempDir.resolve("movie.mkv"), "video");
        Download download = completedDownload(
                URI.create("https://example.com/movie.mkv"), video, Download.Type.ARIA2);

        assertFalse(action(List.of("fr"), new RecordingExecutor(false)).execute(download));
    }

    private SubtitleDownloadAction action(List<String> languages,
            SubtitleDownloadAction.CommandExecutor executor) {
        return new SubtitleDownloadAction(languages, "/custom/subliminal", "/custom/yt-dlp",
                Duration.ofSeconds(30), executor);
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

    private static final class RecordingExecutor
            implements SubtitleDownloadAction.CommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private final boolean result;

        private RecordingExecutor(boolean result) {
            this.result = result;
        }

        @Override
        public boolean execute(String operationId, List<String> command, Duration timeout) {
            commands.add(List.copyOf(command));
            return result;
        }

        @Override
        public void cancelAll() {
        }
    }
}
