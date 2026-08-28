package org.proxychains;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;

/**
 * cancelDownload(deleteFile=true) must only delete partial/control files
 * that genuinely live inside the download's destination: a symlinked file
 * name resolving outside, or a corrupted model name traversing out of the
 * directory, must never remove anything outside. A regular partial file in
 * the destination remains deletable.
 */
@DisplayName("ProxychainsClient cancellation deletes only confined partial files")
class ProxychainsDeleteSafetyTest {

    @TempDir
    Path tempDir;

    private ProxychainsClient client;

    @BeforeEach
    void setUp() {
        try {
            client = new ProxychainsClient("proxychains4", null);
        } catch (RuntimeException unavailable) {
            client = null;
        }
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
    }

    private boolean proxychainsAvailable() {
        return client != null;
    }

    /** Legacy/corrupted state can carry an unsafe name; engines must defend. */
    private static void forceName(Download download, String name) throws Exception {
        Field field = Download.class.getDeclaredField("name");
        field.setAccessible(true);
        field.set(download, name);
    }

    private Download downloadNamed(String name) throws Exception {
        Download download = new Download(new URI("http://example.test/f.bin"));
        download.setDestination(tempDir);
        forceName(download, name);
        return download;
    }

    @Test
    @DisplayName("A file name that is a symlink out of the destination is not deleted")
    void symlinkedFileNameIsNotDeleted() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(proxychainsAvailable());
        Path outside = Files.createTempDirectory("odm-proxychains-outside");
        Path victim = outside.resolve("victim.bin");
        Files.writeString(victim, "precious");
        Path link = tempDir.resolve("link.bin");
        Files.createSymbolicLink(link, victim);
        try {
            client.cancelDownload(downloadNamed("link.bin"), null, true);

            assertTrue(Files.exists(victim), "symlink target outside the destination must survive");
            assertTrue(Files.isSymbolicLink(link), "the escaping symlink itself must not be deleted");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(victim);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("A traversal name cannot delete outside the destination")
    void traversalNameIsRejected() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(proxychainsAvailable());
        Path victim = tempDir.resolve("..").toRealPath()
                .resolve("odm-proxychains-traversal-" + System.nanoTime() + ".bin");
        Files.writeString(victim, "precious");
        try {
            client.cancelDownload(downloadNamed("../" + victim.getFileName()), null, true);
            assertTrue(Files.exists(victim), "traversal name must never delete outside the destination");
        } finally {
            Files.deleteIfExists(victim);
        }
    }

    @Test
    @DisplayName("A regular partial file and its aria2 control file are deleted")
    void regularPartialFileIsDeleted() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(proxychainsAvailable());
        Path partial = tempDir.resolve("partial.bin");
        Path control = tempDir.resolve("partial.bin.aria2");
        Files.writeString(partial, "half");
        Files.writeString(control, "control");

        client.cancelDownload(downloadNamed("partial.bin"), null, true);

        assertFalse(Files.exists(partial), "a confined regular partial file must be deleted");
        assertFalse(Files.exists(control), "the confined aria2 control file must be deleted");
    }
}
