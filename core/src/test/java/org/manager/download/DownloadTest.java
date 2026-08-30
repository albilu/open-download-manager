package org.manager.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.GlobalSettings;
import org.manager.schedule.ScheduleSettings;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for the Download class.
 * Tests all functionality including thread safety, settings management,
 * status transitions, and edge cases.
 */
@DisplayName("Download Unit Tests")
class DownloadTest {

    private Download download;
    private URI testUri;
    private Path testDestination;

    @BeforeEach
    void setUp() throws Exception {
        testUri = URI.create("https://example.com/test-file.zip");
        testDestination = Paths.get("/tmp/downloads");
        download = new Download(testUri);
    }

    @Nested
    @DisplayName("Construction Tests")
    class ConstructionTests {

        @Test
        @DisplayName("Should create download with random UUID")
        void shouldCreateDownloadWithRandomUuid() {
            Download download1 = new Download();
            Download download2 = new Download();

            assertNotNull(download1.getId());
            assertNotNull(download2.getId());
            assertNotEquals(download1.getId(), download2.getId());
            assertTrue(download1.getId().length() > 0);
        }

        @Test
        @DisplayName("Should create download with URI and detect type")
        void shouldCreateDownloadWithUriAndDetectType() {
            // HTTP URL
            Download httpDownload = new Download(URI.create("https://example.com/file.zip"));
            assertEquals(Download.Type.ARIA2, httpDownload.getType());

            // FTP URL
            Download ftpDownload = new Download(URI.create("ftp://example.com/file.zip"));
            assertEquals(Download.Type.ARIA2, ftpDownload.getType());

            // Magnet link
            Download magnetDownload = new Download(URI.create("magnet:?xt=urn:btih:test"));
            assertEquals(Download.Type.ARIA2, magnetDownload.getType());

            // YouTube URL
            Download youtubeDownload = new Download(URI.create("https://www.youtube.com/watch?v=test"));
            assertEquals(Download.Type.YOUTUBE, youtubeDownload.getType());

            Download youtubeShortenedDownload = new Download(URI.create("https://youtu.be/test"));
            assertEquals(Download.Type.YOUTUBE, youtubeShortenedDownload.getType());
        }

        @Test
        @DisplayName("Should extract filename from URI path")
        void shouldExtractFilenameFromUriPath() {
            URI uriWithFilename = URI.create("https://example.com/path/to/file.zip");
            Download downloadWithFilename = new Download(uriWithFilename);

            assertEquals("file.zip", downloadWithFilename.getName());
        }

        @Test
        @DisplayName("Should decode a safe URI filename without treating plus as space")
        void shouldDecodeSafeFilenameFromUriPath() {
            Download decoded = new Download(URI.create(
                    "https://example.com/path/my%20archive%2Bnotes.zip"));

            assertEquals("my archive+notes.zip", decoded.getName());
        }

        @Test
        @DisplayName("Unsafe decoded URI filename falls back to a generated name")
        void unsafeDecodedFilenameFallsBack() {
            Download decoded = new Download(URI.create("https://example.com/%2E%2E"));

            assertTrue(decoded.getName().startsWith("download_"));
        }

        @Test
        @DisplayName("Should generate default name when URI has no filename")
        void shouldGenerateDefaultNameWhenUriHasNoFilename() {
            URI uriWithoutFilename = URI.create("https://example.com/");
            Download downloadWithoutFilename = new Download(uriWithoutFilename);

            assertTrue(downloadWithoutFilename.getName().startsWith("download_"));
            assertTrue(downloadWithoutFilename.getName().length() > 9); // "download_" + 8 chars
        }

        @Test
        @DisplayName("Should create torrent download")
        void shouldCreateTorrentDownload() {
            Path torrentFile = Paths.get("/tmp/test.torrent");
            Path destination = Paths.get("/tmp/downloads");

            Download torrentDownload = Download.fromTorrent(torrentFile, destination);

            assertEquals("test.torrent", torrentDownload.getName());
            assertEquals(Download.Type.ARIA2, torrentDownload.getType());
            assertEquals(destination, torrentDownload.getDestination());
            assertNotNull(torrentDownload.getId());
        }

