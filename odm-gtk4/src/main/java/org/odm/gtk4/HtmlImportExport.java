package org.odm.gtk4;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.manager.download.Download;
import org.manager.download.DownloadOperations;
import org.manager.tools.BoundedHttpFetcher;

/**
 * HTML import / export list logic: href extraction from arbitrary
 * documents, queueing the extracted links, and building/writing the
 * plain-text export. File I/O and regex over large documents are meant to
 * run off the GTK main loop — the dialog only orchestrates.
 */
final class HtmlImportExport {

    private static final Logger LOGGER = Logger.getLogger(HtmlImportExport.class.getName());
    static final long MAX_HTML_BYTES = 8L * 1024 * 1024;
    static final int MAX_IMPORT_LINKS = 1_000;
    private static final java.util.regex.Pattern HREF_PATTERN = java.util.regex.Pattern.compile(
            "<a\\b[^>]*?\\s+href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern BASE_PATTERN = java.util.regex.Pattern.compile(
            "<base\\b[^>]*?\\s+href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private HtmlImportExport() {
    }

    /**
     * Extracts http(s) links from an HTML document's anchor href attributes.
     * Quoted and unquoted values are supported; relative values are resolved
     * against a valid http(s) base element. Invalid values are skipped.
     */
    static List<URI> extractHttpLinks(String html) {
        return extractHttpLinks(html, null);
    }

    static List<URI> extractHttpLinks(String html, URI documentUri) {
        LinkedHashSet<URI> urls = new LinkedHashSet<>();
        URI base = extractBaseUri(html, documentUri);
        java.util.regex.Matcher m = HREF_PATTERN.matcher(html);
        while (m.find() && urls.size() < MAX_IMPORT_LINKS) {
            try {
                String raw = decodeHtmlEntities(firstGroup(m));
                URI candidate;
                if (raw.startsWith("//")) {
                    candidate = base != null ? base.resolve(raw) : URI.create("https:" + raw);
                } else {
                    candidate = new URI(raw);
                    if (!candidate.isAbsolute()) {
                        if (base == null) {
                            continue;
                        }
                        candidate = base.resolve(candidate);
                    }
                }
                URI uri = org.manager.clipboard.UrlDetector.requireValidDownloadUri(candidate);
                if ("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme())) {
                    urls.add(uri);
                }
            } catch (Exception ignored) {
                // non-absolute/invalid href: skip
            }
        }
        return new ArrayList<>(urls);
    }

    private static URI extractBaseUri(String html, URI documentUri) {
        java.util.regex.Matcher matcher = BASE_PATTERN.matcher(html);
        if (!matcher.find()) {
            return isHttp(documentUri) ? documentUri : null;
        }
        try {
            URI base = new URI(decodeHtmlEntities(firstGroup(matcher)));
            if (!base.isAbsolute() && isHttp(documentUri)) {
                base = documentUri.resolve(base);
            }
            return isHttp(base)
                    ? org.manager.clipboard.UrlDetector.requireValidDownloadUri(base)
                    : isHttp(documentUri) ? documentUri : null;
        } catch (Exception ignored) {
            return isHttp(documentUri) ? documentUri : null;
        }
    }

    private static boolean isHttp(URI uri) {
        return uri != null && ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private static String firstGroup(java.util.regex.Matcher matcher) {
        for (int i = 1; i <= 3; i++) {
            if (matcher.group(i) != null) {
                return matcher.group(i);
            }
        }
        return "";
    }

    private static String decodeHtmlEntities(String value) {
        String decoded = value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        java.util.regex.Matcher numeric = java.util.regex.Pattern
                .compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(decoded);
        StringBuffer result = new StringBuffer();
        while (numeric.find()) {
            try {
                String token = numeric.group(1);
                int codePoint = token.startsWith("x") || token.startsWith("X")
                        ? Integer.parseInt(token.substring(1), 16)
                        : Integer.parseInt(token);
                numeric.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
                        Character.toString(codePoint)));
            } catch (IllegalArgumentException invalidEntity) {
                numeric.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(numeric.group()));
            }
        }
        numeric.appendTail(result);
        return result.toString();
    }

    /**
     * Reads an HTML file and queues a download for every extracted link.
     *
     * @return the number of queued downloads, or -1 when the file could not
     *         be read
     */
    static int importHtmlFile(Path path, DownloadOperations operations) {
        try {
            byte[] bytes;
            try (java.io.InputStream input = Files.newInputStream(path)) {
                bytes = input.readNBytes(Math.toIntExact(MAX_HTML_BYTES) + 1);
            }
            if (bytes.length > MAX_HTML_BYTES) {
                return -1;
            }
            List<URI> urls = extractHttpLinks(new String(bytes, StandardCharsets.UTF_8));
            return queueLinks(urls, operations);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "HTML import failed", e);
            return -1;
        }
    }

    /**
     * Fetches a remote HTML document and queues its links. Redirects are
     * followed by the bounded HTTP client; the final document URI is the
     * relative-link base when no valid base element is present.
     */
    static int importRemoteHtml(URI source, DownloadOperations operations,
            String proxyAddress) {
        if (!isHttp(source)) {
            return -1;
        }
        try {
            BoundedHttpFetcher.FetchResult response = BoundedHttpFetcher.fetchResult(
                    source, MAX_HTML_BYTES, Duration.ofSeconds(10),
                    Duration.ofSeconds(30), proxyAddress);
            if (!isHtmlContentType(response.contentType())) {
                return -1;
            }
            Charset charset = responseCharset(response.contentType());
            String html = new String(response.body(), charset);
            return queueLinks(extractHttpLinks(html, response.finalUri()), operations);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Remote HTML import failed", e);
            return -1;
        }
    }

    private static int queueLinks(List<URI> urls, DownloadOperations operations) {
        int queued = 0;
        for (URI uri : urls) {
            try {
                operations.queueDownload(operations.createDownload(uri, null));
                queued++;
            } catch (Exception ignored) {
                // A single rejected link must not abort the remainder.
            }
        }
        return queued;
    }

    private static boolean isHtmlContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String mediaType = contentType.split(";", 2)[0].strip().toLowerCase();
        return mediaType.equals("text/html")
                || mediaType.equals("application/xhtml+xml");
    }

    private static Charset responseCharset(String contentType) {
        if (contentType != null) {
            for (String parameter : contentType.split(";")) {
                String value = parameter.strip();
                if (value.regionMatches(true, 0, "charset=", 0, 8)) {
                    try {
                        return Charset.forName(value.substring(8).strip()
                                .replace("\"", ""));
                    } catch (Exception ignored) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** One download URL per line for the export list. */
    static String exportText(List<Download> downloads) {
        StringBuilder sb = new StringBuilder();
        for (Download d : downloads) {
            if (d.getUri() != null) {
                sb.append(d.getUri()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Writes the export text; the caller surfaces failures. */
    static void writeText(Path path, String contents) throws IOException {
        Files.writeString(path, contents);
    }
}
