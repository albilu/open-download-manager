package org.manager.util;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the descriptor staging ownership model: durable
 * collision-resistant copies, containment as the ownership marker, and
 * deletion restricted to ODM-managed staged files.
 */
@DisplayName("Descriptor Staging Tests")
class DescriptorStagingTest {

    @TempDir
    Path tempDir;

    private Path newStagingRoot() throws IOException {
        Path root = tempDir.resolve("descriptor-staging");
        Files.createDirectories(root);
        return root;
    }

    @Test
    @DisplayName("Repeated staging of one source reuses a single entry with the latest bytes")
    void repeatedStagingOfOneSourceReusesASingleEntry() throws IOException {
        Path root = newStagingRoot();
        Path sentinel = root.resolve("sentinel.torrent");
        byte[] sentinelBytes = "sentinel must survive".getBytes(StandardCharsets.UTF_8);
        Files.write(sentinel, sentinelBytes);

        Path source = tempDir.resolve("movie.torrent");
        Map<Path, byte[]> stagedCopies = new LinkedHashMap<>();
        for (int round = 0; round < 3; round++) {
            byte[] roundBytes = ("descriptor content round " + round).getBytes(StandardCharsets.UTF_8);
            Files.write(source, roundBytes);
            Path staged = DescriptorStaging.stageFile(source, root);
            stagedCopies.put(staged, roundBytes);
        }

        // Updated expectation: the staged name is deterministic per source,
        // so failed-and-retried rounds reuse (replace) ONE entry instead of
        // accumulating unbounded copies; the last round's bytes win
        assertEquals(1, stagedCopies.size(),
                "repeated staging of the same source must reuse one entry");
        Path staged = stagedCopies.keySet().iterator().next();
        assertTrue(Files.isRegularFile(staged), "staged copy must exist: " + staged);
        assertArrayEquals(read(staged), read(source),
                "the staged entry must hold the latest round's bytes");

        // ...and nothing pre-existing was replaced
        assertArrayEquals(sentinelBytes, read(sentinel), "an existing staged file must never be replaced");
        assertTrue(Files.exists(source), "staging must not consume the source");
    }

    @Test
    @DisplayName("Distinct sources stage into distinct entries")
    void distinctSourcesStageIntoDistinctEntries() throws IOException {
        Path root = newStagingRoot();
        Path first = tempDir.resolve("movie.torrent");
        Files.write(first, "descriptor a".getBytes(StandardCharsets.UTF_8));
        Path stagedFirst = DescriptorStaging.stageFile(first, root);

        Files.createDirectories(tempDir.resolve("elsewhere"));
        Path otherSource = tempDir.resolve("elsewhere").resolve("movie.torrent");
        Files.write(otherSource, "descriptor b".getBytes(StandardCharsets.UTF_8));
        Path stagedSecond = DescriptorStaging.stageFile(otherSource, root);

        assertNotEquals(stagedFirst, stagedSecond,
                "different source paths must stage into different entries");
        assertArrayEquals("descriptor a".getBytes(StandardCharsets.UTF_8), read(stagedFirst));
        assertArrayEquals("descriptor b".getBytes(StandardCharsets.UTF_8), read(stagedSecond));
    }

