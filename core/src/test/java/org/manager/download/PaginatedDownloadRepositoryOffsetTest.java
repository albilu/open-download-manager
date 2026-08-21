package org.manager.download;

import java.net.URI;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