        @Test
        @DisplayName("Should initialize with default values")
        void shouldInitializeWithDefaultValues() {
            Download newDownload = new Download();

            assertNotNull(newDownload.getId());
            assertEquals(Download.Status.CREATED, newDownload.getStatus());
            assertNotNull(newDownload.getCreatedAt());
            assertTrue(newDownload.getMirrors().isEmpty());
            assertEquals(0, newDownload.getSize());
            assertEquals(0, newDownload.getDownloaded());
            assertEquals(0.0f, newDownload.getProgress());
            assertEquals(0.0f, newDownload.getSpeed());
            assertNull(newDownload.getStartedAt());
            assertNull(newDownload.getCompletedAt());
            assertNull(newDownload.getErrorMessage());
        }
    }

    @Nested
    @DisplayName("Status Management Tests")
    class StatusManagementTests {

        @Test
        @DisplayName("Should update timestamps when status changes")
        void shouldUpdateTimestampsWhenStatusChanges() {
            Instant before = Instant.now().minus(1, ChronoUnit.SECONDS);

            // Start download
            download.setStatus(Download.Status.DOWNLOADING);
            assertNotNull(download.getStartedAt());
            assertTrue(download.getStartedAt().isAfter(before));

            // Complete download
            Instant beforeCompletion = Instant.now().minus(1, ChronoUnit.SECONDS);
            download.setStatus(Download.Status.COMPLETED);
            assertNotNull(download.getCompletedAt());
            assertTrue(download.getCompletedAt().isAfter(beforeCompletion));
        }

        @Test
        @DisplayName("Should not update start time if already set")
        void shouldNotUpdateStartTimeIfAlreadySet() {
            download.setStatus(Download.Status.DOWNLOADING);
            Instant firstStartTime = download.getStartedAt();

            // Change to another status and back
            download.setStatus(Download.Status.PAUSED);
            download.setStatus(Download.Status.DOWNLOADING);

            assertEquals(firstStartTime, download.getStartedAt());
        }

        @Test
        @DisplayName("Should not update completion time if already set")
        void shouldNotUpdateCompletionTimeIfAlreadySet() {
            download.setStatus(Download.Status.COMPLETED);
            Instant firstCompletionTime = download.getCompletedAt();

            // Change status multiple times
            download.setStatus(Download.Status.PAUSED);
            download.setStatus(Download.Status.COMPLETED);

            assertEquals(firstCompletionTime, download.getCompletedAt());
        }

        @ParameterizedTest
        @EnumSource(Download.Status.class)
        @DisplayName("Should handle all status values")
        void shouldHandleAllStatusValues(Download.Status status) {
            assertDoesNotThrow(() -> download.setStatus(status));
            assertEquals(status, download.getStatus());
        }
    }

    @Nested
    @DisplayName("Progress Management Tests")
    class ProgressManagementTests {

        @Test
        @DisplayName("Should calculate progress from size and downloaded bytes")
        void shouldCalculateProgressFromSizeAndDownloadedBytes() {
            download.setSize(1000);
            download.setDownloaded(250);

            assertEquals(25.0f, download.getProgress(), 0.01f);
        }

        @Test
        @DisplayName("Should update progress when size changes")
        void shouldUpdateProgressWhenSizeChanges() {
            download.setDownloaded(500);
            download.setSize(1000);

            assertEquals(50.0f, download.getProgress(), 0.01f);

            download.setSize(2000);
            assertEquals(25.0f, download.getProgress(), 0.01f);
        }

        @Test
        @DisplayName("Should update progress when downloaded bytes change")
        void shouldUpdateProgressWhenDownloadedBytesChange() {
            download.setSize(1000);
            download.setDownloaded(300);

            assertEquals(30.0f, download.getProgress(), 0.01f);

            download.setDownloaded(700);
            assertEquals(70.0f, download.getProgress(), 0.01f);
        }

        @Test
        @DisplayName("Should handle zero size gracefully")
        void shouldHandleZeroSizeGracefully() {
            download.setSize(0);
            download.setDownloaded(100);

            assertEquals(0.0f, download.getProgress());
        }

        @Test
        @DisplayName("Should handle progress over 100%")
        void shouldHandleProgressOver100Percent() {
            download.setSize(1000);
            download.setDownloaded(1500);

            assertEquals(150.0f, download.getProgress(), 0.01f);
        }
    }

    @Nested
    @DisplayName("Mirror Management Tests")
    class MirrorManagementTests {

