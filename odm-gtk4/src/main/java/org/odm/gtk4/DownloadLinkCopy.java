package org.odm.gtk4;

import java.util.ArrayList;
import java.util.List;
import org.manager.download.Download;

/** Clipboard content and wording for one complete download selection. */
record DownloadLinkCopy(List<String> links) {

    DownloadLinkCopy {
        links = List.copyOf(links);
    }

    static DownloadLinkCopy from(List<Download> downloads) {
        List<String> links = new ArrayList<>();
        if (downloads != null) {
            for (Download download : downloads) {
                String link = linkFor(download);
                if (link == null) {
                    // Do not silently copy only part of a selected group.
                    return new DownloadLinkCopy(List.of());
                }
                links.add(link);
            }
        }
        return new DownloadLinkCopy(links);
    }

    static String linkFor(Download download) {
        if (download == null) {
            return null;
        }
        if (download.getUri() != null
                && "magnet".equalsIgnoreCase(download.getUri().getScheme())) {
            return download.getUri().toString();
        }
        if (download.getInfoHash() != null && !download.getInfoHash().isBlank()) {
            return "magnet:?xt=urn:btih:" + download.getInfoHash();
        }
        // A torrent whose metadata is not available yet can still share its source.
        return download.getUri() == null ? null : download.getUri().toString();
    }

    boolean available() {
        return !links.isEmpty();
    }

    String text() {
        return String.join("\n", links);
    }

    String label() {
        return switch (noun()) {
            case "URL" -> I18n.plural("Copy URL", "Copy URLs", Math.max(1, links.size()));
            case "Magnet URI" -> I18n.plural("Copy Magnet URI", "Copy Magnet URIs", Math.max(1, links.size()));
            default -> I18n.plural("Copy Link", "Copy Links", Math.max(1, links.size()));
        };
    }

    String confirmation() {
        return switch (noun()) {
            case "URL" -> I18n.plural("URL copied", "%d URLs copied", links.size());
            case "Magnet URI" -> I18n.plural("Magnet URI copied", "%d Magnet URIs copied", links.size());
            default -> I18n.plural("Link copied", "%d Links copied", links.size());
        };
    }

    private String noun() {
        long magnets = links.stream().filter(link -> link.regionMatches(
                true, 0, "magnet:", 0, "magnet:".length())).count();
        return magnets == 0 ? "URL" : magnets == links.size() ? "Magnet URI" : "Link";
    }
}
