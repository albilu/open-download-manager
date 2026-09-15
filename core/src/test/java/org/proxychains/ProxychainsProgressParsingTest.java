package org.proxychains;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.download.Download;

@DisplayName("Proxychains aria2 progress parsing")
class ProxychainsProgressParsingTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "[#abc123 1.0MiB/2.0MiB(50%) CN:4 DL:512KiB ETA:2s]|4",
        "[#abc123 1024B/2048B(50%) CN:2 DL:512B]|2",
        "[#abc123 SEED(1.5) CN:3 SD:2 UL:1.5MiB(2.0)]|3",
        "[#abc123 0B/0B CN:1 DL:0B]|1",
        "[#abc123 SEED(0.0) CN:0 SD:0 UL:0B(0)]|0"
    })
    void reportsActualConnectionsForAllTransferStates(String line, int count) {
        Download download = new Download(java.net.URI.create("http://example.invalid/file"));
        download.setConnections(16);
        download.setConnectionCount(9);

        new ProxychainsClient.ConnectionProgress().update(line, download);

        assertEquals(count, download.getConnectionCount());
        assertEquals(16, download.getConnections(), "telemetry must not change the configured limit");
    }

    @Test
    void missingAndMalformedCountsDoNotReplaceTheLastObservation() {
        Download download = new Download(java.net.URI.create("http://example.invalid/file"));
        var connections = new ProxychainsClient.ConnectionProgress();
        connections.update("[#abc123 0B/0B CN:4 DL:0B]", download);
        for (String line : new String[] {
                "[#abc123 1MiB/2MiB(50%) DL:512KiB]", "FILE: CN:10",
                "[#abc123 0B/0B CN:-1 DL:0B]", "[#abc123 0B/0B CN:2.5 DL:0B]",
                "[#abc123 0B/0B CN:999999999999999999999 DL:0B]"}) {
            connections.update(line, download);
            assertEquals(4, download.getConnectionCount(), line);
        }
    }

    @Test
    void summariesAggregateDistinctGidsAndDiscardRetiredGids() {
        Download download = new Download(java.net.URI.create("http://example.invalid/file"));
        var connections = new ProxychainsClient.ConnectionProgress();
        connections.update(" *** Download Progress Summary as of Sun Sep 13 *** ", download);
        connections.update("[#abc123 0B/0B CN:2 DL:0B]", download);
        connections.update("[#def456 SEED(1.5) CN:3 SD:2 UL:10B]", download);
        connections.update("", download);
        assertEquals(5, download.getConnectionCount());

        connections.update("[#abc123 0B/0B CN:2 DL:0B]", download);
        assertEquals(5, download.getConnectionCount(), "readout must not double count a summary GID");

        connections.update(" *** Download Progress Summary as of Sun Sep 13 *** ", download);
        connections.update("[#def456 SEED(1.5) CN:1 SD:1 UL:10B]", download);
        assertEquals(5, download.getConnectionCount(), "publish complete summary snapshots");
        connections.update("", download);
        assertEquals(1, download.getConnectionCount(), "completed metadata GIDs must disappear");
    }

    @Test
    void connectionOnlyUpdatesReachTheDownloadListener() {
        ProxychainsClient client = new ProxychainsClient();
        try {
            Download download = new Download(java.net.URI.create("http://example.invalid/file"));
            var listener = org.mockito.Mockito.mock(org.manager.download.DownloadListener.class);
            var connections = new ProxychainsClient.ConnectionProgress();

            client.processAria2Output("[#abc123 0B/0B CN:2 DL:0B]", download, listener, connections);
            assertEquals(2, download.getConnectionCount());
            org.mockito.Mockito.verify(listener).onDownloadProgress(download, 0f, 0L, 0L, 0f);

            org.mockito.Mockito.clearInvocations(listener);
            client.processAria2Output("[#abc123 SEED(1.5) CN:3 SD:2 UL:256KiB]",
                    download, listener, connections);
            assertEquals(3, download.getConnectionCount());
            org.mockito.Mockito.verify(listener).onDownloadProgress(download, 0f, 0L, 0L, 0f);
        } finally {
            client.shutdown();
        }
    }

    @Test
    @DisplayName("extracts UL while a torrent is downloading")
    void extractsDownloadingUploadSpeed() {
        String line = "[#abc123 1.0MiB/2.0MiB(50%) CN:4 SD:2 "
                + "DL:512KiB UL:256KiB ETA:2s]";

        assertEquals(256 * 1024f, ProxychainsClient.parseUploadSpeed(line));
    }

    @Test
    @DisplayName("extracts UL from seeding-only summaries")
    void extractsSeedingUploadSpeed() {
        String line = "[#abc123 SEED(1.5) CN:3 SD:2 UL:1.5MiB(2.0)]";

        assertEquals(1.5f * 1024 * 1024, ProxychainsClient.parseUploadSpeed(line));
    }

    @Test
    @DisplayName("distinguishes a missing UL field from a reported zero rate")
    void distinguishesMissingAndZeroUploadSpeed() {
        assertTrue(Float.isNaN(ProxychainsClient.parseUploadSpeed(
                "[#abc123 1MiB/2MiB(50%) CN:1 DL:512KiB ETA:2s]")));
        assertEquals(0f, ProxychainsClient.parseUploadSpeed(
                "[#abc123 SEED(0.0) CN:0 SD:0 UL:0B(0)]"));
    }
}
