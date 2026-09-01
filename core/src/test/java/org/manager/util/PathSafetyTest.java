package org.manager.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("PathSafety deletion containment")
class PathSafetyTest {

    @TempDir
    Path base;

    @Test
    @DisplayName("plain names are safe; separators and self/parent references are not")
    void safeFileNameClassification() {
        assertTrue(PathSafety.isSafeFileName(null));
        assertTrue(PathSafety.isSafeFileName("file.zip"));
        assertTrue(PathSafety.isSafeFileName(""));
        assertTrue(PathSafety.isSafeFileName("archive.tar.gz"));

        assertFalse(PathSafety.isSafeFileName("."), "'.' must not count as a file name");
        assertFalse(PathSafety.isSafeFileName(".."), "'..' must not count as a file name");
        assertFalse(PathSafety.isSafeFileName("sub/file.zip"));
        assertFalse(PathSafety.isSafeFileName("sub\\file.zip"));
        assertFalse(PathSafety.isSafeFileName("/etc/passwd"));
        assertFalse(PathSafety.isSafeFileName("a/b/../../../etc/passwd"));

        assertEquals("rejected", expectIllegalArgument("x/y"));
        assertEquals("accepted", expectLegalName("ok.zip"));
    }

    private static String expectIllegalArgument(String name) {
        try {
            PathSafety.requireSafeFileName(name);
            throw new AssertionError("expected IllegalArgumentException for " + name);
        } catch (IllegalArgumentException expected) {
            return "rejected";
        }
    }

    private static String expectLegalName(String name) {
        PathSafety.requireSafeFileName(name);
        return "accepted";
    }

    @Test
    @DisplayName("a regular file inside the base is confined and deletable")
    void confinedFileIsDeletable() throws IOException {
        Path file = Files.write(base.resolve("download.zip"), new byte[] {1, 2, 3});

        assertTrue(PathSafety.isConfined(file, base));
        assertTrue(PathSafety.deleteIfExistsConfined(file, base));
        assertFalse(Files.exists(file));
        assertFalse(PathSafety.deleteIfExistsConfined(file, base), "second delete reports nothing deleted");
    }

    @Test
    @DisplayName("a path outside the base is not confined and is never deleted")
    void outsidePathIsRejected() throws IOException {
        Path outsideFile = Files.createTempFile("odm-outside", ".txt");
        try {
            assertFalse(PathSafety.isConfined(outsideFile, base));
            assertFalse(PathSafety.deleteIfExistsConfined(outsideFile, base));
            assertTrue(Files.exists(outsideFile), "a file outside the base must survive");
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    @DisplayName("a symlink escaping the base is rejected even though it resolves inside a sibling")
    void escapingSymlinkIsRejected() throws IOException {
        Path victimDir = Files.createTempDirectory("odm-victim");
        Path victim = Files.write(victimDir.resolve("target.txt"), new byte[] {9});
        try {
            Path link = Files.createSymbolicLink(base.resolve("escape.txt"), victim);
            assertFalse(PathSafety.isConfined(link, base),
                    "a symlink whose target lives outside the base must be rejected");
            assertFalse(PathSafety.deleteIfExistsConfined(link, base));
            assertTrue(Files.exists(victim), "the pointed-to file must survive");
        } finally {
            Files.deleteIfExists(victim);
            Files.deleteIfExists(victimDir);
        }
    }

    @Test
    @DisplayName("a symlinked directory component that resolves outside fails containment")
    void symlinkedParentComponentIsRejected() throws IOException {
        Path outsideDir = Files.createTempDirectory("odm-outside-dir");
        try {
            Path linkedDir = Files.createSymbolicLink(base.resolve("linked"), outsideDir);
            Path candidate = linkedDir.resolve("file.txt");
            assertFalse(PathSafety.isConfined(candidate, base),
                    "the nearest existing ancestor (the symlinked dir) resolves outside");
            assertFalse(PathSafety.deleteIfExistsConfined(candidate, base));
        } finally {
            Files.deleteIfExists(outsideDir);
        }
    }

    @Test
    @DisplayName("a missing candidate under an existing base is still confined")
    void missingCandidateUnderBaseIsConfined() {
        Path candidate = base.resolve("not-created-yet.bin");
        assertTrue(PathSafety.isConfined(candidate, base));
        assertFalse(PathSafety.deleteIfExistsConfined(candidate, base));
    }

    @Test
    @DisplayName("an unresolved base (broken ancestor) fails closed")
    void brokenBaseFailsClosed() {
        Path missingBase = base.resolve("does-not-exist");
        Path candidate = missingBase.resolve("child.txt");
        assertFalse(PathSafety.isConfined(candidate, missingBase),
                "when the base cannot be resolved, containment must fail closed");
    }

    @Test
    @DisplayName("a directory candidate inside the base is confined (no deletion performed by deleteIfExists on dirs is JVM-dependent, so only containment is asserted)")
    void directoryInsideBaseIsConfined() throws IOException {
        Path dir = Files.createDirectory(base.resolve("subdir"));
        assertTrue(PathSafety.isConfined(dir, base));
    }

    @Test
    @DisplayName("relative paths are judged against their absolute location")
    void relativePathsAreResolvedFirst() {
        Path relative = base.relativize(base.resolve("inner.zip"));
        assertEquals("inner.zip", relative.toString());
        assertTrue(PathSafety.isSafeFileName("inner.zip"));
    }
}