        @Test
        @DisplayName("Should add and remove mirrors")
        void shouldAddAndRemoveMirrors() throws Exception {
            URI mirror1 = URI.create("https://mirror1.com/file.zip");
            URI mirror2 = URI.create("https://mirror2.com/file.zip");

            download.addMirror(mirror1);
            download.addMirror(mirror2);

            List<URI> mirrors = download.getMirrors();
            assertEquals(2, mirrors.size());
            assertTrue(mirrors.contains(mirror1));
            assertTrue(mirrors.contains(mirror2));

            download.removeMirror(mirror1);
            mirrors = download.getMirrors();
            assertEquals(1, mirrors.size());
            assertFalse(mirrors.contains(mirror1));
            assertTrue(mirrors.contains(mirror2));
        }

        @Test
        @DisplayName("Should return defensive copy of mirrors")
        void shouldReturnDefensiveCopyOfMirrors() throws Exception {
            URI mirror = URI.create("https://mirror.com/file.zip");
            download.addMirror(mirror);

            List<URI> mirrors1 = download.getMirrors();
            List<URI> mirrors2 = download.getMirrors();

            assertNotSame(mirrors1, mirrors2);
            assertEquals(mirrors1, mirrors2);

            // Modifying returned list should not affect original
            mirrors1.clear();
            assertEquals(1, download.getMirrors().size());
        }
    }

    @Nested
    @DisplayName("Settings Management Tests")
    class SettingsManagementTests {

        @Test
        @DisplayName("Should initialize settings lazily")
        void shouldInitializeSettingsLazily() {
            Download newDownload = new Download();
            newDownload.setType(Download.Type.ARIA2);

            // Settings should be null initially
            // But accessing them should initialize them
            DownloadSettings settings = newDownload.getSettings();
            assertNotNull(settings);
        }

        @Test
        @DisplayName("Should set and get connections")
        void shouldSetAndGetConnections() {
            download.setConnections(8);
            assertEquals(8, download.getConnections());
        }

        @Test
        @DisplayName("Should set and get proxy settings")
        void shouldSetAndGetProxySettings() {
            download.setUseProxy(true);
            download.setProxyAddress("http://proxy:8080");

            assertTrue(download.isUseProxy());
            assertEquals("http://proxy:8080", download.getProxyAddress());
        }

        @Test
        @DisplayName("Should support method chaining for settings")
        void shouldSupportMethodChainingForSettings() {
            Download result = download
                    .setConnections(4)
                    .setUseProxy(true)
                    .setProxyAddress("http://proxy:3128");

            assertSame(download, result);
            assertEquals(4, download.getConnections());
            assertTrue(download.isUseProxy());
            assertEquals("http://proxy:3128", download.getProxyAddress());
        }

        @Test
        @DisplayName("Should handle option setting and getting")
        void shouldHandleOptionSettingAndGetting() {
            download.setOption("connections", "6");
            download.setOption("use-proxy", "true");
            download.setOption("proxy-address", "http://test:9000");
            download.setOption("custom-option", "custom-value");

            assertEquals(6, download.getConnections());
            assertTrue(download.isUseProxy());
            assertEquals("http://test:9000", download.getProxyAddress());

            Map<String, String> options = download.getOptions();
            assertEquals("custom-value", options.get("custom-option"));
        }

        @Test
        @DisplayName("Should handle invalid option values gracefully")
        void shouldHandleInvalidOptionValuesGracefully() {
            int originalConnections = download.getConnections();

            // Invalid number should not crash
            assertDoesNotThrow(() -> download.setOption("connections", "invalid-number"));

            // Should keep original value
            assertEquals(originalConnections, download.getConnections());
        }

        @Test
        @DisplayName("Should initialize settings with factory")
        void shouldInitializeSettingsWithFactory() {
            Download newDownload = new Download();
            newDownload.setType(Download.Type.ARIA2);

            DownloadSettingsFactory factory = new DownloadSettingsFactory(new GlobalSettings());
            newDownload.initSettings(factory);

            assertNotNull(newDownload.getSettings());
        }

        @Test
        @DisplayName("Should not reinitialize settings if already set")
        void shouldNotReinitializeSettingsIfAlreadySet() {
            DownloadSettings originalSettings = download.getSettings();

            // Try to initialize again
            DownloadSettingsFactory factory = new DownloadSettingsFactory(new GlobalSettings());
            download.initSettings(factory);

            assertSame(originalSettings, download.getSettings());
        }
    }


