package org.manager.download;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

/**
 * Cursor-style pagination contract: offsets that are not multiples of the
 * page size must slice exactly [offset, offset+limit) — the old
 * offset/limit page-number translation returned whole pages and silently
 * duplicated or dropped rows for non-aligned offsets.
 */
@DisplayName("Repository offset-based pagination slices exactly")
class PaginatedDownloadRepositoryOffsetTest {

    @Test
    @DisplayName("Non-page-aligned offsets return the exact window")
    void nonAlignedOffsetsSliceExactly() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        PaginatedDownloadRepository repository = new PaginatedDownloadRepository(settings);

        for (int i = 0; i < 25; i++) {
            // Deterministic creation instants via the id+createdAt ctor
            Download download = new Download("off-" + i,
                    java.time.Instant.now().plusSeconds(i));
            download.setName("off-" + i);
            repository.addDownload(download);
        }

        // Offset 15, limit 10 → exactly items 15..24 (newest-first), i.e.
        // off-9 down to off-0
        List<Download> window = repository.getAllDownloadsByOffset(15, 10).getDownloads();
        assertEquals(10, window.size());
        assertEquals("off-9", window.get(0).getName(), "newest-first ordering must hold");
        assertEquals("off-0", window.get(9).getName());

        // Offset beyond the end → empty
        assertEquals(0, repository.getAllDownloadsByOffset(30, 10).getDownloads().size());

        // Overlapping windows must not duplicate or drop rows
        List<Download> a = repository.getAllDownloadsByOffset(0, 15).getDownloads();
        List<Download> b = repository.getAllDownloadsByOffset(15, 15).getDownloads();
        assertEquals(15, a.size());
        assertEquals(10, b.size());
        assertEquals(25, a.size() + b.size(), "two adjacent windows cover all items exactly once");
    }

    @Test
    @DisplayName("Ten thousand records remain complete and responsive in 500-row pages")
    void tenThousandRecordsPageWithoutLoss() {
        assertTimeout(Duration.ofSeconds(20), () -> {
            GlobalSettings settings = new GlobalSettings()
                    .setMaxDownloadsInMemory(10_000);
            PaginatedDownloadRepository repository =
                    new PaginatedDownloadRepository(settings);

            for (int i = 0; i < 10_000; i++) {
                Download download = new Download("scale-" + i,
                        Instant.EPOCH.plusMillis(i));
                download.setName("scale-" + i);
                repository.addDownload(download);
            }

            HashSet<String> ids = new HashSet<>();
            for (int offset = 0; offset < 10_000; offset += 500) {
                PaginatedDownloadRepository.DownloadPage page =
                        repository.getAllDownloadsByOffset(offset, 500);
                assertEquals(10_000, page.getTotalCount());
                assertEquals(500, page.getDownloads().size());
                page.getDownloads().forEach(download -> ids.add(download.getId()));
            }
            assertEquals(10_000, ids.size(),
                    "adjacent UI-sized pages must expose every record exactly once");
        });
    }
}
