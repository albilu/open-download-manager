package org.manager.download;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects checksums for ordinary HTTP(S) downloads using the sibling-file
 * convention: servers commonly publish {@code <file>.sha256},
 * {@code <file>.sha1}, or {@code <file>.md5} next to the downloadable file.
 * The checksum file is fetched with a short timeout and a hard size cap, and
 * GNU-style ({@code HEX  filename}) and bare-HEX formats are parsed.
 */
public final class ChecksumProbe {

    /** Hard cap on the sibling checksum file body. */
    private static final long MAX_CHECKSUM_FILE_BYTES = 64 * 1024;

    /** Extension (appended to the download URL) per algorithm, in probe order. */
    private static final Map<String, String> EXTENSIONS_BY_ALGORITHM = Map.of(
            "sha256", ".sha256",
            "sha512", ".sha512",
            "sha1", ".sha1",
            "md5", ".md5");

    /** Expected hex length per algorithm, for validation. */
    private static final Map<String, Integer> HEX_LENGTHS = Map.of(
            "sha256", 64,
            "sha512", 128,
            "sha1", 40,
            "md5", 32);

    private static final Pattern HEX_TOKEN = Pattern.compile("^[0-9a-fA-F]+$");

    private ChecksumProbe() {
        // utility class
    }

    /**
     * A checksum detected for a download URL.
     *
     * @param algorithm the checksum algorithm (sha256, sha512, sha1, md5)
     * @param checksum the expected checksum in lowercase hex
     * @param source the sibling file URL the checksum was read from
     */
    public record DetectedChecksum(String algorithm, String checksum, URI source) {
    }

    /**
     * Probes sibling checksum files for the given download URL.
     *
     * @param url the download URL (http/https with a non-empty path)
     * @return the first detected checksum, or empty when none is published
     */
    public static Optional<DetectedChecksum> probe(URI url) {
        if (url == null) {
            return Optional.empty();
        }
        String scheme = url.getScheme() != null ? url.getScheme().toLowerCase() : "";
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.empty();
        }
        if (url.getPath() == null || url.getPath().isBlank() || url.getPath().equals("/")) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> candidate : EXTENSIONS_BY_ALGORITHM.entrySet()) {
            URI sibling = URI.create(url + candidate.getValue());
            Optional<String> checksum = fetchAndParse(sibling, candidate.getKey());
            if (checksum.isPresent()) {
                return Optional.of(new DetectedChecksum(
                        candidate.getKey(), checksum.get(), sibling));
            }
        }
        return Optional.empty();
    }

    /**
     * Fetches a sibling checksum file and extracts the digest for the base
     * filename when the file lists multiple entries.
     */
    private static Optional<String> fetchAndParse(URI sibling, String algorithm) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(sibling)
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }
            String body;
            try (InputStream in = response.body();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (out.size() + read > MAX_CHECKSUM_FILE_BYTES) {
                        return Optional.empty(); // not a checksum file
                    }
                    out.write(buffer, 0, read);
                }
                body = out.toString(java.nio.charset.StandardCharsets.UTF_8);
            }
            return parse(body, algorithm);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // malformed sibling URI
        }
    }

    /**
     * Parses checksum file content: GNU style ({@code HEX  filename} or
     * {@code HEX *filename}), BSD style ({@code ALGO (name) = HEX}), or a
     * bare hex digest.
     *
     * @return the validated lowercase digest, or empty
     */
    static Optional<String> parse(String body, String algorithm) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        int expectedLength = HEX_LENGTHS.get(algorithm);
        for (String line : body.split("\\R+")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            // BSD style: SHA256 (file) = hex
            int eq = trimmed.lastIndexOf('=');
            if (eq >= 0) {
                String hex = trimmed.substring(eq + 1).trim();
                if (isValidHex(hex, expectedLength)) {
                    return Optional.of(hex.toLowerCase());
                }
            }
            // GNU style: HEX [ *]filename — or bare HEX
            String[] parts = trimmed.split("\\s+", 2);
            if (isValidHex(parts[0], expectedLength)) {
                return Optional.of(parts[0].toLowerCase());
            }
        }
        return Optional.empty();
    }

    private static boolean isValidHex(String token, int expectedLength) {
        return token.length() == expectedLength && HEX_TOKEN.matcher(token).matches();
    }
}
