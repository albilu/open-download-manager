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
        assertFalse(OutputNameUniquifier.applyTo(first, List.of(), false));
        assertNull(first.getRequestedFileName());

        Download second = new Download(URI.create("https://host-b.test/v.mp4"));
        second.setDestination(destination);
        assertTrue(OutputNameUniquifier.applyTo(second, List.of(first), false));
        assertEquals("v_1.mp4", second.getRequestedFileName());
        assertEquals("v_1.mp4", second.getName());
    }

    @Test
    void applyToSeesOnDiskFiles(@TempDir Path destination) throws Exception {
        Files.writeString(destination.resolve("v.mp4"), "foreign file");
        Download download = new Download(URI.create("https://host-a.test/v.mp4"));
        download.setDestination(destination);
        assertTrue(OutputNameUniquifier.applyTo(download, List.of(), false));
        assertEquals("v_1.mp4", download.getRequestedFileName());
    }

    @Test
    void overrideClearsDiskButNeverAnotherLiveDownload(@TempDir Path destination) throws Exception {
        Files.writeString(destination.resolve("v.mp4"), "old output");
        Download first = new Download(URI.create("https://host-a.test/v.mp4"));
        first.setDestination(destination);
        first.setStatus(Download.Status.DOWNLOADING);

        Download second = new Download(URI.create("https://host-b.test/v.mp4"));
        second.setDestination(destination);
        assertTrue(OutputNameUniquifier.applyTo(second, List.of(first), true));
        assertEquals("v_1.mp4", second.getRequestedFileName());

        Download lone = new Download(URI.create("https://host-c.test/v.mp4"));
        lone.setDestination(destination);
        assertFalse(OutputNameUniquifier.applyTo(lone, List.of(), true));
        assertNull(lone.getRequestedFileName());
    }

    @Test
    void ownRecordedOutputNeverForcesRename(@TempDir Path destination) throws Exception {
        Path partial = Files.writeString(destination.resolve("v.mp4"), "own partial");
        Download download = new Download(URI.create("https://host-a.test/v.mp4"));
        download.setDestination(destination);
        download.recordOutputPath(partial);
        assertFalse(OutputNameUniquifier.applyTo(download, List.of(), false));
    }

    @Test
    void leadingWhitespaceBaseIsNormalized(@TempDir Path destination) throws Exception {
        Download download = new Download(URI.create("https://host-a.test/spaced.zip"));
        download.setDestination(destination);
        download.setName("  spaced.zip");
        assertFalse(OutputNameUniquifier.applyTo(download, List.of(), false));
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
        assertFalse(OutputNameUniquifier.applyTo(download, List.of(sibling), false));
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
        assertFalse(OutputNameUniquifier.applyTo(download, List.of(sibling), false));
        assertNull(download.getRequestedFileName());
    }

    @Test
    void nullDestinationAndBlankBaseReturnFalse(@TempDir Path destination) throws Exception {
        Download noDestination = new Download(URI.create("https://host-a.test/v.mp4"));
        assertFalse(OutputNameUniquifier.applyTo(noDestination, List.of(), false));

        Download blankBase = new Download();
        blankBase.setDestination(destination);
        assertFalse(OutputNameUniquifier.applyTo(blankBase, List.of(), false));

        Download fresh = new Download(URI.create("https://host-b.test/w.mp4"));
        fresh.setDestination(destination);
        assertFalse(OutputNameUniquifier.applyTo(fresh, null, false));
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
                                first, java.util.List.of(second), false));
                        return null;
                    },
                    () -> {
                        second.prepareUniquifiedOutput(() -> OutputNameUniquifier.applyTo(
                                second, java.util.List.of(first), false));
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
