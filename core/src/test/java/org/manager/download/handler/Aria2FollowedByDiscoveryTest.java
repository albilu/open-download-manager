package org.manager.download.handler;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;

/**
 * tellStatus carries the GID graph (followedBy/following/belongTo) that
 * links a magnet's metadata download to its payload download. The handler
 * must discover those related GIDs from a status fixture and gate
 * completion on ALL of them.
 */
@DisplayName("Aria2 followedBy GID discovery from tellStatus fixtures")
class Aria2FollowedByDiscoveryTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initContext() {
        ApplicationContext.initialize();
    }

    private Aria2DownloadHandler newHandler() {
        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(tempDir);
        DownloadSettingsFactory settingsFactory = new DownloadSettingsFactory(globalSettings);
        ExecutorService executor = Executors.newCachedThreadPool();
        return new Aria2DownloadHandler(
                globalSettings,
                settingsFactory,
                executor,
                ApplicationContext.getToolManagerFactory());
    }

    private static Map<String, Object> status(String status, long completed, long total) {
        Map<String, Object> map = new HashMap<>();
        map.put("status", status);
        map.put("completedLength", String.valueOf(completed));
        map.put("totalLength", String.valueOf(total));
        map.put("downloadSpeed", "0");
        return map;
    }

    @Test
    @DisplayName("A completed metadata GID with followedBy children does not complete the download")
    void followedByChildrenAreTrackedAndGateCompletion() throws Exception {
        Aria2DownloadHandler handler = newHandler();
        try {
            Download download = new Download(new java.net.URI("magnet:?xt=urn:btih:0123456789abcdef0123"));
            handler.registerTrackedDownload(download, List.of("metaGid"));

            Map<String, Object> metadataComplete = status("complete", 10_000, 10_000);
            metadataComplete.put("followedBy", List.of("childGid1", "childGid2"));
            handler.processProgressUpdate(download.getId(), "metaGid", metadataComplete);

            assertEquals(2, handler.trackedGidsFor(download.getId()).size(),
                    "both followedBy children must be tracked after the metadata GID completes");
            assertTrue(handler.trackedGidsFor(download.getId()).containsAll(List.of("childGid1", "childGid2")));
            assertNotEquals(Download.Status.COMPLETED, download.getStatus(),
                    "the download must not complete while followedBy children are outstanding");

            Map<String, Object> child1Active = status("active", 100, 5_000);
            child1Active.put("belongsTo", "metaGid");
            handler.processProgressUpdate(download.getId(), "childGid1", child1Active);
            assertNotEquals(Download.Status.COMPLETED, download.getStatus());

            handler.processProgressUpdate(download.getId(), "childGid1", status("complete", 5_000, 5_000));
            assertNotEquals(Download.Status.COMPLETED, download.getStatus(),
                    "one remaining child must still hold the download open");

            handler.processProgressUpdate(download.getId(), "childGid2", status("complete", 5_000, 5_000));
            assertEquals(Download.Status.COMPLETED, download.getStatus(),
                    "the download completes only after every tracked GID completes");
            assertEquals(20_000, download.getSize(),
                    "reported size aggregates every tracked GID");
        } finally {
            handler.shutdown().join();
        }
    }

    @Test
    @DisplayName("A child's following/belongsTo reference never resurrects a retired parent GID")
    void followingReferenceNeverResurrectsRetiredParent() throws Exception {
        Aria2DownloadHandler handler = newHandler();
        try {
            Download download = new Download(new java.net.URI("magnet:?xt=urn:btih:fedcba9876543210fedc"));
            handler.registerTrackedDownload(download, List.of("parentGid"));

            Map<String, Object> parentComplete = status("complete", 2_000, 2_000);
            parentComplete.put("followedBy", List.of("childGid"));
            handler.processProgressUpdate(download.getId(), "parentGid", parentComplete);
            assertEquals(1, handler.trackedGidsFor(download.getId()).size(),
                    "only the followedBy child remains tracked after the parent retires");

            // The child's status links back to its parent via following and
            // belongsTo: both name an already-retired GID and must not
            // re-register it, or the download could never complete
            Map<String, Object> childActive = status("active", 1, 100);
            childActive.put("following", "parentGid");
            childActive.put("belongsTo", "parentGid");
            handler.processProgressUpdate(download.getId(), "childGid", childActive);
            assertEquals(1, handler.trackedGidsFor(download.getId()).size(),
                    "a retired parent named by following/belongsTo must not be re-tracked");
            assertNotEquals(Download.Status.COMPLETED, download.getStatus(),
                    "the child is still outstanding");

            handler.processProgressUpdate(download.getId(), "childGid", status("complete", 100, 100));
            assertEquals(Download.Status.COMPLETED, download.getStatus(),
                    "with the child complete and the parent not resurrected, the download completes");
        } finally {
            handler.shutdown().join();
        }
    }
}