    @Test
    @DisplayName("Manual imports always receive unique managed copies")
    void manualImportsUseUniqueStagedCopies() throws Exception {
        Path source = tempDir.resolve("restored.torrent");
        Files.writeString(source, "descriptor");

        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", tempDir.toString()).execute(() -> {
            Path first = DescriptorStaging.stageManualFile(source);
            Path second = DescriptorStaging.stageManualFile(source);

            assertNotEquals(first, second);
            assertTrue(DescriptorStaging.isStagedPath(first, DescriptorStaging.stagingRoot()));
            assertTrue(DescriptorStaging.isStagedPath(second, DescriptorStaging.stagingRoot()));
            assertArrayEquals(read(source), read(first));
            assertArrayEquals(read(source), read(second));
        });
    }

    @Test
    @DisplayName("originalNameOf derives the watched name and rejects foreign names")
    void originalNameOfDerivesWatchedName() throws IOException {
        Path root = newStagingRoot();
        Path source = tempDir.resolve("movie.torrent");
        Files.write(source, "descriptor".getBytes(StandardCharsets.UTF_8));
        Path staged = DescriptorStaging.stageFile(source, root);

        assertEquals("movie.torrent", DescriptorStaging.originalNameOf(staged));
        assertEquals("orphan.torrent",
                DescriptorStaging.originalNameOf(root.resolve("0123456789abcdef-orphan.torrent")));
        assertEquals("meta.mkv.meta4",
                DescriptorStaging.originalNameOf(root.resolve("0123456789abcdef-meta.mkv.meta4")));
        assertNull(DescriptorStaging.originalNameOf(root.resolve("readme.txt-not-keyed")));
        assertNull(DescriptorStaging.originalNameOf(root.resolve("readme.txt")));
        assertNull(DescriptorStaging.originalNameOf(root.resolve("notakey-movie.torrent")));
        assertNull(DescriptorStaging.originalNameOf(root.resolve("0123456789abcdef-notes.txt")));
        assertNull(DescriptorStaging.originalNameOf(null));
    }

    @Test
    @DisplayName("Containment beneath the staging root is the ownership marker")
    void containmentIdentifiesOnlyPathsBeneathTheStagingRoot() throws IOException {
        Path root = newStagingRoot();

        Path source = tempDir.resolve("a.torrent");
        Files.write(source, "descriptor".getBytes(StandardCharsets.UTF_8));
        Path staged = DescriptorStaging.stageFile(source, root);
        assertTrue(DescriptorStaging.isStagedPath(staged, root), "staged copy must be recognized as ODM-managed");

        // Lexically different but resolving into the root still counts
        Path sneaky = root.getParent().resolve(root.getFileName().toString()).resolve("b.torrent");
        assertTrue(DescriptorStaging.isStagedPath(sneaky, root), "paths resolving beneath the root count");

        // Siblings, parents, and the root itself are user-owned
        assertFalse(DescriptorStaging.isStagedPath(root.resolveSibling("descriptor-staging-2").resolve("x.torrent"), root));
        assertFalse(DescriptorStaging.isStagedPath(tempDir.resolve("manual.torrent"), root));
        assertFalse(DescriptorStaging.isStagedPath(root, root), "the root itself is not a staged file");
        assertFalse(DescriptorStaging.isStagedPath(null, root));
        assertFalse(DescriptorStaging.isStagedPath(staged, null));
    }

    @Test
    @DisplayName("Deletion removes only ODM-managed staged files")
    void deleteIfStagedRemovesOnlyManagedFiles() throws IOException {
        Path root = newStagingRoot();
        Path source = tempDir.resolve("movie.torrent");
        Files.write(source, "descriptor".getBytes(StandardCharsets.UTF_8));
        Path staged = DescriptorStaging.stageFile(source, root);

        Path manual = tempDir.resolve("manually-selected.torrent");
        byte[] manualBytes = "user-owned descriptor".getBytes(StandardCharsets.UTF_8);
        Files.write(manual, manualBytes);

        assertTrue(DescriptorStaging.deleteIfStaged(staged, root), "the staged copy is ODM-managed");
        assertFalse(Files.exists(staged), "the staged copy must be gone after consumption");

        assertFalse(DescriptorStaging.deleteIfStaged(manual, root),
                "manually selected files are never auto-deleted");
        assertArrayEquals(manualBytes, read(manual), "the manual file must be untouched");
    }

    @Test
    @DisplayName("Collision-safe targets keep the plain name first and suffix on retry")
    void collisionSafeTargetProgression() {
        assertEquals(tempDir.resolve("movie.torrent"),
                DescriptorStaging.collisionSafeTarget(tempDir, "movie.torrent", 0));
        assertEquals(tempDir.resolve("movie-1.torrent"),
                DescriptorStaging.collisionSafeTarget(tempDir, "movie.torrent", 1));
        assertEquals(tempDir.resolve("movie-2.torrent"),
                DescriptorStaging.collisionSafeTarget(tempDir, "movie.torrent", 2));
        assertEquals(tempDir.resolve("archive-1"),
                DescriptorStaging.collisionSafeTarget(tempDir, "archive", 1));
        assertNotEquals(DescriptorStaging.collisionSafeTarget(tempDir, "movie.torrent", 0),
                DescriptorStaging.collisionSafeTarget(tempDir, "movie.torrent", 3));
    }

    @Test
    @DisplayName("A failed staging copy leaves no partial staged file behind")
    void failedStagingCopyLeavesNoPartialFileBehind() throws IOException {
        Path root = newStagingRoot();
        // A directory as the copy source: the staged output file is created
        // but reading the source fails, so the copy fails mid-staging
        Path directorySource = tempDir.resolve("directory-source.torrent");
        Files.createDirectories(directorySource);

        assertThrows(IOException.class, () -> DescriptorStaging.stageFile(directorySource, root),
                "staging a directory must fail");
        try (var entries = Files.list(root)) {
            assertEquals(0, entries.count(),
                    "no partial staged file may remain after a failed copy");
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new AssertionError("failed reading " + file, e);
        }
    }
}
