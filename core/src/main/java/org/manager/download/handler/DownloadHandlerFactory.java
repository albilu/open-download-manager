package org.manager.download.handler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.manager.GlobalSettings;
import org.manager.StartupCoordinator;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;

/**
 * Factory for creating and managing download handlers. This class is
 * responsible for providing the appropriate handler for each download type.
 */
public class DownloadHandlerFactory {

    private static final Logger LOGGER = Logger.getLogger(DownloadHandlerFactory.class.getName());

    // Read from UI/event/executor threads while registration mutates during
    // init and shutdownHandlers clears during teardown
    private final Map<Download.Type, DownloadHandler> handlers = new java.util.concurrent.ConcurrentHashMap<>();
    private final GlobalSettings globalSettings;
    private final DownloadSettingsFactory settingsFactory;
    private final ExecutorService executor;
    private final ToolManagerFactory toolManagerFactory;
    private final StartupCoordinator startupCoordinator;
    private final AtomicBoolean handlersInitialized = new AtomicBoolean(false);

    /**
     * Creates a new DownloadHandlerFactory.
     *
     * @param globalSettings     The global settings
     * @param settingsFactory    The settings factory
     * @param executor           The executor service for async operations
     * @param toolManagerFactory The tool manager factory
     */
    public DownloadHandlerFactory(GlobalSettings globalSettings,
            DownloadSettingsFactory settingsFactory,
            ExecutorService executor,
            ToolManagerFactory toolManagerFactory) {
        this.globalSettings = globalSettings;
        this.settingsFactory = settingsFactory;
        this.executor = executor;
        this.toolManagerFactory = toolManagerFactory;
        // Generation-scoped coordinator (per ApplicationFactory) so a reset
        // generation's handler initialization cannot be blocked by stale
        // flags from an earlier one
        this.startupCoordinator = org.manager.ApplicationContext.getStartupCoordinator();
    }

    /**
     * Initializes all handlers with startup coordination to prevent duplicates.
     * This should be called during the download manager initialization.
     */
    public void initializeHandlers() {
        // Check if handlers are already initialized to prevent duplicates
        if (handlersInitialized.get()) {
            LOGGER.fine("Download handlers already initialized, skipping duplicate initialization");
            return;
        }

        // Use startup coordination to prevent multiple initialization attempts
        String componentId = StartupCoordinator.DOWNLOAD_HANDLER_FACTORY;
        if (!startupCoordinator.beginComponentInitialization(componentId)) {
            LOGGER.fine("Download handler initialization already in progress or complete");
            return;
        }

        try {
            LOGGER.info("Starting coordinated download handler initialization...");
            long startTime = System.currentTimeMillis();

            initializeHandlersInternal();

            handlersInitialized.set(true);
            startupCoordinator.completeComponentInitialization(componentId);

            long duration = System.currentTimeMillis() - startTime;
            LOGGER.info("Download handler initialization completed in " + duration + "ms");
        } catch (Exception e) {
            startupCoordinator.failComponentInitialization(componentId, e);
            throw e;
        }
    }

