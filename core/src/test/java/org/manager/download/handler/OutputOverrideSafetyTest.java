package org.manager.download.handler;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputOverrideSafetyTest {

    private static final class ProbeHandler extends AbstractDownloadHandler {
        ProbeHandler() {
            super(null, null, null);
        }

        void prepareOutput(Download download) {
            overrideOutputPath(download);
        }

        @Override public Download.Type getSupportedType() { return Download.Type.ARIA2; }
        @Override protected void doInitialize() { }
        @Override protected void doShutdown() { }
        @Override public CompletableFuture<String> startDownload(Download download) {
            return CompletableFuture.completedFuture("probe");
        }
        @Override public CompletableFuture<Void> pauseDownload(Download download) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> resumeDownload(Download download) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> changeSettings(Download download) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void overrideNeverDeletesExistingBytesBeforeEngineAcceptance(@TempDir Path destination)
            throws Exception {
        byte[] original = "resumable-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(destination.resolve("archive.bin"), original);
        Download download = new Download(URI.create("https://example.test/archive.bin"));
        download.setDestination(destination);
        download.setOverrideOutputPath(true);

        new ProbeHandler().prepareOutput(download);

        assertArrayEquals(original, Files.readAllBytes(destination.resolve("archive.bin")));
        assertEquals("archive.bin", download.getName());
    }

    @Test
    void nonOverrideStillChoosesUniqueName(@TempDir Path destination) throws Exception {
        Files.writeString(destination.resolve("archive.bin"), "existing");
        Download download = new Download(URI.create("https://example.test/archive.bin"));
        download.setDestination(destination);
        download.setOverrideOutputPath(false);

        new ProbeHandler().prepareOutput(download);

        assertEquals("archive.bin_1", download.getName());
        assertEquals("existing", Files.readString(destination.resolve("archive.bin")));
    }
}
