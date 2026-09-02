package org.manager.download;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadRelocatorTest {

    @TempDir
    Path tempDir;

    @Test
    void movesPayloadResumeFilesAndRemapsModelThenCanRollback() throws Exception {
        Path oldDestination = Files.createDirectory(tempDir.resolve("old"));
        Path newDestination = tempDir.resolve("new");
        Path payload = Files.writeString(oldDestination.resolve("movie.mkv"), "partial");
        Path control = Files.writeString(Path.of(payload + ".aria2"), "resume");
        Path fragment = Files.writeString(Path.of(payload + ".part-Frag12"), "fragment");
        Path unrelated = Files.writeString(oldDestination.resolve("other.txt"), "keep");

        Download download = download(oldDestination, "movie.mkv", payload);
        DownloadRelocator.Relocation relocation =
                DownloadRelocator.relocate(download, newDestination);

        Path movedPayload = newDestination.resolve("movie.mkv").toAbsolutePath().normalize();
        assertEquals(newDestination.toAbsolutePath().normalize(), download.getDestination());
        assertEquals(List.of(movedPayload), download.getOutputPaths());
        assertEquals("partial", Files.readString(movedPayload));
        assertEquals("resume", Files.readString(Path.of(movedPayload + ".aria2")));
        assertEquals("fragment", Files.readString(Path.of(movedPayload + ".part-Frag12")));
        assertFalse(Files.exists(control));
        assertFalse(Files.exists(fragment));
        assertTrue(Files.exists(unrelated), "unrelated destination files must stay put");

        relocation.rollback(download);

        assertEquals(oldDestination.toAbsolutePath().normalize(), download.getDestination());
        assertEquals(List.of(payload.toAbsolutePath().normalize()), download.getOutputPaths());
        assertEquals("partial", Files.readString(payload));
        assertEquals("resume", Files.readString(control));
        assertEquals("fragment", Files.readString(fragment));
    }

    @Test
    void movesATorrentRootAsOneTreeAndKeepsExternalOutputsUntouched() throws Exception {
        Path oldDestination = Files.createDirectory(tempDir.resolve("torrent-old"));
        Path newDestination = tempDir.resolve("torrent-new");
        Path root = Files.createDirectories(oldDestination.resolve("release/sub"));
        Path first = Files.writeString(root.resolve("one.bin"), "one");
        Path second = Files.writeString(oldDestination.resolve("release/two.bin"), "two");
        Path external = Files.writeString(tempDir.resolve("external.bin"), "external");

        Download download = download(oldDestination, "release", first);
        download.setOutputPaths(List.of(first, second, external));
        DownloadRelocator.relocate(download, newDestination);

        assertEquals("one", Files.readString(newDestination.resolve("release/sub/one.bin")));
        assertEquals("two", Files.readString(newDestination.resolve("release/two.bin")));
        assertFalse(Files.exists(oldDestination.resolve("release")));
        assertEquals(external.toAbsolutePath().normalize(), download.getOutputPaths().get(2),
                "an engine path outside the old destination is never moved");
    }

    @Test
    void refusesToOverwriteAndLeavesSourceAndModelUntouched() throws Exception {
        Path oldDestination = Files.createDirectory(tempDir.resolve("conflict-old"));
        Path newDestination = Files.createDirectory(tempDir.resolve("conflict-new"));
        Path source = Files.writeString(oldDestination.resolve("archive.bin"), "source");
        Path target = Files.writeString(newDestination.resolve("archive.bin"), "existing");
        Download download = download(oldDestination, "archive.bin", source);

        assertThrows(java.io.IOException.class,
                () -> DownloadRelocator.relocate(download, newDestination));

        assertEquals("source", Files.readString(source));
        assertEquals("existing", Files.readString(target));
        assertEquals(oldDestination, download.getDestination());
        assertEquals(List.of(source.toAbsolutePath().normalize()), download.getOutputPaths());
    }

    private static Download download(Path destination, String name, Path output)
            throws Exception {
        Download download = new Download(URI.create("https://example.test/" + name));
        download.setDestination(destination);
        download.setName(name);
        download.recordOutputPath(output);
        return download;
    }
}
