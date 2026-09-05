package org.manager.download;

/**
 * Engine-neutral vocabulary for the per-download properties a UI edits:
 * connection count, download limit, retry policy, referer, user agent and
 * cookie header. Every engine's settings implement this seam, so property
 * dialogs work uniformly instead of being aria2-only. The base
 * implementation persists values engine-neutrally; each engine's settings
 * class overrides the accessors it can bridge to native tool options.
 *
 * <p>Units are fixed and engine-independent: limits in KiB/s (0 =
 * unlimited), retry delay in seconds (0 = engine default).
 */
public interface ExternalToolSettings {

    enum Capability {
        CONNECTIONS,
        DOWNLOAD_LIMIT,
        UPLOAD_LIMIT,
        MAX_RETRIES,
        RETRY_DELAY,
        REFERER,
        USER_AGENT,
        COOKIE,
        /** HTTP/HTTPS-style proxy support, either native or through ODM routing. */
        PROXY,
        /** SOCKS proxy support, either native or through ODM routing. */
        SOCKS_PROXY
    }

    /** Whether this engine actually maps the setting to its native command. */
    default boolean supports(Capability capability) {
        return false;
    }

    /** Maximum simultaneous connections for this download. */
    int getMaxConnections();

    ExternalToolSettings setMaxConnections(int maxConnections);

    /** Download limit in KiB/s; 0 means unlimited. */
    int getDownloadLimitKB();

    ExternalToolSettings setDownloadLimitKB(int kibPerSecond);

    /**
     * Upload limit in KiB/s; 0 means unlimited. Only meaningful for
     * bidirectional engines (BitTorrent); other engines persist the value
     * but ignore it.
     */
    int getUploadLimitKB();

    ExternalToolSettings setUploadLimitKB(int kibPerSecond);

    /** Maximum retry attempts; 0 means the engine default. */
    int getMaxRetries();

    ExternalToolSettings setMaxRetries(int maxRetries);

    /** Delay between retries in seconds; 0 means the engine default. */
    int getRetryDelaySeconds();

    ExternalToolSettings setRetryDelaySeconds(int seconds);

    /** Referer to send, or null. */
    String getReferer();

    ExternalToolSettings setReferer(String referer);

    /** User-Agent to send, or null. */
    String getUserAgent();

    ExternalToolSettings setUserAgent(String userAgent);

    /**
     * Cookie header value (e.g. {@code Cookie: key=value}), or null. Engines
     * without header-cookie support persist the value but ignore it.
     */
    String getCookieHeader();

    ExternalToolSettings setCookieHeader(String cookieHeader);
}
