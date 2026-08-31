package org.odm.gtk4;

import org.manager.download.Download;

/** Human-readable GTK presentation for the native download engines. */
final class DownloadEnginePresentation {

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
            case CURL -> "network-wired-symbolic";
            case YOUTUBE -> "video-x-generic-symbolic";
            case WEBSITE_SCRAPING -> "edit-find-symbolic";
            case PROXYCHAINS -> "network-proxy-symbolic";
            case TOR -> "network-wireless-symbolic";
        };
    }
}
