package org.odm.ui.utils;

import java.net.URI;
import java.util.logging.Logger;

import org.jgtk.GladeUI;
import org.manager.download.Download;

/**
 * Utility class for common download operations.
 */
public final class DownloadUtils {

    private static final Logger LOGGER = Logger.getLogger(DownloadUtils.class.getName());

    private DownloadUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Determines the download type based on URL and UI settings.
     * 
     * @param uri URI to download
     * @param ui  GladeUI instance containing relevant settings widgets
     * @return The appropriate Download.Type for the URI
     */
    public static Download.Type determineDownloadType(URI uri, GladeUI ui) {
        try {
            // Check if TOR is enabled and URL is not torrent or magnet
            boolean torEnabled = ui.getSwitchActive("tor_switch");
            String scheme = uri.getScheme().toLowerCase();
            boolean isTorrentOrMagnet = scheme.equals("magnet")
                    || uri.toString().toLowerCase().contains(".torrent");

            if (torEnabled && !isTorrentOrMagnet) {
                return Download.Type.TOR;
            }

            // Check if proxy is defined
            int proxyType = ui.getComboBoxActive("proxy_type_combo");
            if (proxyType > 0) {
                return Download.Type.PROXYCHAINS;
            }

            // Check if URL is yt-dlp supported
            String urlString = uri.toString().toLowerCase();
            if (urlString.contains("youtube.com") || urlString.contains("youtu.be")
                    || urlString.contains("vimeo.com") || urlString.contains("dailymotion.com")) {
                return Download.Type.YOUTUBE;
            }

            // Default to ARIA2
            return Download.Type.ARIA2;
        } catch (Exception e) {
            LOGGER.severe("Error determining download type: " + e.getMessage());
            return Download.Type.ARIA2; // Default fallback
        }
    }

    /**
     * Applies UI settings to a download.
     * 
     * @param download Download to configure
     * @param ui       GladeUI instance containing relevant settings widgets
     */
    public static void applySettingsToDownload(Download download, GladeUI ui) {
        try {
            // This would typically set download-specific settings
            // Currently relies on global settings being applied by the download manager
            LOGGER.info("Applied settings to download: " + download.getId());
        } catch (Exception e) {
            LOGGER.severe("Error applying settings to download: " + e.getMessage());
        }
    }
}
