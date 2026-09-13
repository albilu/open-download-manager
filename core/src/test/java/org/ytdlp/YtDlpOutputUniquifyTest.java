package org.ytdlp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.DownloadManagerImpl;
import org.manager.download.handler.YtDlpDownloadHandler;
import org.manager.tools.ToolManagerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(90)
class YtDlpOutputUniquifyTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"false, AUTOMATIC", "true, AUTOMATIC", "false, MKV", "true, MKV"})
    void preservesExistingGeneratedOutputAndUsesNextFreeFinalName(boolean aria2,
            YtDlpSettings.ContainerProfile profile) throws Exception {
        try (var server = YtDlpLocalMediaServer.start(); var fixture = new HandlerFixture()) {
            String extension = profile == YtDlpSettings.ContainerProfile.MKV ? "mkv" : "mp4";
            Path existing = Files.writeString(directory.resolve("video-video." + extension), "original");
            Path numbered = Files.writeString(directory.resolve("video-video_1." + extension), "first copy");
            Path displayName = Files.writeString(directory.resolve("watch"), "unrelated");
            var settings = new YtDlpSettings().setFormat("best").setUseAria2c(aria2)
                    .setContainerProfile(profile);
            Download download = fixture.download(server.mediaUrl(), settings);

            fixture.start(download).get(45, TimeUnit.SECONDS);

            Path output = directory.resolve("video-video_2." + extension);
            assertTrue(Files.size(output) > 1_000);
            assertEquals("original", Files.readString(existing));
            assertEquals("first copy", Files.readString(numbered));
            assertEquals("unrelated", Files.readString(displayName));
            assertEquals(output, download.getPrimaryOutputPath());
            assertEquals(output.getFileName().toString(), download.getName());
            assertNull(download.getRequestedFileName(), "generated names must retain native extensions");
            assertTrue(settings.isAria2cContinue());
        }
    }

    @Test
    void simultaneousGeneratedNamesReserveDifferentOutputs() throws Exception {
        try (var firstServer = YtDlpLocalMediaServer.start();
                var secondServer = YtDlpLocalMediaServer.start();
                var fixture = new HandlerFixture()) {
            Download first = fixture.download(firstServer.mediaUrl(), new YtDlpSettings().setFormat("best"));
            Download second = fixture.download(secondServer.mediaUrl(), new YtDlpSettings().setFormat("best"));

            CompletableFuture.allOf(fixture.start(first), fixture.start(second)).get(45, TimeUnit.SECONDS);

            assertEquals(Set.of(directory.resolve("video-video.mp4"), directory.resolve("video-video_1.mp4")),
                    Set.of(first.getPrimaryOutputPath(), second.getPrimaryOutputPath()));
            assertArrayEquals(Files.readAllBytes(first.getPrimaryOutputPath()),
                    Files.readAllBytes(second.getPrimaryOutputPath()));
        }
    }

    @Test
    void audioExtractionPreservesExistingConvertedFile() throws Exception {
        Path original = Files.writeString(directory.resolve("video-video.mp3"), "original audio");
        try (var server = YtDlpLocalMediaServer.start(); var fixture = new HandlerFixture()) {
            Download download = fixture.download(server.mediaUrl(), new YtDlpSettings().setFormat("best")
                    .setExtractAudio(true).setAudioFormat("mp3"));
            fixture.start(download).get(45, TimeUnit.SECONDS);
            assertEquals(directory.resolve("video-video_1.mp3"), download.getPrimaryOutputPath());
            assertTrue(Files.size(download.getPrimaryOutputPath()) > 1_000);
            assertEquals("original audio", Files.readString(original));
        }
    }

    @Test
    void explicitNameWithPercentRemainsLiteral() throws Exception {
        Path original = Files.writeString(directory.resolve("100% complete.mp4"), "original");
        try (var server = YtDlpLocalMediaServer.start(); var fixture = new HandlerFixture()) {
            Download download = fixture.download(server.mediaUrl(), new YtDlpSettings().setFormat("best"));
            download.setRequestedFileName("100% complete.mp4");
            fixture.start(download).get(45, TimeUnit.SECONDS);
            assertEquals(directory.resolve("100% complete_1.mp4"), download.getPrimaryOutputPath());
            assertEquals("100% complete_1.mp4", download.getRequestedFileName());
            assertEquals("original", Files.readString(original));
        }
    }

    @Test
    void freshRecordDoesNotInheritAnotherRecordsReservation() throws Exception {
        Files.writeString(directory.resolve("video-video.mp4"), "original");
        var previousSettings = new YtDlpSettings().setFormat("best");
        previousSettings.setOutputNameCounter(7);
        previousSettings.setReservedOutputNames(java.util.List.of("video-video_7.mp4"));
        try (var server = YtDlpLocalMediaServer.start(); var fixture = new HandlerFixture()) {
            Download download = fixture.download(server.mediaUrl(), (YtDlpSettings) previousSettings.copy());
            fixture.start(download).get(45, TimeUnit.SECONDS);
            assertEquals(directory.resolve("video-video_1.mp4"), download.getPrimaryOutputPath());
            assertEquals(7, previousSettings.getOutputNameCounter());
            assertEquals("original", Files.readString(directory.resolve("video-video.mp4")));
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true,false", "true,false,false", "false,true,false", "false,false,false",
            "true,true,true", "true,false,true", "false,true,true", "false,false,true"})
    void archiveSkippingPreservesOutputsBeforeAnyReplacement(boolean uniquify, boolean override,
            boolean aria2) throws Exception {
        com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable(
                "XDG_STATE_HOME", directory.resolve("state").toString()).execute(() -> {
            try (var server = YtDlpLocalMediaServer.start();
                    var fixture = new HandlerFixture(uniquify, override, true)) {
                Download first = fixture.download(server.mediaUrl(), new YtDlpSettings()
                        .setFormat("best").setUseAria2c(aria2));
                first.setRequestedFileName("chosen.mp4");
                fixture.start(first).get(40, TimeUnit.SECONDS);
                Path original = first.getPrimaryOutputPath();
                byte[] content = Files.readAllBytes(original);
                Path sameInode = Files.createLink(directory.resolve("original.link"), original);

                Download repeated = fixture.download(server.mediaUrl(), new YtDlpSettings()
                        .setFormat("best").setUseAria2c(aria2));
                repeated.setRequestedFileName("chosen.mp4");
                fixture.start(repeated).get(30, TimeUnit.SECONDS);

                assertTrue(repeated.isArchiveOnlyCompletion());
                assertTrue(repeated.getOutputPaths().isEmpty());
                assertArrayEquals(content, Files.readAllBytes(original));
                assertTrue(Files.isSameFile(original, sameInode), "archive skips must not replace the original");
                assertFalse(Files.exists(directory.resolve("chosen_1.mp4")));
                assertTrue(((YtDlpSettings) repeated.getSettings()).getReservedOutputNames().isEmpty());

                // The filename does not define archive identity: generated names skip too.
                Download generated = fixture.download(server.mediaUrl(), new YtDlpSettings().setFormat("best"));
                fixture.start(generated).get(30, TimeUnit.SECONDS);
                assertTrue(generated.isArchiveOnlyCompletion());
                assertTrue(generated.getOutputPaths().isEmpty());
                assertFalse(Files.exists(directory.resolve("video-video.mp4")));
            }
        });
    }

    @Test
    void disablingArchiveSkippingAllowsAnotherUniqueCopy() throws Exception {
        com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable(
                "XDG_STATE_HOME", directory.resolve("state").toString()).execute(() -> {
            try (var server = YtDlpLocalMediaServer.start();
                    var fixture = new HandlerFixture(true, true, true)) {
                Download first = fixture.download(server.mediaUrl(), new YtDlpSettings().setFormat("best"));
                fixture.start(first).get(40, TimeUnit.SECONDS);
                fixture.globals.setProperty("ytdlp.skipDownloaded", "false");
                Download repeated = fixture.download(server.mediaUrl(), new YtDlpSettings().setFormat("best"));
                fixture.start(repeated).get(40, TimeUnit.SECONDS);
                assertFalse(repeated.isArchiveOnlyCompletion());
                assertEquals(directory.resolve("video-video_1.mp4"), repeated.getPrimaryOutputPath());
                assertArrayEquals(Files.readAllBytes(first.getPrimaryOutputPath()),
                        Files.readAllBytes(repeated.getPrimaryOutputPath()));
            }
        });
    }

    @Test
    void playlistReservesOnlyUnarchivedEntriesAndSkipsAgainAfterRestart() throws Exception {
        com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable(
                "XDG_STATE_HOME", directory.resolve("state").toString()).execute(() -> {
            try (var server = YtDlpLocalMediaServer.startPlaylist()) {
                Path firstOutput;
                byte[] firstContent;
                try (var fixture = new HandlerFixture(true, true, true)) {
                    Download first = fixture.download(server.playlistUrl(), new YtDlpSettings().setFormat("best")
                            .setNoPlaylist(false).setPlaylistItemSpec("1"));
                    fixture.start(first).get(40, TimeUnit.SECONDS);
                    firstOutput = first.getPrimaryOutputPath();
                    firstContent = Files.readAllBytes(firstOutput);

                    var settings = new YtDlpSettings().setFormat("best").setNoPlaylist(false);
                    Download rest = fixture.download(server.playlistUrl(), settings);
                    fixture.start(rest).get(40, TimeUnit.SECONDS);
                    assertFalse(rest.isArchiveOnlyCompletion());
                    assertTrue(rest.getOperationResults().stream()
                            .anyMatch(result -> result.message().contains("Skipped 1")));
                    assertEquals(1, settings.getReservedOutputNames().size(),
                            "archived playlist entries must not consume output reservations");
                    assertEquals(0, settings.getOutputNameCounter());
                    assertNotEquals(firstOutput, rest.getPrimaryOutputPath());
                    assertArrayEquals(firstContent, Files.readAllBytes(firstOutput));
                }
                try (var restarted = new HandlerFixture(true, true, true)) {
                    Download skipped = restarted.download(server.playlistUrl(), new YtDlpSettings().setFormat("best")
                            .setNoPlaylist(false));
                    restarted.start(skipped).get(40, TimeUnit.SECONDS);
                    assertTrue(skipped.isArchiveOnlyCompletion());
                    assertTrue(skipped.getOperationResults().stream()
                            .anyMatch(result -> result.message().contains("Skipped 2")));
                    assertTrue(skipped.getOutputPaths().isEmpty());
                    assertArrayEquals(firstContent, Files.readAllBytes(firstOutput));
                }
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resumeReusesReservedNameAndPartialFile(boolean restoreSession) throws Exception {
        Path original = Files.writeString(directory.resolve("video-video.mp4"), "original");
        try (var server = YtDlpLocalMediaServer.start();
                var fixture = new HandlerFixture()) {
            Download download = fixture.download(server.mediaUrl(), new YtDlpSettings().setFormat("best")
                    .setLimitRate(true).setRateLimit(16));
            CompletableFuture<Download> completion = fixture.start(download);
            Path partial = directory.resolve("video-video_1.mp4.part");
            org.awaitility.Awaitility.await().atMost(25, TimeUnit.SECONDS)
                    .until(() -> Files.exists(partial) && Files.size(partial) > 0);
            fixture.handler.pauseDownload(download).get(15, TimeUnit.SECONDS);
            assertEquals(Download.Status.PAUSED, download.getStatus());
            assertTrue(Files.size(partial) > 0);

            if (restoreSession) {
                var mapperFactory = DownloadManagerImpl.class.getDeclaredMethod("createStateObjectMapper");
                mapperFactory.setAccessible(true);
                var mapper = (com.fasterxml.jackson.databind.ObjectMapper) mapperFactory.invoke(null);
                Download restored = mapper.readValue(mapper.writeValueAsString(download), Download.class);
                fixture.handler.cancelDownload(download, false).get(15, TimeUnit.SECONDS);
                completion = fixture.start(restored);
            } else {
                fixture.handler.resumeDownload(download).get(15, TimeUnit.SECONDS);
            }

            Download completed = completion.get(45, TimeUnit.SECONDS);
            assertEquals(directory.resolve("video-video_1.mp4"), completed.getPrimaryOutputPath());
            assertFalse(Files.exists(directory.resolve("video-video_2.mp4")));
            assertFalse(Files.exists(partial));
            assertEquals("original", Files.readString(original));
        }
    }

    @Test
    void hlsDownloadsWithMatchingManifestNamesKeepDistinctMediaOutputs() throws Exception {
        try (var firstServer = YtDlpLocalMediaServer.startHls(directory.resolve("origin-a"));
                var secondServer = YtDlpLocalMediaServer.startHls(directory.resolve("origin-b"));
                var fixture = new HandlerFixture()) {
            Download first = fixture.download(firstServer.hlsUrl(), new YtDlpSettings());
            Download second = fixture.download(secondServer.hlsUrl(), new YtDlpSettings());
            CompletableFuture.allOf(fixture.start(first), fixture.start(second)).get(45, TimeUnit.SECONDS);
            assertEquals(Set.of(directory.resolve("master-master.mp4"), directory.resolve("master-master_1.mp4")),
                    Set.of(first.getPrimaryOutputPath(), second.getPrimaryOutputPath()));
            assertTrue(Files.size(first.getPrimaryOutputPath()) > 1_000);
            assertTrue(Files.size(second.getPrimaryOutputPath()) > 1_000);
            assertNull(first.getRequestedFileName());
            assertNull(second.getRequestedFileName());
        }
    }

    private final class HandlerFixture implements AutoCloseable {
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final Map<String, CompletableFuture<Download>> completions = new ConcurrentHashMap<>();
        private final YtDlpDownloadHandler handler;
        private final GlobalSettings globals;

        HandlerFixture() throws Exception {
            this(true, true, false);
        }

        HandlerFixture(boolean uniquify, boolean override, boolean skipDownloaded) throws Exception {
            globals = new GlobalSettings().setUniquifyOutputName(uniquify).setOverrideOutputPath(override);
            globals.setProperty("ytdlp.skipDownloaded", Boolean.toString(skipDownloaded));
            YtDlpToolManager tool = mock(YtDlpToolManager.class);
            when(tool.getToolPath()).thenReturn("yt-dlp");
            ToolManagerFactory tools = mock(ToolManagerFactory.class);
            when(tools.getYtDlpManager()).thenReturn(tool);
            YtDlpFactory.clearInstance();
            handler = new YtDlpDownloadHandler(globals, new DownloadSettingsFactory(globals), executor, tools);
            DownloadListener listener = mock(DownloadListener.class);
            doAnswer(invocation -> {
                Download download = invocation.getArgument(0);
                completions.get(download.getId()).complete(download);
                return null;
            }).when(listener).onDownloadComplete(any());
            doAnswer(invocation -> {
                Download download = invocation.getArgument(0);
                completions.get(download.getId()).completeExceptionally(
                        new AssertionError((String) invocation.getArgument(1)));
                return null;
            }).when(listener).onDownloadError(any(), anyString());
            handler.addDownloadListener(listener);
            handler.initialize().get(15, TimeUnit.SECONDS);
        }

        Download download(String url, YtDlpSettings settings) {
            Download download = new Download(URI.create(url));
            download.setType(Download.Type.YOUTUBE);
            download.setName("watch");
            download.setSettings(settings);
            download.setDestination(directory);
            completions.put(download.getId(), new CompletableFuture<>());
            return download;
        }

        CompletableFuture<Download> start(Download download) {
            return handler.startDownload(download).thenCompose(ignored -> completions.get(download.getId()));
        }

        @Override public void close() throws Exception {
            handler.shutdown().get(15, TimeUnit.SECONDS);
            YtDlpFactory.clearInstance();
            executor.close();
        }
    }
}
