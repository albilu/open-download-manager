package org.odm.gtk4;

import org.ytdlp.YtDlpSettings;

/** Localized labels are independent of the values persisted or passed to yt-dlp. */
final class MediaPresentation {
    private MediaPresentation() { }

    static String container(YtDlpSettings.ContainerProfile profile) {
        return switch (profile) {
            case AUTOMATIC -> I18n.tr("Automatic / best compatible");
            case MP4_COMPATIBLE -> I18n.tr("MP4 (prefer H.264/AAC)");
            case MKV -> "MKV";
            case PRESERVE_NATIVE -> I18n.tr("Preserve native formats");
        };
    }

    static String browser(YtDlpSettings.BrowserCookieSource source) {
        return source == YtDlpSettings.BrowserCookieSource.NONE ? I18n.tr("None") : source.displayName();
    }

    static String sponsorBlock(YtDlpSettings.SponsorBlockMode mode) {
        return switch (mode) {
            case OFF -> I18n.tr("Off");
            case MARK -> I18n.tr("Mark segments as chapters");
            case REMOVE -> I18n.tr("Remove segments");
        };
    }
}
