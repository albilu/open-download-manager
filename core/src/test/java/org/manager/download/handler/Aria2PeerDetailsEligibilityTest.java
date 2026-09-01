package org.manager.download.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import org.aria2.Aria2Client.Aria2RpcException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.download.Download;

@DisplayName("Aria2 peer detail eligibility")
class Aria2PeerDetailsEligibilityTest {

    @Test
    @DisplayName("ordinary HTTP downloads never trigger aria2.getPeers")
    void ordinaryHttpDownloadDoesNotSupportPeerDetails() {
        Download download = new Download(URI.create("https://example.test/archive.iso"));
        download.setGid("0123456789abcdef");

        assertEquals(Download.Protocol.HTTPS, download.getProtocol());
        assertFalse(Aria2DownloadHandler.supportsPeerDetails(download));
    }

    @Test
    @DisplayName("magnet, torrent descriptor, and discovered info hash downloads support peers")
    void bitTorrentDownloadsSupportPeerDetails() {
        Download magnet = new Download(URI.create(
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        Download remoteTorrent = new Download(URI.create("https://example.test/file.torrent"));
        Download localTorrent = Download.fromTorrent(
                Path.of("/tmp/example.torrent"), Path.of("/tmp"));
        Download discovered = new Download(URI.create("https://example.test/download?id=42"));
        discovered.setInfoHash("0123456789abcdef0123456789abcdef01234567");

        assertEquals(Download.Protocol.MAGNET, magnet.getProtocol());
        assertEquals(Download.Protocol.TORRENT, remoteTorrent.getProtocol());
        assertEquals(Download.Protocol.TORRENT, localTorrent.getProtocol());
        assertEquals(Download.Protocol.TORRENT, discovered.getProtocol());
        assertTrue(Aria2DownloadHandler.supportsPeerDetails(magnet));
        assertTrue(Aria2DownloadHandler.supportsPeerDetails(remoteTorrent));
        assertTrue(Aria2DownloadHandler.supportsPeerDetails(localTorrent));
        assertTrue(Aria2DownloadHandler.supportsPeerDetails(discovered));
    }

    @Test
    @DisplayName("only aria2's no-peer-data response is an expected empty state")
    void onlyNoPeerDataResponseIsExpected() {
        assertTrue(Aria2DownloadHandler.isNoPeerDataResponse(
                new Aria2RpcException(1, "No peer data is available for GID#0123456789abcdef")));
        assertFalse(Aria2DownloadHandler.isNoPeerDataResponse(
                new Aria2RpcException(1, "Unauthorized")));
        assertFalse(Aria2DownloadHandler.isNoPeerDataResponse(null));
    }
}
