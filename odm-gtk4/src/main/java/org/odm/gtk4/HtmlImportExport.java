package org.odm.gtk4;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.manager.download.Download;
import org.manager.download.DownloadOperations;

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
        LinkedHashSet<URI> urls = new LinkedHashSet<>();
        URI base = extractBaseUri(html);
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

    private static URI extractBaseUri(String html) {
        java.util.regex.Matcher matcher = BASE_PATTERN.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        try {
            URI base = org.manager.clipboard.UrlDetector.requireValidDownloadUrl(
                    decodeHtmlEntities(firstGroup(matcher)));
            return "http".equalsIgnoreCase(base.getScheme())
                    || "https".equalsIgnoreCase(base.getScheme()) ? base : null;
        } catch (Exception ignored) {
            return null;
        }
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
            List<URI> urls = extractHttpLinks(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            int queued = 0;
            for (URI uri : urls) {
                try {
                    operations.queueDownload(operations.createDownload(uri, null));
                    queued++;
                } catch (Exception ignored) {
                    // skip
                }
            }
            return queued;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "HTML import failed", e);
            return -1;
        }
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
