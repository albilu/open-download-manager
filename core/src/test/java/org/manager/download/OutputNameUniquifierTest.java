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
}
