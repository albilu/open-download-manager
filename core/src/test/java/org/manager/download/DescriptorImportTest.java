package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.util.DescriptorStaging;

@DisplayName("Local descriptor import")
class DescriptorImportTest {

    @TempDir
    Path tempDir;

    private static class RecordingOperations implements DownloadOperations {

        private final AtomicBoolean sourceExistedAtCreation;
        private final Path originalSource;
        private Path torrentSource;
        private URI metalinkSource;
        private int cancellations;

        RecordingOperations(Path originalSource, AtomicBoolean sourceExistedAtCreation) {
            this.originalSource = originalSource;
            this.sourceExistedAtCreation = sourceExistedAtCreation;
        }

        @Override
        public Download createTorrentDownload(Path source, Path destination) {
            sourceExistedAtCreation.set(Files.exists(originalSource));
            torrentSource = source;
            return Download.fromTorrent(source, destination);
        }

        @Override
        public Download createMetaLinkDownload(URI source, Path destination) {
            sourceExistedAtCreation.set(Files.exists(originalSource));
            metalinkSource = source;
            return Download.fromMetaLink(Path.of(source), destination);
        }

        @Override
        public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
            cancellations++;
            return CompletableFuture.completedFuture(null);
        }

        @Override public Download createDownload(URI uri, Path destination) { throw unsupported(); }
        @Override public Download createMagnetDownload(URI uri, Path destination) { throw unsupported(); }
        @Override public Download createYoutubeDownload(URI uri, Path destination,
                Map<String, String> options) { throw unsupported(); }
        @Override public Download createWebsiteDownload(URI uri, Path destination,
                Map<String, String> options) { throw unsupported(); }
        @Override public CompletableFuture<Void> queueDownload(Download download) { throw unsupported(); }
        @Override public CompletableFuture<Void> queueDownloadFromBackgroundSource(
                Download download) { throw unsupported(); }
        @Override public CompletableFuture<Void> queueDownloadForManualStart(Download download) { throw unsupported(); }
        @Override public CompletableFuture<Void> startDownload(Download download) { throw unsupported(); }
        @Override public CompletableFuture<Void> pauseDownload(Download download) { throw unsupported(); }
        @Override public CompletableFuture<Void> resumeDownload(Download download) { throw unsupported(); }
        @Override public CompletableFuture<Void> changeSettings(Download download) { throw unsupported(); }
        @Override public CompletableFuture<Void> verifyData(Download download) { throw unsupported(); }
        @Override public CompletableFuture<Void> relocateDownload(Download download,
                Path destination) { throw unsupported(); }
        @Override public List<Map<String, Object>> getDownloadPeers(Download download) { throw unsupported(); }
        @Override public List<Map<String, Object>> getDownloadFiles(Download download) { throw unsupported(); }
        @Override public CompletableFuture<List<DownloadFileInfo>> previewDownloadFiles(
                URI source, String proxyAddress) { throw unsupported(); }
        @Override public List<List<String>> getDownloadTrackers(Download download) { throw unsupported(); }
        @Override public void setDownloadGate(java.util.function.Predicate<String> gate) { throw unsupported(); }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by this test");
        }
    }

    @Test
    @DisplayName("The download is created before a selected torrent is moved to XDG Trash")
    void createsBeforeTrashingTorrent() throws Exception {
        Path dataHome = tempDir.resolve("xdg-data");
        Path source = Files.createDirectories(tempDir.resolve("selected"))
                .resolve("movie.torrent");
        Files.writeString(source, "torrent descriptor bytes");
        Path destination = Files.createDirectories(tempDir.resolve("downloads"));
        AtomicBoolean sourceExistedAtCreation = new AtomicBoolean();
        RecordingOperations operations = new RecordingOperations(source, sourceExistedAtCreation);

        Download created = SystemLambda.withEnvironmentVariable(
                "XDG_DATA_HOME", dataHome.toString()).execute(() ->
                        DescriptorImport.create(operations, source, destination, true));

        assertTrue(sourceExistedAtCreation.get(),
                "creation must complete before the original is moved");
        assertFalse(Files.exists(source));
        assertTrue(Files.exists(dataHome.resolve("Trash/files/movie.torrent")));
        Path staged = Path.of(created.getUri());
        assertTrue(DescriptorStaging.isStagedPath(staged,
                dataHome.resolve("odm/descriptor-staging")));
        assertTrue(Files.exists(staged), "aria2 must retain a readable staged descriptor");
        assertEquals("movie.torrent", created.getName());
        assertEquals(Download.Protocol.TORRENT, created.getProtocol());
        assertEquals(staged, operations.torrentSource);
    }

    @Test
    @DisplayName("A selected .meta4 remains user-owned when the Trash policy is off")
    void keepsMeta4WhenPolicyIsOff() throws Exception {
        Path source = tempDir.resolve("mirrors.meta4");
        Files.writeString(source, "metalink descriptor bytes");
        Path destination = Files.createDirectories(tempDir.resolve("meta-downloads"));
        Download expected = Download.fromMetaLink(source, destination);
        RecordingOperations operations = new RecordingOperations(source, new AtomicBoolean()) {
            @Override
            public Download createMetaLinkDownload(URI descriptor, Path target) {
                super.createMetaLinkDownload(descriptor, target);
                return expected;
            }
        };

        Download created = DescriptorImport.create(
                operations, source, destination, false);

        assertSame(expected, created);
        assertTrue(Files.exists(source));
        assertEquals(source.toUri(), operations.metalinkSource);
    }

    @Test
    @DisplayName("A Trash failure rolls back the created item and managed copy")
    void trashFailureRollsBackCreation() throws Exception {
        Path dataHome = Files.createDirectories(tempDir.resolve("blocked-xdg-data"));
        Files.writeString(dataHome.resolve("Trash"), "blocks the Trash directory");
        Path source = tempDir.resolve("rollback.torrent");
        Files.writeString(source, "torrent descriptor bytes");
        Path destination = Files.createDirectories(tempDir.resolve("rollback-downloads"));
        RecordingOperations operations = new RecordingOperations(source, new AtomicBoolean());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SystemLambda.withEnvironmentVariable(
                        "XDG_DATA_HOME", dataHome.toString()).execute(() ->
                                DescriptorImport.create(
                                        operations, source, destination, true)));

        assertTrue(failure.getMessage().contains("Trash"));
        assertTrue(Files.exists(source), "the original must remain when Trash rejects the move");
        assertEquals(1, operations.cancellations,
                "the item created before the Trash failure must be rolled back");
        Path manualRoot = dataHome.resolve("odm/descriptor-staging/manual");
        try (var staged = Files.list(manualRoot)) {
            assertEquals(0, staged.count(), "the rolled-back staged copy must be deleted");
        }
    }
}
