package org.manager.download;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.handler.DownloadHandlerFactory;
import static org.junit.jupiter.api.Assertions.*;

class ManagerInvalidUrlRecoveryTest {
    @TempDir Path directory;

    @Test
    void historySurvivesRestorationButInvalidSourcesCannotResume() throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", directory.resolve("config").toString())
                .and("XDG_DATA_HOME", directory.toString())
                .and("XDG_STATE_HOME", directory.toString()).execute(() -> {
            Download invalid = new Download(URI.create("https://example.com:70000/file.zip"));
            invalid.setName("invalid.zip");
            invalid.setStatus(Download.Status.PAUSED);
            Download mirrors = new Download(URI.create("https://example.com/file.zip"));
            mirrors.setName("mirrors.zip");
            mirrors.setStatus(Download.Status.PAUSED);
            mirrors.setFileSources("", List.of("mailto:someone@example.com"));
            Download finished = new Download(URI.create("legacy:removed-source"));
            finished.setName("historical.zip");
            finished.setStatus(Download.Status.COMPLETED);
            try (var store = new SqliteDownloadStateStore(directory.resolve("odm/odm-state.db"),
                    directory.resolve("odm/odm-state.json"), DownloadManagerImpl.createStateObjectMapper())) {
                store.save(List.of(invalid, mirrors, finished), Set.of());
            }
            DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
            var handler = new ManagerUrlAdmissionTest.RecordingHandler();
            DownloadManagerFactory.getContainer().getRequired(DownloadHandlerFactory.class)
                    .registerHandler(Download.Type.ARIA2, handler);
            try {
                manager.loadState().join();
                assertEquals(3, manager.getDownloadCount());
                assertEquals(Download.Status.COMPLETED, manager.getDownload(finished.getId()).getStatus());
                for (Download saved : List.of(invalid, mirrors)) {
                    Download restored = manager.getDownload(saved.getId());
                    assertThrows(CompletionException.class, () -> manager.resumeDownload(restored).join());
                    assertEquals(Download.Status.ERROR, restored.getStatus());
                }
                assertEquals(3, manager.getDownloadCount());
                assertEquals(0, handler.starts.get());
                assertEquals(0, handler.resumes.get());
                assertEquals(0, manager.getRunningDownloadCount());
            } finally {
                manager.getAllDownloads().forEach(download -> manager.cancelDownload(download, false).join());
                DownloadManagerFactory.shutdown();
            }
        });
    }
}
