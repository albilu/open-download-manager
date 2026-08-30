package org.odm.gtk4;

import java.net.URI;
import org.manager.download.Download;
import org.manager.download.ExternalToolSettings;

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

    private DialogOptions() {
    }

    /** Parsed fields used to round-trip a persisted proxy URI through GTK controls. */
    record ProxyFields(int typeIndex, String host, int port, String username, String password) {
        static ProxyFields none() {
            return new ProxyFields(0, "", 0, "", "");
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
        if (torActive) {
            download.setUseProxy(true);
            download.setProxyAddress("socks5h://127.0.0.1:9050");
            return;
        }
        if (typeIndex <= 0 || typeIndex >= PROXY_TYPES.length
                || host == null || host.isBlank()) {
            download.setUseProxy(false);
            download.setProxyAddress(null);
            return;
        }
        String proxy = buildProxyAddress(typeIndex, host, port, user, password);
        if (proxy != null) {
            download.setUseProxy(true);
            download.setProxyAddress(proxy);
        } else {
            download.setUseProxy(false);
            download.setProxyAddress(null);
        }
    }

    /**
     * Passes the shared dialog fields onto the engine-neutral settings seam.
     * Blank/zero values are skipped so engine defaults survive.
     */
    static void applyCommon(ExternalToolSettings settings, int connections,
            int downloadLimitKb, int uploadLimitKb, int maxRetries, int retryDelaySeconds,
            String referer, String userAgent, String cookie) {
        if (settings == null) {
            return;
        }
        settings.setMaxConnections(connections);
        if (downloadLimitKb > 0) {
            settings.setDownloadLimitKB(downloadLimitKb);
        }
        if (uploadLimitKb > 0) {
            settings.setUploadLimitKB(uploadLimitKb);
        }
        if (maxRetries > 0) {
            settings.setMaxRetries(maxRetries);
        }
        if (retryDelaySeconds > 0) {
            settings.setRetryDelaySeconds(retryDelaySeconds);
        }
        if (referer != null && !referer.isBlank()) {
            settings.setReferer(referer.trim());
        }
        if (userAgent != null && !userAgent.isBlank()) {
            settings.setUserAgent(userAgent.trim());
        }
        if (cookie != null && !cookie.isBlank()) {
            settings.setCookieHeader("Cookie: " + cookie.trim());
        }
    }
}
