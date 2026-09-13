package org.manager.download;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OutputNameUniquifierTest {

    @Test
    void freeNamePassesThroughUntouched() {
        assertEquals("archive.zip",
                OutputNameUniquifier.uniquifiedName("archive.zip", name -> false));
    }

    @Test
    void takenNameGainsUnderscoreCounterBeforeExtension() {
        Set<String> taken = new HashSet<>(Set.of("archive.zip"));
        assertEquals("archive_1.zip",
                OutputNameUniquifier.uniquifiedName("archive.zip", taken::contains));
        taken.add("archive_1.zip");
        assertEquals("archive_2.zip",
                OutputNameUniquifier.uniquifiedName("archive.zip", taken::contains));
    }

    @Test
    void multiDotKeepsFullStem() {
        assertEquals("720p.h264.mp4_1.m3u8", OutputNameUniquifier.uniquifiedName(
                "720p.h264.mp4.m3u8", "720p.h264.mp4.m3u8"::equals));
    }

    @Test
    void extensionlessAndDotfiles() {
        assertEquals("README_1",
                OutputNameUniquifier.uniquifiedName("README", "README"::equals));
        assertEquals(".profile_1",
                OutputNameUniquifier.uniquifiedName(".profile", ".profile"::equals));
    }

    @Test
    void blankAndUnsafeNamesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OutputNameUniquifier.uniquifiedName("   ", name -> false));
        assertThrows(IllegalArgumentException.class,
                () -> OutputNameUniquifier.uniquifiedName("../escape.zip", name -> false));
    }

    @Test
    void applyToStampsOnlyOnCollision(@TempDir Path destination) throws Exception {
        Download first = new Download(URI.create("https://host-a.test/v.mp4"));
        first.setDestination(destination);
        assertFalse(OutputNameUniquifier.applyTo(first, List.of()));
        assertNull(first.getRequestedFileName());

        Download second = new Download(URI.create("https://host-b.test/v.mp4"));
        second.setDestination(destination);
        assertTrue(OutputNameUniquifier.applyTo(second, List.of(first)));
        assertEquals("v_1.mp4", second.getRequestedFileName());
        assertEquals("v_1.mp4", second.getName());
    }

    @Test
    void applyToSeesOnDiskFiles(@TempDir Path destination) throws Exception {
        Files.writeString(destination.resolve("v.mp4"), "foreign file");
        Download download = new Download(URI.create("https://host-a.test/v.mp4"));
        download.setDestination(destination);
        assertTrue(OutputNameUniquifier.applyTo(download, List.of()));
        assertEquals("v_1.mp4", download.getRequestedFileName());
    }

    @Test
    void existingAndLiveOutputsBothForceUniqueNames(@TempDir Path destination) throws Exception {
        Files.writeString(destination.resolve("v.mp4"), "old output");
        Download first = new Download(URI.create("https://host-a.test/v.mp4"));
        first.setDestination(destination);
        first.setStatus(Download.Status.DOWNLOADING);

        Download second = new Download(URI.create("https://host-b.test/v.mp4"));
        second.setDestination(destination);
        assertTrue(OutputNameUniquifier.applyTo(second, List.of(first)));
        assertEquals("v_1.mp4", second.getRequestedFileName());

        Download lone = new Download(URI.create("https://host-c.test/v.mp4"));
        lone.setDestination(destination);
        assertTrue(OutputNameUniquifier.applyTo(lone, List.of()));
        assertEquals("v_1.mp4", lone.getRequestedFileName());
    }

    @Test
    void ownRecordedOutputNeverForcesRename(@TempDir Path destination) throws Exception {
        Path partial = Files.writeString(destination.resolve("v.mp4"), "own partial");
        Download download = new Download(URI.create("https://host-a.test/v.mp4"));
        download.setDestination(destination);
        download.recordOutputPath(partial);
        assertFalse(OutputNameUniquifier.applyTo(download, List.of()));
    }

    @Test
    void leadingWhitespaceBaseIsNormalized(@TempDir Path destination) throws Exception {
        Download download = new Download(URI.create("https://host-a.test/spaced.zip"));
        download.setDestination(destination);
        download.setName("  spaced.zip");
        assertFalse(OutputNameUniquifier.applyTo(download, List.of()));
        assertEquals("  spaced.zip", download.getName());
        assertNull(download.getRequestedFileName());
    }

    @Test
    void counterOverflowThrows() {
        assertThrows(IllegalStateException.class,
                () -> OutputNameUniquifier.uniquifiedName("a.zip", taken -> true));
    }

    @Test
    void ignoresDifferentDestination(@TempDir Path destination, @TempDir Path otherDestination)
            throws Exception {
        Download sibling = new Download(URI.create("https://host-a.test/v.mp4"));
        sibling.setDestination(otherDestination);
        Download download = new Download(URI.create("https://host-b.test/v.mp4"));
        download.setDestination(destination);
        assertFalse(OutputNameUniquifier.applyTo(download, List.of(sibling)));
        assertNull(download.getRequestedFileName());
    }

    @ParameterizedTest
    @EnumSource(value = Download.Status.class, names = {"COMPLETED", "CANCELED", "ERROR"})
    void ignoresTerminalStatuses(Download.Status terminal, @TempDir Path destination)
            throws Exception {
        Download sibling = new Download(URI.create("https://host-a.test/v.mp4"));
        sibling.setDestination(destination);
        sibling.setStatus(terminal);
        Download download = new Download(URI.create("https://host-b.test/v.mp4"));
        download.setDestination(destination);
        assertFalse(OutputNameUniquifier.applyTo(download, List.of(sibling)));
        assertNull(download.getRequestedFileName());
    }

    @Test
    void siblingClaimWithLeadingWhitespaceIsNormalized(@TempDir Path destination) throws Exception {
        Download sibling = new Download(URI.create("https://x.test/a.zip"));
        sibling.setDestination(destination);
        sibling.setName("  a.zip");
        sibling.setStatus(Download.Status.DOWNLOADING);

        Download self = new Download(URI.create("https://x.test/a.zip"));
        self.setDestination(destination);
        self.setName("a.zip");

        assertTrue(OutputNameUniquifier.applyTo(self, List.of(sibling)));
        assertEquals("a_1.zip", self.getName());
    }

    @Test
    void nullDestinationAndBlankBaseReturnFalse(@TempDir Path destination) throws Exception {
        Download noDestination = new Download(URI.create("https://host-a.test/v.mp4"));
        assertFalse(OutputNameUniquifier.applyTo(noDestination, List.of()));

        Download blankBase = new Download();
        blankBase.setDestination(destination);
        assertFalse(OutputNameUniquifier.applyTo(blankBase, List.of()));

        Download fresh = new Download(URI.create("https://host-b.test/w.mp4"));
        fresh.setDestination(destination);
        assertFalse(OutputNameUniquifier.applyTo(fresh, null));
        assertNull(fresh.getRequestedFileName());
    }

    @Test
    void uniquifyPreparationRunsOnceAndRetriesAfterFailure() {
        Download download = new Download(URI.create("https://host-a.test/v.mp4"));
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        download.prepareUniquifiedOutput(runs::incrementAndGet);
        download.prepareUniquifiedOutput(runs::incrementAndGet);
        assertEquals(1, runs.get());

        Download failing = new Download(URI.create("https://host-b.test/v.mp4"));
        assertThrows(IllegalStateException.class, () ->
                failing.prepareUniquifiedOutput(() -> {
                    runs.incrementAndGet();
                    throw new IllegalStateException("boom");
                }));
        failing.prepareUniquifiedOutput(runs::incrementAndGet);
        assertEquals(3, runs.get());
    }

    @Test
    void mediaDisplayNameNeverBecomesALiteralOutput(@TempDir Path destination) throws Exception {
        Files.writeString(destination.resolve("watch"), "unrelated");
        Download download = mediaDownload(destination);
        assertFalse(OutputNameUniquifier.applyTo(download, List.of()));
        assertNull(download.getRequestedFileName());
    }

    @Test
    void mediaReservationProtectsPartialFilesAndEveryPlaylistEntry(@TempDir Path destination) throws Exception {
        Files.writeString(destination.resolve("second.mp4.part-Frag1"), "unrelated fragment");
        Files.writeString(destination.resolve("first_1.mp3"), "unrelated audio");
        Download download = mediaDownload(destination);
        OutputNameUniquifier.applyToMedia(download, List.of(), List.of("first.webm", "second.mp4"));
        var settings = (org.ytdlp.YtDlpSettings) download.getSettings();
        assertEquals(2, settings.getOutputNameCounter());
        assertEquals(List.of("first_2.webm", "second_2.mp4"), settings.getReservedOutputNames());
        assertNull(download.getRequestedFileName());
        assertTrue(download.getOutputPaths().isEmpty(), "reservations are not produced files or deletion authority");
    }

    @Test
    void pausedMediaReservationsRemainTakenBeforeAnyOutputExists(@TempDir Path destination) throws Exception {
        Download first = mediaDownload(destination);
        OutputNameUniquifier.applyToMedia(first, List.of(), List.of("clip.webm"));
        first.setStatus(Download.Status.PAUSED);
        Download second = mediaDownload(destination);
        OutputNameUniquifier.applyToMedia(second, List.of(first), List.of("clip.mp4"));
        assertEquals(List.of("clip_1.mp4"),
                ((org.ytdlp.YtDlpSettings) second.getSettings()).getReservedOutputNames());
    }

    @Test
    void scalarDownloadRespectsTheMediaReservationAfterConversion(@TempDir Path destination) {
        Download media = mediaDownload(destination);
        OutputNameUniquifier.applyToMedia(media, List.of(), List.of("clip.webm"));
        media.setStatus(Download.Status.PAUSED);
        Download file = new Download(URI.create("https://example.test/clip.mp3"));
        file.setDestination(destination);

        assertTrue(OutputNameUniquifier.applyTo(file, List.of(media)));
        assertEquals("clip_1.mp3", file.getRequestedFileName(),
                "a paused audio extraction still owns the converted output before it exists");
    }

    private static Download mediaDownload(Path destination) {
        Download download = new Download(URI.create("https://example.test/watch"));
        download.setType(Download.Type.YOUTUBE);
        download.setSettings(new org.ytdlp.YtDlpSettings());
        download.setDestination(destination);
        return download;
    }

    @Test
    void concurrentSameNameStartsAlwaysDiverge(@TempDir Path destination) throws Exception {
        Download first = new Download(URI.create("https://host-a.test/v.mp4"));
        first.setDestination(destination);
        Download second = new Download(URI.create("https://host-b.test/v.mp4"));
        second.setDestination(destination);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(java.util.List.of(
                    () -> {
                        first.prepareUniquifiedOutput(() -> OutputNameUniquifier.applyTo(
                                first, java.util.List.of(second)));
                        return null;
                    },
                    () -> {
                        second.prepareUniquifiedOutput(() -> OutputNameUniquifier.applyTo(
                                second, java.util.List.of(first)));
                        return null;
                    }));
            for (var result : results) {
                result.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertNotEquals(first.getName(), second.getName());
        // Serialized by the claim lock: the loser sees the winner's stamped
        // name, freeing the base — outcome is always {v.mp4, v_1.mp4} in some
        // order. Distinct names with exactly one suffix, deterministically.
        assertEquals(Set.of("v.mp4", "v_1.mp4"),
                new HashSet<>(Set.of(first.getName(), second.getName())));
    }
}