    /**
     * Gets the appropriate handler for the given download type.
     */
    private void initializeHandlersInternal() {
        // Create HTTP/FTP/BitTorrent handler (aria2-based)
        if (toolManagerFactory.isToolAvailable("aria2")
                && !startupCoordinator.isComponentInitialized(StartupCoordinator.ARIA2_HANDLER)) {

            if (startupCoordinator.beginComponentInitialization(StartupCoordinator.ARIA2_HANDLER)) {
                try {
                    Aria2DownloadHandler aria2Handler = new Aria2DownloadHandler(
                            globalSettings, settingsFactory, executor, toolManagerFactory);

                    // Register for HTTP, FTP, TORRENT, and MAGNET
                    // registerHandler(Download.Type.HTTP, aria2Handler);
                    // registerHandler(Download.Type.FTP, aria2Handler);
                    // registerHandler(Download.Type.TORRENT, aria2Handler);
                    // registerHandler(Download.Type.MAGNET, aria2Handler);
                    registerHandler(Download.Type.ARIA2, aria2Handler);
                    registerHandler(Download.Type.TOR, aria2Handler);

                    aria2Handler.initialize().join();
                    startupCoordinator.completeComponentInitialization(StartupCoordinator.ARIA2_HANDLER);
                    LOGGER.info("Initialized aria2 download handler");
                } catch (Exception e) {
                    startupCoordinator.failComponentInitialization(StartupCoordinator.ARIA2_HANDLER, e);
                    LOGGER.log(Level.SEVERE, "Failed to initialize aria2 download handler", e);
                    // Remove the handler if initialization failed
                    handlers.remove(Download.Type.ARIA2);
                    handlers.remove(Download.Type.TOR);
                    throw new RuntimeException("aria2 handler initialization failed", e);
                }
            }
        } else if (!toolManagerFactory.isToolAvailable("aria2")) {
            LOGGER.warning("aria2 is not available, HTTP/FTP/BitTorrent downloads will not be supported");
        }

        // Create YouTube handler (yt-dlp-based)
        if (toolManagerFactory.isToolAvailable("yt-dlp")
                && !startupCoordinator.isComponentInitialized(StartupCoordinator.YTDLP_HANDLER)) {

            if (startupCoordinator.beginComponentInitialization(StartupCoordinator.YTDLP_HANDLER)) {
                try {
                    YtDlpDownloadHandler ytDlpHandler = new YtDlpDownloadHandler(
                            globalSettings, settingsFactory, executor, toolManagerFactory);

                    registerHandler(Download.Type.YOUTUBE, ytDlpHandler);
                    ytDlpHandler.initialize().join();
                    startupCoordinator.completeComponentInitialization(StartupCoordinator.YTDLP_HANDLER);
                    LOGGER.info("Initialized yt-dlp download handler");
                } catch (Exception e) {
                    startupCoordinator.failComponentInitialization(StartupCoordinator.YTDLP_HANDLER, e);
                    LOGGER.log(Level.SEVERE, "Failed to initialize yt-dlp download handler", e);
                    handlers.remove(Download.Type.YOUTUBE);
                }
            }
        } else if (!toolManagerFactory.isToolAvailable("yt-dlp")) {
            LOGGER.warning("yt-dlp is not available, YouTube downloads will not be supported");
        }

        // Create website scraping handler (httrack-based)
        if (toolManagerFactory.isToolAvailable("httrack")
                && !startupCoordinator.isComponentInitialized(StartupCoordinator.HTTRACK_HANDLER)) {

            if (startupCoordinator.beginComponentInitialization(StartupCoordinator.HTTRACK_HANDLER)) {
                try {
                    HttrackDownloadHandler httrackHandler = new HttrackDownloadHandler(
                            globalSettings, settingsFactory, executor, toolManagerFactory);

                    registerHandler(Download.Type.WEBSITE_SCRAPING, httrackHandler);
                    httrackHandler.initialize().join();
                    startupCoordinator.completeComponentInitialization(StartupCoordinator.HTTRACK_HANDLER);
                    LOGGER.info("Initialized httrack download handler");
                } catch (Exception e) {
                    startupCoordinator.failComponentInitialization(StartupCoordinator.HTTRACK_HANDLER, e);
                    LOGGER.log(Level.SEVERE, "Failed to initialize httrack download handler", e);
                    handlers.remove(Download.Type.WEBSITE_SCRAPING);
                }
            }
        } else if (!toolManagerFactory.isToolAvailable("httrack")) {
            LOGGER.warning("httrack is not available, website scraping will not be supported");
        }

        // Create curl handler (as fallback)
        if (toolManagerFactory.isToolAvailable("curl")
                && !startupCoordinator.isComponentInitialized(StartupCoordinator.CURL_HANDLER)) {

            if (startupCoordinator.beginComponentInitialization(StartupCoordinator.CURL_HANDLER)) {
                try {
                    CurlDownloadHandler curlHandler = new CurlDownloadHandler(
                            globalSettings, settingsFactory, executor, toolManagerFactory);

                    registerHandler(Download.Type.CURL, curlHandler);
                    curlHandler.initialize().join();
                    startupCoordinator.completeComponentInitialization(StartupCoordinator.CURL_HANDLER);
                    LOGGER.info("Initialized curl download handler");
                } catch (Exception e) {
                    startupCoordinator.failComponentInitialization(StartupCoordinator.CURL_HANDLER, e);
                    LOGGER.log(Level.SEVERE, "Failed to initialize curl download handler", e);
                    handlers.remove(Download.Type.CURL);
                }
            }
        } else if (!toolManagerFactory.isToolAvailable("curl")) {
            LOGGER.warning("curl is not available, fallback downloads will not be supported");
        }

        // Create proxychains handler
        if (toolManagerFactory.isToolAvailable("proxychains")
                && !startupCoordinator.isComponentInitialized(StartupCoordinator.PROXYCHAINS_HANDLER)) {

            if (startupCoordinator.beginComponentInitialization(StartupCoordinator.PROXYCHAINS_HANDLER)) {
                try {
                    ProxychainsDownloadHandler proxychainsHandler = new ProxychainsDownloadHandler(
                            globalSettings, settingsFactory, executor, toolManagerFactory);

                    registerHandler(Download.Type.PROXYCHAINS, proxychainsHandler);
                    proxychainsHandler.initialize().join();
                    startupCoordinator.completeComponentInitialization(StartupCoordinator.PROXYCHAINS_HANDLER);
                    LOGGER.info("Initialized proxychains download handler");
                } catch (Exception e) {
                    startupCoordinator.failComponentInitialization(StartupCoordinator.PROXYCHAINS_HANDLER, e);
                    LOGGER.log(Level.SEVERE, "Failed to initialize proxychains download handler", e);
                    handlers.remove(Download.Type.PROXYCHAINS);
                }
            }
        } else if (!toolManagerFactory.isToolAvailable("proxychains")) {
            LOGGER.warning("proxychains is not available, proxy downloads will not be supported");
        }
    }

