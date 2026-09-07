package org.manager.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Bounded HTTP(S) transport with remote SOCKS DNS and TLS-to-proxy support. */
public final class BoundedHttpFetcher {
    public record FetchResult(byte[] body, URI finalUri, String contentType) {
        public FetchResult { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }

    private record Metadata(URI finalUri, String contentType) { }
    private BoundedHttpFetcher() { }

    public static byte[] fetch(URI uri, long maximumBytes, Duration connectTimeout,
            Duration readTimeout, String proxyAddress) throws IOException {
        return fetchResult(uri, maximumBytes, connectTimeout, readTimeout, proxyAddress).body();
    }

    public static FetchResult fetchResult(URI uri, long maximumBytes, Duration connectTimeout,
            Duration readTimeout, String proxyAddress) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Metadata metadata = transfer(uri, maximumBytes, connectTimeout, readTimeout, proxyAddress, output);
        return new FetchResult(output.toByteArray(), metadata.finalUri(), metadata.contentType());
    }

    /** Streams archives to disk without retaining a second in-memory copy. */
    public static void fetchTo(URI uri, Path destination, long maximumBytes,
            Duration connectTimeout, Duration readTimeout, String proxyAddress) throws IOException {
        try (OutputStream output = Files.newOutputStream(destination)) {
            transfer(uri, maximumBytes, connectTimeout, readTimeout, proxyAddress, output);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(destination);
            throw failure;
        }
    }

    private static Metadata transfer(URI uri, long maximumBytes, Duration connectTimeout,
            Duration readTimeout, String proxyAddress, OutputStream output) throws IOException {
        if (uri == null || maximumBytes < 1 || connectTimeout == null || readTimeout == null
                || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("URI, positive timeouts and a positive byte limit are required");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("Only HTTP(S) resources are supported");
        }
        String proxy;
        try {
            proxy = NetworkProcessPolicy.proxyAddress(proxyAddress);
        } catch (IllegalArgumentException invalid) {
            throw new IOException(invalid.getMessage());
        }
        String marker = "ODM_HTTP_" + UUID.randomUUID();
        // A total deadline bounds stalled redirects and body reads as well.
        long deadlineMs = Math.addExact(connectTimeout.toMillis(), readTimeout.toMillis());
        List<String> command = List.of(ToolPaths.curl(), "-q", "--silent", "--fail",
                "--location", "--max-redirs", "10", "--proto", "=http,https",
                "--proto-redir", scheme.equals("https") ? "=https" : "=http,https",
                "--proxy", proxy, "--noproxy", "",
                "--connect-timeout", seconds(connectTimeout.toMillis()),
                "--max-time", seconds(deadlineMs), "--max-filesize", Long.toString(maximumBytes),
                "--user-agent", "Open Download Manager",
                "--write-out", "%{stderr}\n" + marker + "\n%{http_code}\n%{url_effective}\n%{content_type}\n",
                "--url", uri.toASCIIString());
        Process process = NetworkProcessPolicy.prepare(new ProcessBuilder(command)).start();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var body = workers.submit(() -> {
                try (InputStream input = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    long received = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        received += count;
                        if (received > maximumBytes) {
                            process.destroyForcibly();
                            throw new IOException("Remote resource exceeds the configured byte limit");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                return null;
            });
            var details = workers.submit(() -> {
                try (InputStream input = process.getErrorStream()) {
                    byte[] bytes = input.readNBytes(65537);
                    if (bytes.length > 65536) {
                        process.destroyForcibly();
                        throw new IOException("HTTP response metadata exceeds the byte limit");
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            });
            try {
                if (!process.waitFor(deadlineMs + 1000, TimeUnit.MILLISECONDS)) {
                    throw new IOException("HTTP request timed out");
                }
                body.get();
                String[] fields = details.get().split("\\n" + marker + "\\n", 2);
                String[] metadata = fields.length == 2 ? fields[1].split("\\n", -1) : new String[0];
                if (process.exitValue() == 63) {
                    throw new IOException("Remote resource exceeds the configured byte limit");
                }
                if (metadata.length < 3) { throw new IOException("HTTP request failed"); }
                int status = Integer.parseInt(metadata[0]);
                if (status != 0 && (status < 200 || status >= 300)) {
                    throw new IOException("HTTP request failed with status " + status);
                }
                if (process.exitValue() != 0 || status == 0) {
                    throw new IOException("HTTP request failed through the selected route (curl "
                            + process.exitValue() + ")");
                }
                return new Metadata(URI.create(metadata[1]), metadata[2].isEmpty() ? null : metadata[2]);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("HTTP request interrupted", interrupted);
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof IOException io) { throw io; }
                throw new IOException("HTTP request failed", failure.getCause());
            } finally {
                process.destroyForcibly();
            }
        }
    }

    private static String seconds(long milliseconds) {
        return String.format(Locale.ROOT, "%.3f", milliseconds / 1000.0);
    }
}
