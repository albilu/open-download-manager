package org.manager.download.handler;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure unit tests for the deletion-eligibility rule: a candidate survives
 * only when, after resolving against the normalized absolute destination and
 * normalizing again, it is still beneath that destination.
 */
@DisplayName("yt-dlp output path eligibility")
class YtDlpOutputPathValidationTest {

    @TempDir
    Path tempDir;

    private Path normalizedDestination() {
        return tempDir.resolve("dl").toAbsolutePath().normalize();
    }

    @Test
    @DisplayName("A plain relative name resolves inside the destination")
    void relativeNameIsEligible() {
        Path eligible = YtDlpDownloadHandler.eligibleOutputPath(
                normalizedDestination(), "Episode 42 [1080p].mkv").orElseThrow();
        assertEquals(normalizedDestination().resolve("Episode 42 [1080p].mkv"), eligible);
    }

    @Test
    @DisplayName("A relative subfolder path resolves inside the destination")
    void relativeSubfolderIsEligible() {
        Path eligible = YtDlpDownloadHandler.eligibleOutputPath(
                normalizedDestination(), "channel/Episode 42 [1080p].mkv").orElseThrow();
        assertEquals(normalizedDestination().resolve("channel").resolve("Episode 42 [1080p].mkv"), eligible);
    }

    @Test
    @DisplayName("Inner dot-dot that stays inside the destination is eligible")
    void innerTraversalThatStaysInsideIsEligible() {
        Path eligible = YtDlpDownloadHandler.eligibleOutputPath(
                normalizedDestination(), "channel/../video.mkv").orElseThrow();
        assertEquals(normalizedDestination().resolve("video.mkv"), eligible);
    }

    @Test
    @DisplayName("An absolute path beneath the destination is eligible")
    void absoluteInsideDestinationIsEligible() {
        Path dest = normalizedDestination();
        Path eligible = YtDlpDownloadHandler.eligibleOutputPath(
                dest, dest.resolve("video.mkv").toString()).orElseThrow();
        assertEquals(dest.resolve("video.mkv"), eligible);
    }

    @Test
    @DisplayName("Null and blank candidates are rejected")
    void nullAndBlankAreRejected() {
        assertTrue(YtDlpDownloadHandler.eligibleOutputPath(normalizedDestination(), null).isEmpty());
        assertTrue(YtDlpDownloadHandler.eligibleOutputPath(normalizedDestination(), "").isEmpty());
        assertTrue(YtDlpDownloadHandler.eligibleOutputPath(normalizedDestination(), "   ").isEmpty());
    }

    @Test
    @DisplayName("An absolute path outside the destination is rejected")
    void absoluteOutsideDestinationIsRejected() {
        assertTrue(YtDlpDownloadHandler.eligibleOutputPath(
                normalizedDestination(), "/etc/passwd").isEmpty());
        assertTrue(YtDlpDownloadHandler.eligibleOutputPath(
                normalizedDestination(), tempDir.getParent().resolve("evil.mkv").toString()).isEmpty());
    }

    @Test
    @DisplayName("Traversal escaping the destination is rejected")
    void escapingTraversalIsRejected() {
        assertTrue(YtDlpDownloadHandler.eligibleOutputPath(
                normalizedDestination(), "../evil.mkv").isEmpty());
        assertTrue(YtDlpDownloadHandler.eligibleOutputPath(
                normalizedDestination(), "sub/../../evil.mkv").isEmpty());
        assertTrue(YtDlpDownloadHandler.eligibleOutputPath(
                normalizedDestination(), "../../evil.mkv").isEmpty());
    }

    @Test
    @DisplayName("A sibling directory sharing the destination's prefix is rejected")
    void prefixSiblingIsRejected() {
        // startsWith compares name elements: /tmp/.../dl-evil is not /tmp/.../dl
        assertTrue(YtDlpDownloadHandler.eligibleOutputPath(
                normalizedDestination(), tempDir.resolve("dl-evil").resolve("x.mkv").toString()).isEmpty());
    }

    @Test
    @DisplayName("A traversal absolute path that would land inside via .. stays eligible only if contained")
    void absoluteTraversalNormalization() {
        Path dest = normalizedDestination();
        // dest/sub/../video.mkv normalizes back inside
        Path eligible = YtDlpDownloadHandler.eligibleOutputPath(
                dest, dest.resolve("sub").resolve("..").resolve("video.mkv").toString()).orElseThrow();
        assertEquals(dest.resolve("video.mkv"), eligible);
        // dest/../dl2/video.mkv normalizes outside
        assertTrue(YtDlpDownloadHandler.eligibleOutputPath(
                dest, dest.getParent().resolve("dl2").resolve("video.mkv").toString()).isEmpty());
    }
}
