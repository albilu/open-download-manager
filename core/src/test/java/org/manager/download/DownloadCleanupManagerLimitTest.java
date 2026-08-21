package org.manager.download;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

/**
 * Regression test for the max-downloads limit: exceeding the limit by one
 * download must remove exactly the surplus, oldest finished items — never
 * the entire non-active history.
 */
@DisplayName("enforceMaxDownloadsLimit prunes only the surplus, oldest items")
class DownloadCleanupManagerLimitTest {

    private static Download completed(String name, Instant completedAt) throws Exception {
        Download download = new Download(new URI("http://example.test/" + name + ".bin"));
        download.setName(name);
        return download;
    }

    @Test
    @DisplayName("Hitting the limit removes only the oldest surplus downloads")
    void removesOnlyOldestSurplusBeyondLimit() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        PaginatedDownloadRepository repository = new PaginatedDownloadRepository(settings);
        DownloadCleanupManager manager = new DownloadCleanupManager(repository, settings);

        Instant base = Instant.now();
        String[] names = {"oldest", "older", "mid", "recent", "newest"};
        for (int i = 0; i < names.length; i++) {
            Download download = completed(names[i], base.plusSeconds(i));
            repository.addDownload(download);
            repository.updateDownloadStatus(download, Download.Status.COMPLETED);
            download.setCompletedAt(base.plusSeconds(i));
        }
        Download active = completed("active-download", base.plusSeconds(100));
        repository.addDownload(active);
        repository.updateDownloadStatus(active, Download.Status.DOWNLOADING);

        // 6 total, limit 4 -> exactly 2 oldest completed items must go
        int removed = manager.enforceMaxDownloadsLimit(4);

        assertEquals(2, removed, "exactly the surplus count must be removed");
        assertEquals(4, repository.getTotalCount(), "limit must be enforced exactly");

        List<Download> remaining = repository.getAllDownloads(0, Integer.MAX_VALUE).getDownloads();
        List<String> remainingNames = remaining.stream().map(Download::getName).toList();
        assertFalse(remainingNames.contains("oldest"), "oldest item must be pruned first");
        assertFalse(remainingNames.contains("older"), "second-oldest item must be pruned second");
        assertTrue(remainingNames.contains("mid"), "recent history must survive");
        assertTrue(remainingNames.contains("newest"), "newest history must survive");
        assertTrue(remainingNames.contains("active-download"), "active downloads must never be pruned");
    }
}
