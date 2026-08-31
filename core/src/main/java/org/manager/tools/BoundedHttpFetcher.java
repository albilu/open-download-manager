package org.manager.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Locale;

/** Small, bounded HTTP(S) fetches that honor the configured proxy. */
public final class BoundedHttpFetcher {

    /** Bounded response body plus metadata needed by redirect-aware callers. */
    public record FetchResult(byte[] body, URI finalUri, String contentType) {
        public FetchResult {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private BoundedHttpFetcher() {
    }

    public static byte[] fetch(URI uri, long maximumBytes, Duration connectTimeout,
            Duration readTimeout, String proxyAddress) throws IOException {
        return fetchResult(uri, maximumBytes, connectTimeout, readTimeout,
                proxyAddress).body();
    }

    public static FetchResult fetchResult(URI uri, long maximumBytes,
            Duration connectTimeout, Duration readTimeout, String proxyAddress)
            throws IOException {
        if (uri == null || maximumBytes < 1) {
            throw new IllegalArgumentException("URI and a positive byte limit are required");
        }
        String scheme = uri.getScheme() == null ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("Only HTTP(S) resources are supported");
        }

        Proxy proxy = proxy(proxyAddress);
        URLConnection connection = proxy == Proxy.NO_PROXY
                ? uri.toURL().openConnection() : uri.toURL().openConnection(proxy);
        connection.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
        connection.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
        connection.setUseCaches(false);

        HttpURLConnection http = (HttpURLConnection) connection;
        http.setInstanceFollowRedirects(true);
        http.setRequestMethod("GET");
        http.setRequestProperty("User-Agent", "Open Download Manager");
        try {
            int status = http.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP request failed with status " + status);
            }
            long declared = http.getContentLengthLong();
            if (declared > maximumBytes) {
                throw new IOException("Remote resource exceeds the configured byte limit");
            }
            try (InputStream input = http.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream(
                            declared > 0 ? (int) Math.min(declared, maximumBytes) : 8192)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if ((long) output.size() + count > maximumBytes) {
                        throw new IOException("Remote resource exceeds the configured byte limit");
                    }
                    output.write(buffer, 0, count);
                }
                URI finalUri;
                try {
                    finalUri = http.getURL().toURI();
                } catch (java.net.URISyntaxException invalidRedirect) {
                    throw new IOException("HTTP redirect produced an invalid URI", invalidRedirect);
                }
                return new FetchResult(output.toByteArray(), finalUri,
                        http.getContentType());
            }
        } finally {
            http.disconnect();
        }
    }

    private static Proxy proxy(String address) throws IOException {
        if (address == null || address.isBlank()) {
            return Proxy.NO_PROXY;
        }
        try {
            URI uri = URI.create(address);
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535) {
                throw new IOException("Invalid proxy address");
            }
            String scheme = uri.getScheme() == null ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            Proxy.Type type = switch (scheme) {
                case "socks4", "socks5", "socks5h" -> Proxy.Type.SOCKS;
                case "http", "https" -> Proxy.Type.HTTP;
                default -> throw new IOException("Unsupported proxy scheme");
            };
            // Unresolved is essential for SOCKS5: the proxy, not the local
            // resolver, receives the destination hostname.
            return new Proxy(type, InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort()));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid proxy address", e);
        }
    }
}
