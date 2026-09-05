package org.odm.gtk4;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.gnome.gdk.Texture;
import org.gnome.gdkpixbuf.PixbufLoader;
import org.gnome.gio.Icon;
import org.gnome.gio.ThemedIcon;
import org.manager.download.Download;

/** Human-readable GTK presentation for the native download engines. */
final class DownloadEnginePresentation {

    static final int ICON_SIZE = 20;
    static final int TOOLBAR_ICON_SIZE = 24;
    private static final String ICON_RESOURCE_DIRECTORY = "/images/engines/";
    private static final Map<Download.Type, Icon> ICONS =
            new EnumMap<>(Download.Type.class);
    private static Icon toolbarTorIcon;

    private DownloadEnginePresentation() {
    }

    static String displayName(Download.Type type) {
        return switch (type) {
            case ARIA2 -> "aria2";
            case CURL -> "curl";
            case YOUTUBE -> "yt-dlp";
            case WEBSITE_SCRAPING -> "HTTrack";
            case PROXYCHAINS -> "Proxychains";
            case TOR -> "Tor";
        };
    }

    static String iconName(Download.Type type) {
        return switch (type) {
            case ARIA2 -> "network-server-symbolic";
            case CURL -> "odm-engine-curl";
            case YOUTUBE -> "odm-engine-youtube";
            case WEBSITE_SCRAPING -> "odm-engine-httrack";
            case PROXYCHAINS -> "odm-engine-proxychains";
            case TOR -> "onion-icon-24";
        };
    }

    static Optional<String> iconResource(Download.Type type) {
        if (type == Download.Type.ARIA2) {
            return Optional.empty();
        }
        if (type == Download.Type.TOR) {
            return Optional.of("/images/onion-icon-24.svg");
        }
        return Optional.of(ICON_RESOURCE_DIRECTORY + iconName(type) + ".svg");
    }

    /**
     * Loads and caches an icon suitable for GtkImage and GtkCellRendererPixbuf.
     * aria2 uses GTK's standard server icon; engines with real logos use
     * bundled classpath resources.
     */
    static synchronized Icon icon(Download.Type type) {
        return ICONS.computeIfAbsent(type, DownloadEnginePresentation::loadIcon);
    }

    /** Bundled onion artwork at its native toolbar size. */
    static synchronized Icon toolbarTorIcon() {
        if (toolbarTorIcon == null) {
            toolbarTorIcon = loadBundledIcon("/images/onion-icon-24.svg", TOOLBAR_ICON_SIZE);
        }
        return toolbarTorIcon;
    }

    private static Icon loadIcon(Download.Type type) {
        if (type == Download.Type.ARIA2) {
            return new ThemedIcon(iconName(type));
        }
        String resource = iconResource(type).orElseThrow();
        return loadBundledIcon(resource, ICON_SIZE);
    }

    private static Icon loadBundledIcon(String resource, int size) {
        try (var input = DownloadEnginePresentation.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing engine icon resource: " + resource);
            }
            PixbufLoader loader = PixbufLoader.withType("svg");
            loader.setSize(size, size);
            loader.write(input.readAllBytes());
            loader.close();
            if (loader.getPixbuf() == null) {
                throw new IllegalStateException("Could not decode engine icon: " + resource);
            }
            return Texture.forPixbuf(loader.getPixbuf());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load engine icon: " + resource, e);
        }
    }
}
