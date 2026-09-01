package org.manager.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("XDG Trash")
class XdgTrashTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("A trashed file gets a restorable, URL-escaped trashinfo record")
    void writesPayloadAndTrashInfo() throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("source folder"))
                .resolve("release.meta4");
        Files.writeString(source, "metalink");
        Path root = tempDir.resolve("Trash");

        Path target = XdgTrash.moveToTrash(source, root);

        assertEquals(root.resolve("files/release.meta4"), target);
        assertFalse(Files.exists(source));
        assertEquals("metalink", Files.readString(target));
        String info = Files.readString(root.resolve("info/release.meta4.trashinfo"));
        assertTrue(info.contains("Path=" + source.toAbsolutePath().toUri().getRawPath()));
        assertTrue(info.contains("source%20folder"));
        assertTrue(info.contains("DeletionDate="));
    }

    @Test
    @DisplayName("Existing Trash entries are never replaced")
    void resolvesPayloadAndMetadataCollisions() throws Exception {
        Path root = tempDir.resolve("Trash");
        Files.createDirectories(root.resolve("files"));
        Files.createDirectories(root.resolve("info"));
        Files.writeString(root.resolve("files/movie.torrent"), "existing payload");
        Files.writeString(root.resolve("info/movie.torrent.trashinfo"), "existing metadata");
        Path source = Files.createDirectories(tempDir.resolve("incoming"))
                .resolve("movie.torrent");
        Files.writeString(source, "new payload");

        Path target = XdgTrash.moveToTrash(source, root);

        assertEquals(root.resolve("files/movie-1.torrent"), target);
        assertEquals("existing payload", Files.readString(root.resolve("files/movie.torrent")));
        assertEquals("existing metadata",
                Files.readString(root.resolve("info/movie.torrent.trashinfo")));
        assertEquals("new payload", Files.readString(target));
        assertTrue(Files.exists(root.resolve("info/movie-1.torrent.trashinfo")));
    }
}
