package org.manager.download;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Create, start, pause, resume, cancel, and reconfigure individual
 * downloads, plus the per-download engine detail fetches (peers, files,
 * trackers) and the schedule gate consulted before any start.
 */
public interface DownloadOperations {

    /**
     * Creates a new download for the given URI.
     *
     * @param uri The URI to download
     * @param destination The destination directory (null for default location)
     * @return The created download
     */
    Download createDownload(URI uri, Path destination);

    /**
     * Creates a new torrent download.
     *
     * @param torrentFile Path to the torrent file
     * @param destination The destination directory (null for default location)
     * @return The created download
     */
    Download createTorrentDownload(Path torrentFile, Path destination);

    /**
     * Creates a new magnet download.
     *
     * @param magnetUri The magnet URI
     * @param destination The destination directory (null for default location)
     * @return The created download
     */
    Download createMagnetDownload(URI magnetUri, Path destination);

    /**
     * Creates a new metaLink download.
     *
     * @param metaLinkUri The metaLink URI
     * @param destination The destination directory (null for default location)
     * @return The created download
     */
    Download createMetaLinkDownload(URI metaLinkUri, Path destination);

    /**
     * Creates a new YouTube video download using yt-dlp.
     *
     * @param videoUrl The YouTube video URL
     * @param destination The destination directory (null for default location)
     * @param options Additional yt-dlp options
     * @return The created download
     */
    Download createYoutubeDownload(URI videoUrl, Path destination, Map<String, String> options);

    /**
     * Creates a new website scraping download using httrack.
     *
     * @param websiteUrl The website URL to scrape
     * @param destination The destination directory
     * @param options Additional httrack options
     * @return The created download
     */
    Download createWebsiteDownload(URI websiteUrl, Path destination, Map<String, String> options);

    /**
     * Adds a user-requested download to the queue and makes it eligible for
     * admission. The {@code ui.startAutomatically} setting does not apply to
     * explicit actions such as dialogs and imports.
     *
     * @param download The download to add
     * @return A future that completes when the download is added
     */
    CompletableFuture<Void> queueDownload(Download download);

    /**
     * Adds a download discovered by a background source such as clipboard or
     * folder monitoring. The {@code ui.startAutomatically} setting decides
     * whether it is immediately eligible for admission or held for an
     * explicit user start.
     *
     * @param download The background-discovered download to add
     * @return A future that completes when the download is added
     */
    CompletableFuture<Void> queueDownloadFromBackgroundSource(Download download);

    /**
     * Places a download in the visible queue without making it eligible for
     * automatic admission. An explicit {@link #startDownload(Download)} or
     * {@link #resumeDownload(Download)} releases the manual-start hold.
     *
     * @param download The download to queue for an explicit user start
     * @return A future that completes when the queued state is published
     */
    CompletableFuture<Void> queueDownloadForManualStart(Download download);

    /**
     * Starts a download immediately.
     *
     * @param download The download to start
     * @return A future that completes when the download is started
     */
    CompletableFuture<Void> startDownload(Download download);

    /**
     * Pauses an active download.
     *
     * @param download The download to pause
     * @return A future that completes when the download is paused
     */
    CompletableFuture<Void> pauseDownload(Download download);

    /**
     * Resumes a paused download.
     *
     * @param download The download to resume
     * @return A future that completes when the download is resumed
     */
    CompletableFuture<Void> resumeDownload(Download download);

    /**
     * Changes the settings of an existing download. aria2 handlers apply
     * changes live via changeOption; process-based handlers restart the
     * transfer with the new settings.
     *
     * @param download The download to update
     * @return A future that completes when the settings are applied
     */
    CompletableFuture<Void> changeSettings(Download download);

    /**
     * Moves a download's payload to another directory. Active transfers are
     * paused while their payload and resume metadata are moved, reconfigured
     * at the engine, and then resumed. A download that was already paused
     * remains paused.
     *
     * @param download The download to relocate
     * @param destination The new destination directory
     * @return A future that completes after relocation (and any required resume)
     */
    CompletableFuture<Void> relocateDownload(Download download, Path destination);

    /**
     * Cancels and removes a download.
     *
     * @param download The download to cancel
     * @param deleteFiles Whether to delete associated files
     * @return A future that completes when the download is canceled
     */
    CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles);

    /**
     * Fetches the current peer list for a BitTorrent download (aria2.getPeers).
     *
     * @param download the download
     * @return list of peer detail maps; empty for non-aria2 downloads
     */
    List<Map<String, Object>> getDownloadPeers(Download download);

    /**
     * Fetches the file list of a download (aria2.getFiles).
     *
     * @param download the download
     * @return list of file detail maps; empty for non-aria2 downloads
     */
    List<Map<String, Object>> getDownloadFiles(Download download);

    /**
     * Resolves descriptor files using the proxy selected in a creation
     * dialog. Implementations must not add a visible download or leave a
     * transfer running after the preview completes.
     *
     * @param source descriptor or magnet URI
     * @param proxyAddress explicit proxy URI, or {@code null} for manager defaults
     * @return asynchronously discovered file metadata
     */
    CompletableFuture<List<DownloadFileInfo>> previewDownloadFiles(
            URI source, String proxyAddress);

    /**
     * Fetches the tracker announce tiers of a BitTorrent download.
     *
     * @param download the download
     * @return list of tracker tiers, each a list of announce URLs; empty otherwise
     */
    List<List<String>> getDownloadTrackers(Download download);

    /**
     * Installs a schedule gate consulted before any download is started: the
     * predicate receives the download id and returns whether starting it is
     * allowed right now. Used by the weekly scheduler (uGet-style ranges):
     * outside the configured ranges downloads stay QUEUED. A null gate
     * removes the restriction.
     *
     * @param gate the gate predicate, or null to allow all starts
     */
    void setDownloadGate(java.util.function.Predicate<String> gate);
}
