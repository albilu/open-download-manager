package org.odm.gtk4;

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
            download.setProxyAddress("socks5://127.0.0.1:9050");
            return;
        }
        if (typeIndex <= 0 || host == null || host.isBlank()) {
            return;
        }
        StringBuilder proxy = new StringBuilder(PROXY_TYPES[typeIndex].toLowerCase())
                .append("://");
        if (user != null && !user.isBlank()) {
            proxy.append(user.trim());
            if (password != null && !password.isEmpty()) {
                proxy.append(':').append(password);
            }
            proxy.append('@');
        }
        proxy.append(host.trim()).append(':').append(port);
        download.setUseProxy(true);
        download.setProxyAddress(proxy.toString());
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
