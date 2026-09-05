package org.odm.gtk4;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadNetworkCapabilities;
import org.manager.download.ExternalToolSettings;
import org.tor.TorService;

/**
 * Shared option plumbing for the new-download/import/property dialogs: the
 * proxy-type vocabulary, Tor SOCKS routing, proxy-URL assembly, and the
 * engine-neutral pass-through onto the {@link ExternalToolSettings} seam.
 * Previously each dialog hand-built proxy strings (drifting apart) and
 * mapped aria2 options via instanceof guards the seam made redundant.
 */
final class DialogOptions {

    /** Proxy type labels, index-aligned with the proxy_type_combo rows. */
    static final String[] PROXY_TYPES = {"None", "HTTP", "HTTPS", "SOCKS4", "SOCKS5"};
    /** The user's non-Tor proxy, retained while the active route is Tor. */
    static final String MANUAL_PROXY_ADDRESS_KEY = "network.manualProxyAddress";

    private DialogOptions() {
    }

    /** Parsed fields used to round-trip a persisted proxy URI through GTK controls. */
    record ProxyFields(int typeIndex, String host, int port, String username, String password) {
        static ProxyFields none() {
            return new ProxyFields(0, "", 0, "", "");
        }
    }

    /** Immutable snapshot shared by every per-record Network Options panel. */
    record NetworkValues(int connections, int downloadLimitKb, int uploadLimitKb,
            int maxRetries, int retryDelaySeconds, String referer, String userAgent,
            String cookie, boolean torActive, int proxyTypeIndex, String proxyHost,
            int proxyPort, String proxyUsername, String proxyPassword) {

        void applyTo(Download download) {
            applyCommon(download, connections, downloadLimitKb, uploadLimitKb,
                    maxRetries, retryDelaySeconds, referer, userAgent, cookie);
            applyProxy(download, torActive, proxyTypeIndex, proxyHost, proxyPort,
                    proxyUsername, proxyPassword);
        }
    }

    static ProxyFields parseProxy(String address) {
        if (address == null || address.isBlank()) {
            return ProxyFields.none();
        }
        try {
            URI uri = URI.create(address.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            int type = switch (scheme) {
                case "http" -> 1;
                case "https" -> 2;
                case "socks4", "socks4a" -> 3;
                case "socks5", "socks5h" -> 4;
                default -> 0;
            };
            if (type == 0 || uri.getHost() == null || uri.getHost().isBlank()) {
                return ProxyFields.none();
            }
            String username = "";
            String password = "";
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                int separator = userInfo.indexOf(':');
                username = separator >= 0 ? userInfo.substring(0, separator) : userInfo;
                password = separator >= 0 ? userInfo.substring(separator + 1) : "";
            }
            int port = uri.getPort();
            if (port < 1) {
                port = switch (type) {
                    case 1 -> 80;
                    case 2 -> 443;
                    default -> 1080;
                };
            }
            return new ProxyFields(type, uri.getHost(), port, username, password);
        } catch (RuntimeException invalidProxy) {
            return ProxyFields.none();
        }
    }

    static String buildProxyAddress(int typeIndex, String host, int port,
            String user, String password) {
        if (typeIndex <= 0 || typeIndex >= PROXY_TYPES.length
                || host == null || host.isBlank() || port < 1 || port > 65_535) {
            return null;
        }
        String userInfo = null;
        if (user != null && !user.isBlank()) {
            userInfo = password == null || password.isEmpty()
                    ? user.trim() : user.trim() + ':' + password;
        }
        try {
            // socks5h delegates DNS resolution to the proxy, preventing the
            // otherwise easy-to-miss local DNS leak of plain socks5.
            String scheme = typeIndex == 4 ? "socks5h" : PROXY_TYPES[typeIndex].toLowerCase();
            return new URI(scheme, userInfo, host.trim(), port, null, null, null).toASCIIString();
        } catch (Exception invalidProxy) {
            return null;
        }
    }

    /**
     * Returns the proxy chosen in Preferences independently of the temporary
     * Tor route. Older settings files have no dedicated key, so migrate their
     * active non-Tor proxy in memory on first use.
     */
    static String manualProxyAddress(GlobalSettings settings) {
        if (settings == null) {
            return null;
        }
        String remembered = settings.getProperty(MANUAL_PROXY_ADDRESS_KEY, null);
        if (remembered != null) {
            return remembered.isBlank() ? null : remembered;
        }
        String active = settings.isGlobalProxyEnabled()
                ? settings.getGlobalProxyAddress() : null;
        return isManagedTorProxy(active) ? null : active;
    }

    /** Stores the Preferences proxy without changing the currently active route. */
    static void rememberManualProxy(GlobalSettings settings, String address) {
        if (settings != null) {
            settings.setProperty(MANUAL_PROXY_ADDRESS_KEY,
                    address == null || address.isBlank() ? "" : address);
        }
    }

    /** Saves a currently active explicit proxy before Tor temporarily replaces it. */
    static void rememberActiveManualProxy(GlobalSettings settings) {
        if (settings == null || !settings.isGlobalProxyEnabled()) {
            return;
        }
        String active = settings.getGlobalProxyAddress();
        if (!isManagedTorProxy(active)) {
            rememberManualProxy(settings, active);
        }
    }

    /** Restores the explicit proxy (or direct networking) when Tor is disabled. */
    static void restoreManualProxy(GlobalSettings settings) {
        String address = manualProxyAddress(settings);
        settings.setGlobalProxyEnabled(address != null);
        settings.setGlobalProxyAddress(address);
    }

