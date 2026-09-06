package org.odm.gtk4;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.manager.download.Download;
import org.manager.download.DownloadOperations;
import org.manager.tools.BoundedHttpFetcher;
import org.manager.url.DownloadUrlPolicy;

/**
 * HTML import / export list logic: href extraction from arbitrary
 * documents, queueing the extracted links, and building/writing the
 * plain-text export. File I/O and parsing large documents are meant to
 * run off the GTK main loop — the dialog only orchestrates.
 */
final class HtmlImportExport {

    private static final Logger LOGGER = LoggerFactory.getLogger(HtmlImportExport.class);

    private HtmlImportExport() {
    }

    /**
     * Extracts http(s) links from an HTML document's anchor href attributes.
     * Quoted and unquoted values are supported; relative values are resolved
     * against a valid http(s) base element. Comments, raw text, inert template
     * contents, empty hrefs and same-document fragments are not candidates.
     */
    static List<URI> extractHttpLinks(String html) {
        return extractHttpLinks(html, null, ImportLimits.defaults());
    }

    static List<URI> extractHttpLinks(String html, URI documentUri) {
        return extractHttpLinks(html, documentUri, ImportLimits.defaults());
    }

    static List<URI> extractHttpLinks(String html, URI documentUri,
            ImportLimits limits) {
        ImportLimits effective = limits != null ? limits : ImportLimits.defaults();
        LinkedHashSet<URI> urls = new LinkedHashSet<>();
        if (html == null || html.isBlank()) {
            return new ArrayList<>();
        }
        Document document = Jsoup.parse(html);
        document.select("template").remove();
        URI base = extractBaseUri(document, documentUri);
        for (Element anchor : document.select("a[href]")) {
            String raw = anchor.attr("href").strip();
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            try {
                URI candidate = resolveReference(raw, base);
                urls.add(DownloadUrlPolicy.require(candidate).requireWeb().uri());
                if (urls.size() == effective.maxUrls()) {
                    break;
                }
            } catch (IllegalArgumentException | URISyntaxException ignored) {
                // An HTML href is a reference; only valid web sources are admitted.
            }
        }
        return new ArrayList<>(urls);
    }

    private static URI extractBaseUri(Document document, URI documentUri) {
        URI fallback = null;
        try {
            fallback = DownloadUrlPolicy.require(documentUri).requireWeb().uri();
        } catch (IllegalArgumentException ignored) {
            // Local imports have no web document URL.
        }
        Element base = document.selectFirst("base[href]");
        if (base == null) {
            return fallback;
        }
        // Only the first real base href counts, including when it is invalid.
        try {
            return DownloadUrlPolicy.require(resolveReference(base.attr("href").strip(), fallback))
                    .requireWeb().uri();
        } catch (IllegalArgumentException | URISyntaxException ignored) {
            return fallback;
        }
    }

    private static URI resolveReference(String raw, URI base) throws URISyntaxException {
        URI reference = new URI(raw);
        if (reference.isAbsolute()) {
            return reference;
        }
        if (base == null) {
            return raw.startsWith("//") ? new URI("https:" + raw) : null;
        }
        // URI.resolve treats a query-only reference as a sibling directory.
        // HTML query links retain the document path and replace its query.
        if (raw.startsWith("?")) {
            return new URI(base.toString().split("[?#]", 2)[0] + raw);
        }
        return raw.isEmpty() ? base : base.resolve(reference);
    }

    /**
     * Reads an HTML file and queues a download for every extracted link.
     *
     * @return the number of queued downloads, or -1 when the file could not
     *         be read
     */
    static int importHtmlFile(Path path, DownloadOperations operations) {
        return importHtmlFile(path, operations, ImportLimits.defaults());
    }

    static int importHtmlFile(Path path, DownloadOperations operations,
            ImportLimits limits) {
        try {
            return queueLinkStrings(readHtmlLinks(path, limits), operations);
        } catch (Exception e) {
            LOGGER.debug("HTML import failed", e);
            return -1;
        }
    }

    /** Reads and validates a local HTML source without creating records yet. */
    static List<String> readHtmlLinks(Path path, ImportLimits limits) {
        ImportLimits effective = limits != null ? limits : ImportLimits.defaults();
        try {
            byte[] bytes;
            try (java.io.InputStream input = Files.newInputStream(path)) {
                bytes = input.readNBytes(Math.toIntExact(effective.maxSourceBytes()) + 1);
            }
            if (bytes.length > effective.maxSourceBytes()) {
                throw new IllegalArgumentException("HTML source exceeds the "
                        + effective.maxSourceSizeMiB() + " MiB import limit");
            }
            return extractHttpLinks(new String(bytes, StandardCharsets.UTF_8), null, effective)
                    .stream().map(URI::toString).toList();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Could not read the selected HTML file", error);
        }
    }

    /**
     * Fetches a remote HTML document and queues its links. Redirects are
     * followed by the bounded HTTP client; the final document URI is the
     * relative-link base when no valid base element is present.
     */
    static int importRemoteHtml(URI source, DownloadOperations operations,
            String proxyAddress) {
        return importRemoteHtml(source, operations, proxyAddress,
                ImportLimits.defaults());
    }

    static int importRemoteHtml(URI source, DownloadOperations operations,
            String proxyAddress, ImportLimits limits) {
        try {
            return queueLinkStrings(fetchRemoteHtmlLinks(source, proxyAddress, limits),
                    operations);
        } catch (Exception e) {
            LOGGER.debug("Remote HTML import failed", e);
            return -1;
        }
    }

    /** Fetches and validates a remote HTML source without creating records yet. */
    static List<String> fetchRemoteHtmlLinks(URI source, String proxyAddress,
            ImportLimits limits) {
        ImportLimits effective = limits != null ? limits : ImportLimits.defaults();
        URI normalizedSource = DownloadUrlPolicy.require(source).requireWeb().uri();
        try {
            BoundedHttpFetcher.FetchResult response = BoundedHttpFetcher.fetchResult(
                    normalizedSource, effective.maxSourceBytes(), Duration.ofSeconds(10),
                    Duration.ofSeconds(30), proxyAddress);
            if (!isHtmlContentType(response.contentType())) {
                throw new IllegalArgumentException("The remote source is not HTML");
            }
            Charset charset = responseCharset(response.contentType());
            String html = new String(response.body(), charset);
            return extractHttpLinks(html, response.finalUri(), effective)
                    .stream().map(URI::toString).toList();
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Could not fetch the remote HTML source", error);
        }
    }

    private static int queueLinkStrings(List<String> urls, DownloadOperations operations) {
        return DownloadSubmission.queueUrls(operations, urls, null, download -> { }, urls.size());
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
