package org.manager.download.handler;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.download.Download;
import org.manager.GlobalSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutputOverrideSafetyTest {

    private static final class ProbeHandler extends AbstractDownloadHandler {
        ProbeHandler(GlobalSettings settings) {
            super(settings, null, null);
        }

        void prepareOutput(Download download) {
            overrideOutputPath(download);
        }

        @Override public Download.Type getSupportedType() { return Download.Type.ARIA2; }
        @Override protected void doInitialize() { }
        @Override protected void doShutdown() { }
        @Override public CompletableFuture<String> startDownload(Download download) {
            return CompletableFuture.completedFuture("probe");
        }
        @Override public CompletableFuture<Void> pauseDownload(Download download) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> resumeDownload(Download download) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> changeSettings(Download download) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void enabledSettingDeletesExistingPathBeforeEngineStart(@TempDir Path destination)
            throws Exception {
        Files.writeString(destination.resolve("archive.bin"), "resumable-content");
        Download download = new Download(URI.create("https://example.test/archive.bin"));
        download.setDestination(destination);
        download.setStatus(Download.Status.STARTING);

        new ProbeHandler(new GlobalSettings().setOverrideOutputPath(true)).prepareOutput(download);

        assertFalse(Files.exists(destination.resolve("archive.bin")));
        assertEquals("archive.bin", download.getName());
        assertTrue(download.isOverrideOutputPath());
    }

    @Test
    void defaultLeavesExistingPathAndNameForTheEngine(@TempDir Path destination) throws Exception {
        Files.writeString(destination.resolve("archive.bin"), "existing");
        Download download = new Download(URI.create("https://example.test/archive.bin"));
        download.setDestination(destination);
        assertFalse(download.isOverrideOutputPath());

        new ProbeHandler(new GlobalSettings()).prepareOutput(download);

        assertEquals("archive.bin", download.getName());
        assertEquals("existing", Files.readString(destination.resolve("archive.bin")));
    }

    @ParameterizedTest
    @CsvSource({"true, true", "false, false", "false, true", "true, false"})
    void restartingAnEnginePreservesPartialOutputRegardlessOfSettingChanges(
            boolean overrideAtStart, boolean overrideAtResume, @TempDir Path destination)
            throws Exception {
        Path output = Files.writeString(destination.resolve("archive.bin"), "existing");
        Download download = new Download(URI.create("https://example.test/archive.bin"));
        download.setDestination(destination);
        GlobalSettings settings = new GlobalSettings().setOverrideOutputPath(overrideAtStart);

        new ProbeHandler(settings).prepareOutput(download);
        assertEquals(!overrideAtStart, Files.exists(output));
        Files.writeString(output, "partially downloaded content");
        download.setStatus(Download.Status.DOWNLOADING);
        download.setStatus(Download.Status.PAUSED);
        // Recovered resumes and engine changes enter startDownload again.
        download.setStatus(Download.Status.STARTING);
        settings.setOverrideOutputPath(overrideAtResume);
        new ProbeHandler(settings).prepareOutput(download);

        assertEquals("partially downloaded content", Files.readString(output));
    }

    @Test
    void retryBeforeFirstProgressPreservesOutput(@TempDir Path destination) throws Exception {
        Download download = new Download(URI.create("https://example.test/archive.bin"));
        download.setDestination(destination);
        GlobalSettings settings = new GlobalSettings().setOverrideOutputPath(true);
        new ProbeHandler(settings).prepareOutput(download);
        Path output = Files.writeString(destination.resolve("archive.bin"), "partial");
        download.setStatus(Download.Status.ERROR);
        download.setStatus(Download.Status.STARTING);

        new ProbeHandler(settings).prepareOutput(download);

        assertEquals("partial", Files.readString(output));
    }

    @Test
    void alreadyStartedRecordPreservesOutput(@TempDir Path destination) throws Exception {
        Path output = Files.writeString(destination.resolve("archive.bin"), "partial");
        Download download = new Download(URI.create("https://example.test/archive.bin"));
        download.setDestination(destination);
        download.setStartedAt(Instant.now());
        download.setStatus(Download.Status.STARTING);

        new ProbeHandler(new GlobalSettings().setOverrideOutputPath(true)).prepareOutput(download);

        assertEquals("partial", Files.readString(output));
    }

    @Test
    void deletesTheActualRecordedDownloadPath(@TempDir Path destination) throws Exception {
        Path actual = Files.writeString(destination.resolve("actual.mp4"), "old media");
        Path displayName = Files.writeString(destination.resolve("watch"), "unrelated");
        Download download = new Download(URI.create("https://example.test/watch"));
        download.setDestination(destination);
        download.recordOutputPath(actual);
        new ProbeHandler(new GlobalSettings().setOverrideOutputPath(true)).prepareOutput(download);
        assertFalse(Files.exists(actual));
        assertEquals("unrelated", Files.readString(displayName));
    }

    @Test
    void explicitFilenameTakesPrecedence(@TempDir Path destination) throws Exception {
        Path requested = Files.writeString(destination.resolve("chosen.bin"), "old file");
        Files.writeString(destination.resolve("archive.bin"), "keep");
        Download download = new Download(URI.create("https://example.test/archive.bin"));
        download.setDestination(destination);
        download.setRequestedFileName("chosen.bin");
        new ProbeHandler(new GlobalSettings().setOverrideOutputPath(true)).prepareOutput(download);
        assertFalse(Files.exists(requested));
        assertEquals("keep", Files.readString(destination.resolve("archive.bin")));
    }

    @Test
    void deletesAnExistingDownloadFolderAndItsContents(@TempDir Path destination) throws Exception {
        Path folder = Files.createDirectory(destination.resolve("site"));
        Files.writeString(folder.resolve("index.html"), "old site");
        Files.writeString(destination.resolve("unrelated.txt"), "keep");
        Download download = new Download(URI.create("https://example.test/site"));
        download.setDestination(destination);
        new ProbeHandler(new GlobalSettings().setOverrideOutputPath(true)).prepareOutput(download);
        assertFalse(Files.exists(folder));
        assertEquals("keep", Files.readString(destination.resolve("unrelated.txt")));
    }

    @Test
    void refusesPathsOutsideDestination(@TempDir Path directory) throws Exception {
        Path destination = Files.createDirectory(directory.resolve("downloads"));
        Path outside = Files.writeString(directory.resolve("original.bin"), "keep");
        Files.createSymbolicLink(destination.resolve("archive.bin"), outside);
        Download download = new Download(URI.create("https://example.test/archive.bin"));
        download.setDestination(destination);
        assertThrows(IllegalStateException.class,
                () -> new ProbeHandler(new GlobalSettings().setOverrideOutputPath(true)).prepareOutput(download));
        assertEquals("keep", Files.readString(outside));
    }

    @Test
    void failedPreparationMustSucceedBeforeEngineCanStart(@TempDir Path directory) throws Exception {
        Path destination = Files.createDirectory(directory.resolve("downloads"));
        Path outside = Files.writeString(directory.resolve("original.bin"), "keep");
        Path output = Files.createSymbolicLink(destination.resolve("archive.bin"), outside);
        Download download = new Download(URI.create("https://example.test/archive.bin"));
        download.setDestination(destination);
        GlobalSettings settings = new GlobalSettings().setOverrideOutputPath(true);

        assertThrows(IllegalStateException.class, () -> new ProbeHandler(settings).prepareOutput(download));
        assertThrows(IllegalStateException.class, () -> new ProbeHandler(settings).prepareOutput(download));
        Files.delete(output);
        Files.writeString(output, "old output");
        new ProbeHandler(settings).prepareOutput(download);

        assertFalse(Files.exists(output));
        assertEquals("keep", Files.readString(outside));
    }

    @Test
    void localSourceDescriptorIsNotAnOutput(@TempDir Path destination) throws Exception {
        Path source = Files.writeString(destination.resolve("source.torrent"), "descriptor");
        Download download = Download.fromTorrent(source, destination);
        new ProbeHandler(new GlobalSettings().setOverrideOutputPath(true)).prepareOutput(download);
        assertEquals("descriptor", Files.readString(source));
    }
}
