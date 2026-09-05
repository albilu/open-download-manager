package org.manager.download.handler;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.GlobalSettings;
import org.manager.StartupCoordinator;
import org.manager.download.Download;
import org.manager.download.DownloadSettings;
import org.manager.download.DownloadSettingsFactory;
import org.curl.CurlSettings;
import org.manager.tools.ToolManagerFactory;

/**
 * Factory for creating and managing download handlers. This class is
 * responsible for providing the appropriate handler for each download type.
 */
public class DownloadHandlerFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadHandlerFactory.class);

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
            LOGGER.debug("Download handlers already initialized, skipping duplicate initialization");
            return;
        }

        // Use startup coordination to prevent multiple initialization attempts
        String componentId = StartupCoordinator.DOWNLOAD_HANDLER_FACTORY;
        if (!startupCoordinator.beginComponentInitialization(componentId)) {
            LOGGER.debug("Download handler initialization already in progress or complete");
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
                    LOGGER.error("Failed to initialize aria2 download handler", e);
                    // Remove the handler if initialization failed
                    handlers.remove(Download.Type.ARIA2);
                    handlers.remove(Download.Type.TOR);
                    throw new RuntimeException("aria2 handler initialization failed", e);
                }
            }
        } else if (!toolManagerFactory.isToolAvailable("aria2")) {
            LOGGER.warn("aria2 is not available, HTTP/FTP/BitTorrent downloads will not be supported");
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
                    LOGGER.error("Failed to initialize yt-dlp download handler", e);
                    handlers.remove(Download.Type.YOUTUBE);
                }
            }
        } else if (!toolManagerFactory.isToolAvailable("yt-dlp")) {
            LOGGER.warn("yt-dlp is not available, YouTube downloads will not be supported");
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
                    LOGGER.error("Failed to initialize httrack download handler", e);
                    handlers.remove(Download.Type.WEBSITE_SCRAPING);
                }
            }
        } else if (!toolManagerFactory.isToolAvailable("httrack")) {
            LOGGER.warn("httrack is not available, website scraping will not be supported");
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
                    LOGGER.error("Failed to initialize curl download handler", e);
                    handlers.remove(Download.Type.CURL);
                }
            }
        } else if (!toolManagerFactory.isToolAvailable("curl")) {
            LOGGER.warn("curl is not available, fallback downloads will not be supported");
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
                    LOGGER.error("Failed to initialize proxychains download handler", e);
                    handlers.remove(Download.Type.PROXYCHAINS);
                }
            }
        } else if (!toolManagerFactory.isToolAvailable("proxychains")) {
            LOGGER.warn("proxychains is not available, proxy downloads will not be supported");
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

        // aria2 does not accept SOCKS4/SOCKS5 in --all-proxy. Every aria2
        // workload with an effective SOCKS proxy (including torrents,
        // magnets and Metalinks) therefore tries proxychains first. Plain
        // URL transfers may use Curl's native SOCKS support as a secondary
        // route; aria2-only torrent-like work must never reach Curl.
        // Downloads already typed PROXYCHAINS/CURL keep their handler so
        // routing remains stable across later lookups.
        String effectiveProxy = effectiveProxyAddress(download);
        boolean socksRoutingRequired = download.getType() == Download.Type.ARIA2
                && isSocksProxyAddress(effectiveProxy);
        if (socksRoutingRequired) {
            // This method owns the complete privacy-preserving fallback
            // chain. Do not continue into the ordinary direct fallback path.
            return routeSocksDownload(download, effectiveProxy);
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
            LOGGER.warn("No suitable handler found for download type: " + download.getType());
            return null;
        }

        return handler;
    }

    /**
     * Routes fresh ARIA2 downloads with an effective SOCKS proxy through
     * proxychains first. When proxychains is unavailable, a plain URL may
     * fall back to Curl with the same SOCKS proxy. Torrent, magnet and
     * Metalink work remains proxychains-only because Curl cannot execute
     * those aria2 protocols.
     *
     * @return a handler, or null when no socks routing applies
     */
    private DownloadHandler routeSocksDownload(Download download, String proxy) {
        DownloadSettings settings = download.getSettings();
        settings.setUseProxy(true);
        settings.setProxyAddress(proxy);
        DownloadHandler proxychains = handlers.get(Download.Type.PROXYCHAINS);
        if (proxychains != null) {
            Download.Type originalType = download.getType();
            download.setType(Download.Type.PROXYCHAINS);
            try {
                if (proxychains.canHandle(download)) {
                    LOGGER.info("Routing SOCKS download through proxychains: "
                            + download.getName());
                    return proxychains;
                }
            } catch (RuntimeException routingFailure) {
                LOGGER.warn("Proxychains rejected SOCKS download "
                        + download.getId() + "; considering Curl fallback", routingFailure);
            }
            download.setType(originalType);
        }

        if (prepareCurlProxyFallback(download)) {
            DownloadHandler curl = handlers.get(Download.Type.CURL);
            LOGGER.info("Proxychains unavailable; falling back to Curl with SOCKS proxy: "
                    + download.getName());
            return curl;
        }

        LOGGER.error("Proxychains is unavailable and no valid Curl fallback exists for "
                + "SOCKS-proxied download " + download.getId());
        return null;
    }

    /**
     * Returns whether a proxy-preserving Curl fallback can be prepared
     * without mutating the download. Used by the manager before it claims a
     * failed proxychains generation for runtime handoff.
     */
    public boolean canPrepareCurlProxyFallback(Download download) {
        if (download == null) {
            return false;
        }
        String proxy = effectiveProxyAddress(download);
        return isCurlTransfer(download)
                && !isAria2OnlyDownload(download)
                && isSocksProxyAddress(proxy)
                && isValidProxyAddress(proxy)
                && handlers.get(Download.Type.CURL) != null;
    }

    /**
     * Retypes a plain URL for Curl while translating the effective SOCKS
     * proxy and engine-neutral settings. Merely changing {@link Download.Type}
     * is insufficient because {@code CurlClient} intentionally reads proxy
     * fields from {@link CurlSettings}.
     *
     * @return true when a valid proxy-preserving Curl handler is available
     */
    public boolean prepareCurlProxyFallback(Download download) {
        if (!canPrepareCurlProxyFallback(download)) {
            return false;
        }

        String proxy = effectiveProxyAddress(download);
        Download.Type originalType = download.getType();
        DownloadSettings old = download.getSettings();
        CurlSettings curl = old instanceof CurlSettings existing
                ? existing : new CurlSettings();
        if (old != curl) {
            curl.setConnections(old.getConnections());
            curl.setDownloadLimitKB(old.getDownloadLimitKB());
            curl.setUploadLimitKB(old.getUploadLimitKB());
            curl.setMaxRetries(old.getMaxRetries());
            curl.setRetryDelaySeconds(old.getRetryDelaySeconds());
            curl.setReferer(old.getReferer());
            curl.setUserAgent(old.getUserAgent());
            curl.setCookieHeader(old.getCookieHeader());
            old.getAdditionalOptions().forEach(curl::setOption);
        }
        curl.setUseProxy(true);
        curl.setProxyAddress(proxy);
        download.setSettings(curl);
        download.setType(Download.Type.CURL);

        DownloadHandler curlHandler = handlers.get(Download.Type.CURL);
        try {
            if (curlHandler != null && curlHandler.canHandle(download)) {
                return true;
            }
        } catch (RuntimeException routingFailure) {
            LOGGER.warn("Curl rejected proxychains fallback for "
                    + download.getId(), routingFailure);
        }

        download.setType(originalType);
        download.setSettings(old);
        return false;
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

    public static boolean isSocksProxyAddress(String address) {
        if (address == null) {
            return false;
        }
        String lower = address.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("socks4://") || lower.startsWith("socks4a://")
                || lower.startsWith("socks5://")
                || lower.startsWith("socks5h://");
    }

    private static boolean isValidProxyAddress(String address) {
        try {
            URI uri = URI.create(address);
            return uri.getHost() != null && uri.getPort() > 0 && uri.getPort() <= 65535;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isCurlTransfer(Download download) {
        Download.Protocol protocol = download != null ? download.getProtocol() : null;
        return protocol != null && protocol.isDirectTransfer();
    }

    /** SFTP, torrent descriptors, magnets and Metalinks require aria2 semantics. */
    private static boolean isAria2OnlyDownload(Download download) {
        Download.Protocol protocol = download != null ? download.getProtocol() : null;
        return protocol != null && protocol.requiresAria2();
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
     *
     * <p>Handler instances are deduplicated (one instance may be registered
     * under several download types — aria2 serves both ARIA2 and TOR), every
     * distinct shutdown future is awaited with a bounded per-handler
     * timeout, and any failure is rethrown as an aggregate so the essential
     * shutdown phase can surface it.</p>
     */
    public void shutdownHandlers() {
        Set<DownloadHandler> uniqueHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        uniqueHandlers.addAll(handlers.values());

        List<Throwable> failures = new ArrayList<>();
        for (DownloadHandler handler : uniqueHandlers) {
            try {
                handler.shutdown().get(HANDLER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                LOGGER.error("Handler shutdown timed out after "
                        + HANDLER_SHUTDOWN_TIMEOUT_SECONDS + "s: " + handler.getSupportedType());
                failures.add(new RuntimeException(
                        "Handler shutdown timed out: " + handler.getSupportedType(), e));
            } catch (Exception e) {
                Throwable cause = (e instanceof java.util.concurrent.ExecutionException && e.getCause() != null)
                        ? e.getCause() : e;
                LOGGER.error("Handler shutdown failed: " + handler.getSupportedType(), cause);
                failures.add(new RuntimeException(
                        "Handler shutdown failed: " + handler.getSupportedType(), cause));
            }
        }
        handlers.clear();

        if (!failures.isEmpty()) {
            RuntimeException aggregate = new RuntimeException(
                    "Download handler shutdown failed for " + failures.size() + " handler(s)");
            for (Throwable failure : failures) {
                aggregate.addSuppressed(failure);
            }
            throw aggregate;
        }
    }

    private static final long HANDLER_SHUTDOWN_TIMEOUT_SECONDS = 30;
}