    /**
     * Registers a handler for a specific download type.
     *
     * @param type    The download type
     * @param handler The handler for the type
     */
    public void registerHandler(Download.Type type, DownloadHandler handler) {
        handlers.put(type, handler);
    }

    /**
     * Gets the appropriate handler for a download.
     *
     * @param download The download
     * @return The appropriate handler, or null if no suitable handler is found
     */
    public DownloadHandler getHandler(Download download) {
        if (download == null) {
            return null;
        }

        // Socks routing: fresh aria2 downloads whose effective proxy is
        // socks4/socks5 (per-download fields, global proxy setting, or the
        // Tor toggle) are routed through proxychains when available. Without
        // proxychains, plain HTTP(S)/FTP downloads fall back to curl (native
        // -x socks support); torrents/magnets stay on aria2 (native
        // all-proxy socks). Downloads already typed PROXYCHAINS/CURL keep
        // their handler so routing stays stable across later lookups.
        DownloadHandler socksHandler = routeSocksDownload(download);
        if (socksHandler != null) {
            return socksHandler;
        }

        // Get the registered handler for this type
        DownloadHandler handler = handlers.get(download.getType());

        // If no handler found or it can't handle this download, try fallbacks
        if (handler == null || !handler.canHandle(download)) {
            // Try curl as fallback if available
            handler = handlers.get(Download.Type.CURL);
            if (handler != null && handler.canHandle(download)) {
                LOGGER.info("Using curl as fallback for " + download.getType() + " download");
                return handler;
            }

            // No suitable handler found
            LOGGER.warning("No suitable handler found for download type: " + download.getType());
            return null;
        }

        return handler;
    }

    /**
     * Routes fresh ARIA2 downloads with an effective socks proxy to the
     * proxychains handler, or to curl when proxychains is unavailable.
     *
     * @return a handler, or null when no socks routing applies
     */
    private DownloadHandler routeSocksDownload(Download download) {
        if (download.getType() != Download.Type.ARIA2) {
            return null;
        }
        String proxy = effectiveProxyAddress(download);
        if (proxy == null || !isSocksProxy(proxy)) {
            return null;
        }
        // Torrents/magnets cannot run through curl; aria2 handles their
        // socks proxy natively via all-proxy
        if (isTorrentLike(download)) {
            return null;
        }

        DownloadHandler proxychains = handlers.get(Download.Type.PROXYCHAINS);
        if (proxychains != null) {
            download.setType(Download.Type.PROXYCHAINS);
            if (proxychains.canHandle(download)) {
                LOGGER.info("Routing socks download through proxychains: "
                        + download.getName());
                return proxychains;
            }
        }

        DownloadHandler curl = handlers.get(Download.Type.CURL);
        if (curl != null) {
            download.setType(Download.Type.CURL);
            if (curl.canHandle(download)) {
                LOGGER.info("proxychains unavailable; falling back to curl with socks proxy: "
                        + download.getName());
                return curl;
            }
        }

        // No socks-capable alternative: let the normal path decide
        return null;
    }

    /**
     * Gets the proxy address that applies to this download: its own socks
     * proxy when configured, otherwise the global proxy when enabled.
     */
    private String effectiveProxyAddress(Download download) {
        if (download.getSettings() != null
                && download.getSettings().isUseProxy()
                && download.getSettings().getProxyAddress() != null) {
            return download.getSettings().getProxyAddress();
        }
        if (globalSettings.isGlobalProxyEnabled()
                && globalSettings.getGlobalProxyAddress() != null) {
            return globalSettings.getGlobalProxyAddress();
        }
        return null;
    }

    private static boolean isSocksProxy(String address) {
        String lower = address.toLowerCase();
        return lower.startsWith("socks4://") || lower.startsWith("socks5://")
                || lower.startsWith("socks5h://");
    }

    /**
     * Checks whether a download is torrent/magnet/metalink work that only
     * aria2 can perform.
     */
    private static boolean isTorrentLike(Download download) {
        URI uri = download.getUri();
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase();
        if (scheme.equals("magnet") || scheme.equals("torrent") || scheme.equals("metalink")) {
            return true;
        }
        String path = uri.getPath() != null ? uri.getPath().toLowerCase() : "";
        return path.endsWith(".torrent") || path.endsWith(".metalink") || path.endsWith(".meta4");
    }

    /**
     * Gets a handler for a specific download type.
     *
     * @param type The download type
     * @return The handler for the type, or null if not found
     */
    public DownloadHandler getHandler(Download.Type type) {
        return handlers.get(type);
    }

    /**
     * Checks if a handler is available for a specific download type.
     *
     * @param type The download type
     * @return true if a handler is available, false otherwise
     */
    public boolean isHandlerAvailable(Download.Type type) {
        return handlers.containsKey(type);
    }

    /**
     * Shuts down all handlers. This should be called during the download
     * manager shutdown.
     */
    public void shutdownHandlers() {
        for (DownloadHandler handler : handlers.values()) {
            try {
                handler.shutdown();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error shutting down handler: " + handler.getSupportedType(), e);
            }
        }
        handlers.clear();
    }
}
