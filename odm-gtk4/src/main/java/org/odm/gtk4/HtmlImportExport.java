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
import java.util.regex.Pattern;
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
 * HTML import / export list logic: link and media source extraction from
 * documents, queueing the extracted links, and building/writing the
 * plain-text export. File I/O and parsing large documents are meant to
 * run off the GTK main loop — the dialog only orchestrates.
 */
final class HtmlImportExport {

    private static final Logger LOGGER = LoggerFactory.getLogger(HtmlImportExport.class);
    private static final Pattern SRCSET_DENSITY = Pattern.compile(
            "-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?x");

    private HtmlImportExport() {
    }

    /**
     * Extracts HTTP(S) anchor and media URLs, plus valid magnet anchor links.
     * Quoted and unquoted values are supported; relative values are resolved
     * against a valid http(s) base element. Comments, raw text, inert template
     * contents, empty hrefs and same-document fragments are not candidates.
     */
    static List<URI> extractLinks(String html) {
        return extractLinks(html, null, ImportLimits.defaults());
    }

    static List<URI> extractLinks(String html, URI documentUri) {
        return extractLinks(html, documentUri, ImportLimits.defaults());
    }

    static List<URI> extractLinks(String html, URI documentUri,
            ImportLimits limits) {
        ImportLimits effective = limits != null ? limits : ImportLimits.defaults();
        LinkedHashSet<URI> urls = new LinkedHashSet<>();
        if (html == null || html.isBlank()) {
            return new ArrayList<>();
        }
        Document document = Jsoup.parse(html);
        document.select("template").remove();
        URI base = extractBaseUri(document, documentUri);
        for (Element element : document.select(
                "a[href], video[src], audio[src], source[src], source[srcset], img[src], img[srcset]")) {
            boolean anchor = element.normalName().equals("a");
            addLink(element.attr(anchor ? "href" : "src"), base, anchor, urls, effective.maxUrls());
            if (element.normalName().equals("img") || element.normalName().equals("source")) {
                addSrcsetLinks(element.attr("srcset"), base, urls, effective.maxUrls());
            }
            if (urls.size() == effective.maxUrls()) {
                break;
            }
        }
        return new ArrayList<>(urls);
    }

    private static void addLink(String raw, URI base, boolean allowMagnet,
            LinkedHashSet<URI> urls, int maximumUrls) {
        raw = raw.strip();
        if (urls.size() >= maximumUrls || raw.isEmpty() || raw.startsWith("#")) {
            return;
        }
        try {
            var source = DownloadUrlPolicy.require(resolveReference(raw, base));
            if (source.isWeb() || allowMagnet && source.protocol() == Download.Protocol.MAGNET) {
                urls.add(source.uri());
            }
        } catch (IllegalArgumentException | URISyntaxException ignored) {
            // Validate complete references through the shared source policy.
        }
    }

    /**
     * Tokenizes srcset URLs and descriptors without splitting commas inside URLs
     * (including rejected data URLs). All valid candidates are offered for import.
     * See https://html.spec.whatwg.org/multipage/images.html#parsing-a-srcset-attribute.
     */
    private static void addSrcsetLinks(String srcset, URI base, LinkedHashSet<URI> urls, int maximumUrls) {
        int position = 0;
        while (position < srcset.length() && urls.size() < maximumUrls) {
            while (position < srcset.length()
                    && (isHtmlSpace(srcset.charAt(position)) || srcset.charAt(position) == ',')) {
                position++;
            }
            int start = position;
            while (position < srcset.length() && !isHtmlSpace(srcset.charAt(position))) {
                position++;
            }
            if (start == position) {
                break;
            }
            int end = position;
            if (srcset.charAt(end - 1) == ',') {
                while (end > start && srcset.charAt(end - 1) == ',') {
                    end--;
                }
                addLink(srcset.substring(start, end), base, false, urls, maximumUrls);
                continue;
            }
            int descriptorStart = position;
            boolean inParens = false;
            while (position < srcset.length()) {
                char value = srcset.charAt(position);
                if (value == ',' && !inParens) {
                    break;
                }
                if (value == '(') {
                    inParens = true;
                } else if (value == ')') {
                    inParens = false;
                }
                position++;
            }
            if (validSrcsetDescriptors(srcset.substring(descriptorStart, position))) {
                addLink(srcset.substring(start, end), base, false, urls, maximumUrls);
            }
            // Consume the candidate separator; the next iteration skips whitespace.
            if (position < srcset.length()) {
                position++;
            }
        }
    }

    private static boolean validSrcsetDescriptors(String raw) {
        int start = 0, end = raw.length();
        while (start < end && isHtmlSpace(raw.charAt(start))) { start++; }
        while (end > start && isHtmlSpace(raw.charAt(end - 1))) { end--; }
        if (start == end) {
            return true;
        }
        boolean width = false, height = false, density = false;
        // At most width and height are valid together. Bound token allocation
        // even when a malformed attribute contains thousands of descriptors.
        String[] descriptors = raw.substring(start, end).split("[\\t\\n\\f\\r ]+", 3);
        for (String descriptor : descriptors) {
            char kind = descriptor.charAt(descriptor.length() - 1);
            if (kind == 'w' && !width && !density && positiveIntegerDescriptor(descriptor)) {
                width = true;
            } else if (kind == 'h' && !height && !density && positiveIntegerDescriptor(descriptor)) {
                height = true;
            } else if (kind == 'x' && !density && !width && !height
                    && SRCSET_DENSITY.matcher(descriptor).matches()) {
                double value = Double.parseDouble(descriptor.substring(0, descriptor.length() - 1));
                if (!Double.isFinite(value) || value < 0) {
                    return false;
                }
                density = true;
            } else {
                return false;
            }
        }
        return !height || width;
    }

    private static boolean positiveIntegerDescriptor(String descriptor) {
        boolean positive = false;
        for (int i = 0; i < descriptor.length() - 1; i++) {
            char value = descriptor.charAt(i);
            if (value < '0' || value > '9') {
                return false;
            }
            positive |= value != '0';
        }
        return positive;
    }

    private static boolean isHtmlSpace(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f';
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
            return extractLinks(new String(bytes, StandardCharsets.UTF_8), null, effective)
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
        return fetchRemoteHtmlLinks(source, proxyAddress, limits, true);
    }

    static List<String> fetchRemoteHtmlLinks(URI source, String proxyAddress,
            ImportLimits limits, boolean verifyHttpsCertificates) {
        ImportLimits effective = limits != null ? limits : ImportLimits.defaults();
        URI normalizedSource = DownloadUrlPolicy.require(source).requireWeb().uri();
        try {
            BoundedHttpFetcher.FetchResult response = BoundedHttpFetcher.fetchResult(
                    normalizedSource, effective.maxSourceBytes(), Duration.ofSeconds(10),
                    Duration.ofSeconds(30), proxyAddress, verifyHttpsCertificates);
            if (!isHtmlContentType(response.contentType())) {
                throw new IllegalArgumentException("The remote source is not HTML");
            }
            Charset charset = responseCharset(response.contentType());
            String html = new String(response.body(), charset);
            return extractLinks(html, response.finalUri(), effective)
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