    @Nested
    @DisplayName("Thread Safety Tests")
    class ThreadSafetyTests {

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("Should handle concurrent access to status")
        void shouldHandleConcurrentAccessToStatus() throws InterruptedException {
            int threadCount = 10;
            int operationsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            // Launch threads that modify status concurrently
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            Download.Status status = (threadId % 2 == 0)
                                ? Download.Status.DOWNLOADING
                                : Download.Status.PAUSED;
                            download.setStatus(status);

                            // Also test reading
                            Download.Status currentStatus = download.getStatus();
                            assertNotNull(currentStatus);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

            // Verify download is in a valid state
            assertNotNull(download.getStatus());
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("Should handle concurrent access to progress")
        void shouldHandleConcurrentAccessToProgress() throws InterruptedException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            // Set initial size
            download.setSize(10000);

            // Launch threads that modify progress concurrently
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < 50; j++) {
                            long downloaded = (threadId * 50 + j) * 10;
                            download.setDownloaded(downloaded);

                            // Read progress
                            float progress = download.getProgress();
                            assertTrue(progress >= 0);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

            // Verify download is in a valid state
            assertTrue(download.getProgress() >= 0);
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("Should handle concurrent access to mirrors")
        void shouldHandleConcurrentAccessToMirrors() throws InterruptedException {
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            // Launch threads that modify mirrors concurrently
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < 20; j++) {
                            URI mirror = URI.create("https://mirror" + threadId + "-" + j + ".com/file.zip");
                            download.addMirror(mirror);

                            // Sometimes remove a mirror
                            if (j > 5 && j % 3 == 0) {
                                URI toRemove = URI.create("https://mirror" + threadId + "-" + (j-5) + ".com/file.zip");
                                download.removeMirror(toRemove);
                            }

                            // Read mirrors
                            List<URI> mirrors = download.getMirrors();
                            assertNotNull(mirrors);
                        }
                    } catch (Exception e) {
                        // URI creation might fail, which is ok for this test
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

            // Verify mirrors list is valid
            assertNotNull(download.getMirrors());
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("Should handle concurrent access to settings")
        void shouldHandleConcurrentAccessToSettings() throws InterruptedException {
            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            // Launch threads that access settings concurrently
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < 30; j++) {
                            // Mix of read and write operations
                            if (j % 2 == 0) {
                                download.setConnections(threadId + j);
                                download.setUseProxy(threadId % 2 == 0);
                            } else {
                                int connections = download.getConnections();
                                boolean useProxy = download.isUseProxy();
                                DownloadSettings settings = download.getSettings();

                                assertTrue(connections > 0);
                                assertNotNull(settings);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

            // Verify settings are in a valid state
            assertNotNull(download.getSettings());
            assertTrue(download.getConnections() > 0);
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle null URI gracefully")
        void shouldHandleNullUriGracefully() {
            Download nullUriDownload = new Download();
            nullUriDownload.setUri(null);

            assertNull(nullUriDownload.getUri());
        }

        @Test
        @DisplayName("Should handle null destination gracefully")
        void shouldHandleNullDestinationGracefully() {
            download.setDestination(null);
            assertNull(download.getDestination());
        }

        @Test
        @DisplayName("Should handle null name gracefully")
        void shouldHandleNullNameGracefully() {
            download.setName(null);
            assertNull(download.getName());
        }

        @Test
        @DisplayName("Should handle empty name gracefully")
        void shouldHandleEmptyNameGracefully() {
            download.setName("");
            assertEquals("", download.getName());
        }

        @Test
        @DisplayName("Should handle negative size")
        void shouldHandleNegativeSize() {
            download.setSize(-100);
            assertEquals(-100, download.getSize());
            // Progress calculation should handle this gracefully
            download.setDownloaded(50);
            // With negative size, progress calculation might be unusual
            assertDoesNotThrow(() -> download.getProgress());
        }

        @Test
        @DisplayName("Should handle negative downloaded bytes")
        void shouldHandleNegativeDownloadedBytes() {
            download.setSize(1000);
            download.setDownloaded(-50);
            assertEquals(-50, download.getDownloaded());
            assertDoesNotThrow(() -> download.getProgress());
        }

        @Test
        @DisplayName("Should handle very large numbers")
        void shouldHandleVeryLargeNumbers() {
            long largeSize = Long.MAX_VALUE - 1000;
            long largeDownloaded = Long.MAX_VALUE - 2000;

            assertDoesNotThrow(() -> {
                download.setSize(largeSize);
                download.setDownloaded(largeDownloaded);
                download.getProgress();
            });

            assertEquals(largeSize, download.getSize());
            assertEquals(largeDownloaded, download.getDownloaded());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t", "\n"})
        @DisplayName("Should handle whitespace-only error messages")
        void shouldHandleWhitespaceOnlyErrorMessages(String errorMessage) {
            assertDoesNotThrow(() -> download.setErrorMessage(errorMessage));
            assertEquals(errorMessage, download.getErrorMessage());
        }

        @Test
        @DisplayName("Should handle very long error messages")
        void shouldHandleVeryLongErrorMessages() {
            String longMessage = "Error: " + "x".repeat(10000);

            assertDoesNotThrow(() -> download.setErrorMessage(longMessage));
            assertEquals(longMessage, download.getErrorMessage());
        }
    }

    @Nested
    @DisplayName("Name Safety Tests")
    class NameSafetyTests {

        @Test
        @DisplayName("Requested filename is distinct from engine-reported output artifacts")
        void requestedFilenameAndOutputArtifactsStayDistinct() {
            download.setDestination(testDestination);
            download.setRequestedFileName("chosen-name.zip");
            download.recordOutputPath(Path.of("engine-name.zip"));
            download.recordOutputPath(testDestination.resolve("engine-name.zip"));
            download.recordOutputPath(testDestination.resolve("engine-name.zip.sha256"));

            assertEquals("chosen-name.zip", download.getRequestedFileName());
            assertEquals(List.of(
                    testDestination.resolve("engine-name.zip").toAbsolutePath().normalize(),
                    testDestination.resolve("engine-name.zip.sha256").toAbsolutePath().normalize()),
                    download.getOutputPaths());
            assertEquals(testDestination.resolve("engine-name.zip").toAbsolutePath().normalize(),
                    download.getPrimaryOutputPath());
            assertThrows(UnsupportedOperationException.class,
                    () -> download.getOutputPaths().add(Path.of("unexpected")));
        }

        @Test
        @DisplayName("Blank requested filename restores engine-native naming")
        void blankRequestedFilenameClearsOverride() {
            download.setRequestedFileName("custom.zip");
            download.setRequestedFileName("   ");

            assertNull(download.getRequestedFileName());
            assertThrows(IllegalArgumentException.class,
                    () -> download.setRequestedFileName("../escape.zip"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "a/b",
            "../victim",
            "..",
            ".",
            "/etc/passwd",
            "sub\\dir",
            "a\\..\\b"
        })
        @DisplayName("Names with separators or directory references are rejected")
        void unsafeNamesAreRejected(String name) {
            assertThrows(IllegalArgumentException.class, () -> download.setName(name),
                    "name must be rejected: " + name);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "file.iso",
            "my video [1080p].mkv",
            "file..mp4",
            "..hidden",
            "a...b",
            "trailing."
        })
        @DisplayName("Legitimate file names keep working")
        void legitimateNamesAreAccepted(String name) {
            assertDoesNotThrow(() -> download.setName(name));
            assertEquals(name, download.getName());
        }

        @Test
        @DisplayName("Null and empty names remain permitted")
        void nullAndEmptyRemainPermitted() {
            assertDoesNotThrow(() -> download.setName(null));
            assertNull(download.getName());
            assertDoesNotThrow(() -> download.setName(""));
            assertEquals("", download.getName());
        }
    }

    @Nested
    @DisplayName("ToString and Object Methods Tests")
    class ObjectMethodsTests {

        @Test
        @DisplayName("Should provide meaningful toString representation")
        void shouldProvideMeaningfulToStringRepresentation() {
            download.setName("test-file.zip");
            download.setStatus(Download.Status.DOWNLOADING);
            download.setSize(1000);
            download.setDownloaded(250);

            String result = download.toString();

            assertTrue(result.contains("test-file.zip"));
            assertTrue(result.contains("DOWNLOADING"));
            assertTrue(result.contains("25.0%"));
            assertTrue(result.contains(download.getId()));
        }

        @Test
        @DisplayName("Should handle null values in toString")
        void shouldHandleNullValuesInToString() {
            Download emptyDownload = new Download();
            emptyDownload.setName(null);

            assertDoesNotThrow(() -> {
                String result = emptyDownload.toString();
                assertNotNull(result);
            });
        }
    }
}
