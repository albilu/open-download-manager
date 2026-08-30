package org.manager.download;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.List;
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
    private static final List<Map.Entry<String, String>> CHECKSUM_CANDIDATES = List.of(
            Map.entry("sha256", ".sha256"),
            Map.entry("sha512", ".sha512"),
            Map.entry("sha1", ".sha1"),
            Map.entry("md5", ".md5"));

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
        return probe(url, null);
    }

    /** Probes through the supplied proxy when configured. */
    public static Optional<DetectedChecksum> probe(URI url, String proxyAddress) {
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
        String expectedFilename = PathName.of(url.getPath());
        for (Map.Entry<String, String> candidate : CHECKSUM_CANDIDATES) {
            URI sibling = siblingUri(url, candidate.getValue());
            Optional<String> checksum = fetchAndParse(
                    sibling, candidate.getKey(), expectedFilename, proxyAddress);
            if (checksum.isPresent()) {
                return Optional.of(new DetectedChecksum(
                        candidate.getKey(), checksum.get(), sibling));
            }
        }
        return Optional.empty();
    }

    static URI siblingUri(URI url, String extension) {
        StringBuilder value = new StringBuilder(url.getScheme()).append("://")
                .append(url.getRawAuthority()).append(url.getRawPath()).append(extension);
        if (url.getRawQuery() != null) {
            value.append('?').append(url.getRawQuery());
        }
        return URI.create(value.toString());
    }

    /**
     * Fetches a sibling checksum file and extracts the digest for the base
     * filename when the file lists multiple entries.
     */
    private static Optional<String> fetchAndParse(URI sibling, String algorithm,
            String expectedFilename, String proxyAddress) {
        try {
            byte[] bytes = org.manager.tools.BoundedHttpFetcher.fetch(sibling,
                    MAX_CHECKSUM_FILE_BYTES, Duration.ofSeconds(5), Duration.ofSeconds(8),
                    proxyAddress);
            String body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return parse(body, algorithm, expectedFilename);
        } catch (IOException e) {
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
        return parse(body, algorithm, null);
    }

    static Optional<String> parse(String body, String algorithm, String expectedFilename) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        Integer expectedLength = HEX_LENGTHS.get(algorithm);
        if (expectedLength == null) {
            return Optional.empty();
        }
        Optional<String> bareDigest = Optional.empty();
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
                    int open = trimmed.indexOf('(');
                    int close = trimmed.lastIndexOf(')', eq);
                    String filename = open >= 0 && close > open
                            ? trimmed.substring(open + 1, close).trim() : null;
                    if (expectedFilename == null || filenameMatches(filename, expectedFilename)) {
                        return Optional.of(hex.toLowerCase());
                    }
                }
            }
            // GNU style: HEX [ *]filename — or bare HEX
            String[] parts = trimmed.split("\\s+", 2);
            if (isValidHex(parts[0], expectedLength)) {
                String digest = parts[0].toLowerCase();
                if (parts.length == 1) {
                    bareDigest = Optional.of(digest);
                } else {
                    String filename = parts[1].strip();
                    if (filename.startsWith("*")) {
                        filename = filename.substring(1);
                    }
                    if (expectedFilename == null || filenameMatches(filename, expectedFilename)) {
                        return Optional.of(digest);
                    }
                }
            }
        }
        return bareDigest;
    }

    private static boolean filenameMatches(String listed, String expected) {
        if (listed == null || listed.isBlank()) {
            return false;
        }
        String normalized = listed.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return basename.equals(expected);
    }

    private static final class PathName {
        static String of(String path) {
            if (path == null || path.isBlank()) {
                return null;
            }
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }

    private static boolean isValidHex(String token, int expectedLength) {
        return token.length() == expectedLength && HEX_TOKEN.matcher(token).matches();
    }
}
