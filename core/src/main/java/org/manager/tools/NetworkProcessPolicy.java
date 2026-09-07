package org.manager.tools;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** ODM owns routing; a child must not inherit shell proxy or bypass settings. */
public final class NetworkProcessPolicy {
    private NetworkProcessPolicy() { }

    public static ProcessBuilder prepare(ProcessBuilder builder) {
        clearProxyEnvironment(builder.environment());
        return builder;
    }

    public static void clearProxyEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(key -> key.toLowerCase(Locale.ROOT).endsWith("_proxy"));
    }

    public static String selectedProxy(org.manager.download.DownloadSettings settings) {
        if (settings == null || !settings.isUseProxy()) { return ""; }
        String route = proxyAddress(settings.getProxyAddress());
        if (route.isEmpty()) { throw new IllegalArgumentException("An enabled proxy requires an address"); }
        return route;
    }

    /** Empty means an explicit direct route; SOCKS always resolves at the proxy. */
    public static String proxyAddress(String address) {
        if (address == null || address.isBlank()) { return ""; }
        try {
            URI uri = URI.create(address.strip());
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            scheme = switch (scheme) {
                case "socks4", "socks4a" -> "socks4a";
                case "socks5", "socks5h" -> "socks5h";
                case "http", "https" -> scheme;
                default -> throw new IllegalArgumentException();
            };
            return scheme + "://" + uri.getRawAuthority();
        } catch (RuntimeException invalid) {
            // Never echo addresses: they may contain proxy credentials.
            throw new IllegalArgumentException("Invalid or unsupported proxy address");
        }
    }
}
