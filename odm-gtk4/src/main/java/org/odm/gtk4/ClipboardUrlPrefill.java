package org.odm.gtk4;

import java.net.URI;
import java.util.Optional;
import java.util.function.Predicate;
import org.gnome.gdk.Clipboard;
import org.gnome.gtk.Entry;
import org.gnome.gtk.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Asynchronously prefills blank URL fields from the desktop clipboard. */
final class ClipboardUrlPrefill {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClipboardUrlPrefill.class);

    private ClipboardUrlPrefill() {
    }

    /**
     * Reads the GTK session clipboard without blocking the main loop and
     * inserts the first URL accepted by the dialog's input policy. Existing
     * text is never replaced.
     */
    static void populate(Window owner, Entry entry, Predicate<URI> accepts) {
        if (owner == null || entry == null || accepts == null
                || !entry.getText().isBlank()) {
            return;
        }
        try {
            Clipboard clipboard = owner.getClipboard();
            clipboard.readTextAsync(null, result -> {
                try {
                    String content = clipboard.readTextFinish(result);
                    Optional<URI> candidate = firstMatchingUrl(content, accepts);
                    if (owner.getVisible() && entry.getText().isBlank()) {
                        candidate.ifPresent(uri -> entry.setText(uri.toString()));
                    }
                } catch (Throwable failure) {
                    LOGGER.debug("Could not prefill URL from clipboard", failure);
                }
            });
        } catch (Throwable failure) {
            LOGGER.debug("Could not schedule clipboard URL prefill", failure);
        }
    }

    static Optional<URI> firstMatchingUrl(String clipboardContent,
            Predicate<URI> accepts) {
        if (accepts == null) {
            return Optional.empty();
        }
        return org.manager.clipboard.UrlDetector.extractUrls(clipboardContent).stream()
                .filter(accepts)
                .findFirst();
    }

    static boolean isWebPage(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null
                || uri.getHost().isBlank()) {
            return false;
        }
        return "http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme());
    }
}
