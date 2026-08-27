package org.odm.gtk4;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private HtmlImportExport() {
    }

    /**
     * Extracts absolute http(s) links from an HTML document's href
     * attributes (single- or double-quoted). Non-absolute and invalid
     * hrefs are skipped.
     */
    static List<URI> extractHttpLinks(String html) {
        List<URI> urls = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("href\\s*=\\s*[\"']([^\"']+)[\"']",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        while (m.find()) {
            try {
                URI uri = new URI(m.group(1));
                if (uri.getScheme() != null && uri.getScheme().startsWith("http")) {
                    urls.add(uri);
                }
            } catch (Exception ignored) {
                // non-absolute/invalid href: skip
            }
        }
        return urls;
    }

    /**
     * Reads an HTML file and queues a download for every extracted link.
     *
     * @return the number of queued downloads, or -1 when the file could not
     *         be read
     */
    static int importHtmlFile(Path path, DownloadOperations operations) {
        try {
            List<URI> urls = extractHttpLinks(Files.readString(path));
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