    private static boolean isManagedTorProxy(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(address);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return scheme != null && scheme.toLowerCase().startsWith("socks5")
                    && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
        } catch (RuntimeException invalidAddress) {
            return false;
        }
    }

    /** Returns the proxy route represented by the dialog controls. */
    static String selectedProxyAddress(boolean torActive, int typeIndex,
            String host, int port, String user, String password) {
        return torActive ? "socks5h://127.0.0.1:9050"
                : buildProxyAddress(typeIndex, host, port, user, password);
    }

    /**
     * Applies the proxy selection to a download: Tor SOCKS wins over the
     * explicit proxy fields; an empty host clears proxying.
     *
     * @param download     the download to configure
     * @param torActive    the Tor switch state
     * @param typeIndex    proxy_type_combo selection (0 = None)
     * @param host         proxy host text (trimmed by caller convention)
     * @param port         proxy port
     * @param user         proxy username ("" = none)
     * @param password     proxy password ("" = none)
     */
    static void applyProxy(Download download, boolean torActive, int typeIndex,
            String host, int port, String user, String password) {
        String proxy = selectedProxyAddress(torActive, typeIndex, host, port, user, password);
        if (proxy != null) {
            if (!DownloadNetworkCapabilities.supportsProxy(download, proxy)) {
                throw new IllegalArgumentException(torActive
                        ? "Tor/SOCKS proxying is not supported for this download"
                        : "This proxy type is not supported for this download");
            }
            download.setUseProxy(true);
            download.setProxyAddress(proxy);
        } else {
            download.setUseProxy(false);
            download.setProxyAddress(null);
        }
    }

    /** Only the toolbar starts the service; previews must wait until it is available. */
    static CompletableFuture<Void> ensureTorAvailable(boolean requested, TorService torService) {
        if (!requested || (torService != null && torService.isRunning())) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.failedFuture(new IllegalStateException(
                "Tor is selected. Start the Tor service using the toolbar first."));
    }

    /**
     * Passes the shared dialog fields onto the engine-neutral settings seam.
     * Every supported capability is applied, including blank/zero values
     * which explicitly restore the engine default.
     */
    static void applyCommon(ExternalToolSettings settings, int connections,
            int downloadLimitKb, int uploadLimitKb, int maxRetries, int retryDelaySeconds,
            String referer, String userAgent, String cookie) {
        applyCommon(settings, java.util.EnumSet.allOf(
                ExternalToolSettings.Capability.class), connections,
                downloadLimitKb, uploadLimitKb, maxRetries, retryDelaySeconds,
                referer, userAgent, cookie);
    }

    /** Applies only controls meaningful for this record's engine and protocol. */
    static void applyCommon(Download download, int connections,
            int downloadLimitKb, int uploadLimitKb, int maxRetries,
            int retryDelaySeconds, String referer, String userAgent, String cookie) {
        if (download == null) {
            return;
        }
        applyCommon(download.getSettings(), DownloadNetworkCapabilities.forDownload(download),
                connections, downloadLimitKb, uploadLimitKb, maxRetries,
                retryDelaySeconds, referer, userAgent, cookie);
    }

    static void applyCommon(ExternalToolSettings settings,
            java.util.Set<ExternalToolSettings.Capability> enabledCapabilities,
            int connections, int downloadLimitKb, int uploadLimitKb,
            int maxRetries, int retryDelaySeconds, String referer,
            String userAgent, String cookie) {
        if (settings == null) {
            return;
        }
        if (enabledCapabilities.contains(ExternalToolSettings.Capability.CONNECTIONS)
                && settings.supports(ExternalToolSettings.Capability.CONNECTIONS)) {
            settings.setMaxConnections(connections);
        }
        if (enabledCapabilities.contains(ExternalToolSettings.Capability.DOWNLOAD_LIMIT)
                && settings.supports(ExternalToolSettings.Capability.DOWNLOAD_LIMIT)) {
            settings.setDownloadLimitKB(Math.max(0, downloadLimitKb));
        }
        if (enabledCapabilities.contains(ExternalToolSettings.Capability.UPLOAD_LIMIT)
                && settings.supports(ExternalToolSettings.Capability.UPLOAD_LIMIT)) {
            settings.setUploadLimitKB(uploadLimitKb);
        }
        if (enabledCapabilities.contains(ExternalToolSettings.Capability.MAX_RETRIES)
                && settings.supports(ExternalToolSettings.Capability.MAX_RETRIES)) {
            settings.setMaxRetries(maxRetries);
        }
        if (enabledCapabilities.contains(ExternalToolSettings.Capability.RETRY_DELAY)
                && settings.supports(ExternalToolSettings.Capability.RETRY_DELAY)) {
            settings.setRetryDelaySeconds(retryDelaySeconds);
        }
        if (enabledCapabilities.contains(ExternalToolSettings.Capability.REFERER)
                && settings.supports(ExternalToolSettings.Capability.REFERER)) {
            settings.setReferer(referer == null || referer.isBlank() ? null : referer.trim());
        }
        if (enabledCapabilities.contains(ExternalToolSettings.Capability.USER_AGENT)
                && settings.supports(ExternalToolSettings.Capability.USER_AGENT)) {
            settings.setUserAgent(userAgent == null || userAgent.isBlank() ? null : userAgent.trim());
        }
        if (enabledCapabilities.contains(ExternalToolSettings.Capability.COOKIE)
                && settings.supports(ExternalToolSettings.Capability.COOKIE)) {
            String value = cookie == null ? "" : cookie.trim();
            settings.setCookieHeader(value.isEmpty() ? null
                    : value.regionMatches(true, 0, "Cookie:", 0, 7)
                            ? value : "Cookie: " + value);
        }
    }
}
