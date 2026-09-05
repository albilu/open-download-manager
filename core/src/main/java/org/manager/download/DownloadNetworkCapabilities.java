package org.manager.download;

import java.util.EnumSet;

/**
 * Resolves Network-panel capabilities for a concrete download. Engine
 * settings describe what their execution path can bridge; this class narrows
 * that declaration for protocols where a control would be meaningless.
 */
public final class DownloadNetworkCapabilities {

    private DownloadNetworkCapabilities() {
    }

    public static EnumSet<ExternalToolSettings.Capability> forDownload(Download download) {
        if (download == null || download.getSettings() == null) {
            return EnumSet.noneOf(ExternalToolSettings.Capability.class);
        }
        return forSettings(download.getSettings(), download.getType(), download.getProtocol());
    }

    public static EnumSet<ExternalToolSettings.Capability> forSettings(
            ExternalToolSettings settings, Download.Type type, Download.Protocol protocol) {
        EnumSet<ExternalToolSettings.Capability> capabilities =
                EnumSet.noneOf(ExternalToolSettings.Capability.class);
        if (settings == null) {
            return capabilities;
        }
        for (ExternalToolSettings.Capability capability
                : ExternalToolSettings.Capability.values()) {
            if (supports(settings, type, protocol, capability)) {
                capabilities.add(capability);
            }
        }
        return capabilities;
    }

    public static boolean supports(Download download,
            ExternalToolSettings.Capability capability) {
        return download != null && supports(download.getSettings(), download.getType(),
                download.getProtocol(), capability);
    }

    public static boolean supports(ExternalToolSettings settings, Download.Type type,
            Download.Protocol protocol, ExternalToolSettings.Capability capability) {
        if (settings == null || capability == null || !settings.supports(capability)) {
            return false;
        }
        if (protocol == null) {
            return true;
        }
        return switch (capability) {
            case UPLOAD_LIMIT -> protocol.supportsPeerDetails();
            case CONNECTIONS -> (type != Download.Type.ARIA2
                    && type != Download.Type.PROXYCHAINS
                    && type != Download.Type.TOR)
                    || !protocol.supportsPeerDetails();
            case REFERER, USER_AGENT, COOKIE -> type == Download.Type.YOUTUBE
                    || type == Download.Type.WEBSITE_SCRAPING
                    || protocol == Download.Protocol.HTTP
                    || protocol == Download.Protocol.HTTPS
                    || protocol == Download.Protocol.METALINK;
            case PROXY -> protocol != Download.Protocol.SFTP;
            case SOCKS_PROXY -> true;
            case DOWNLOAD_LIMIT, MAX_RETRIES, RETRY_DELAY -> true;
        };
    }

    /** Whether the selected proxy scheme is supported for this download. */
    public static boolean supportsProxy(Download download, String proxyAddress) {
        ExternalToolSettings.Capability capability = isSocksProxy(proxyAddress)
                ? ExternalToolSettings.Capability.SOCKS_PROXY
                : ExternalToolSettings.Capability.PROXY;
        return supports(download, capability);
    }

    /** Whether the selected proxy scheme is supported before a record exists. */
    public static boolean supportsProxy(ExternalToolSettings settings, Download.Type type,
            Download.Protocol protocol, String proxyAddress) {
        ExternalToolSettings.Capability capability = isSocksProxy(proxyAddress)
                ? ExternalToolSettings.Capability.SOCKS_PROXY
                : ExternalToolSettings.Capability.PROXY;
        return supports(settings, type, protocol, capability);
    }

    private static boolean isSocksProxy(String address) {
        if (address == null) {
            return false;
        }
        String lower = address.strip().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("socks4://") || lower.startsWith("socks4a://")
                || lower.startsWith("socks5://") || lower.startsWith("socks5h://");
    }
}
