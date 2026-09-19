package org.odm.gtk4;

import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.url.DownloadUrlPolicy;

/** Optional engine override for a batch of newly imported records. */
enum ImportEngine {
    AUTO(I18n.mark("Auto"), null),
    ARIA2(I18n.mark("HTTP/Torrent"), Download.Type.ARIA2),
    YT_DLP(I18n.mark("Media"), Download.Type.YOUTUBE),
    HTTRACK(I18n.mark("Web Scrap"), Download.Type.WEBSITE_SCRAPING);

    private final String label;
    private final Download.Type type;

    ImportEngine(String label, Download.Type type) {
        this.label = label;
        this.type = type;
    }

    String label() { return I18n.tr(label); }

    Download.Type type() { return type; }

    void validate(DownloadUrlPolicy.ValidatedSource source) {
        try {
            switch (this) {
                case YT_DLP, HTTRACK -> source.requireWeb();
                case AUTO, ARIA2 -> { }
            }
        } catch (IllegalArgumentException incompatible) {
            throw new IllegalArgumentException(label() + ": " + UiErrors.message(incompatible));
        }
    }

    void apply(Download download, DownloadSettingsFactory settingsFactory) {
        if (this != AUTO) {
            download.setType(type);
            // The manager has already initialized the auto-detected engine.
            // Replace its settings before applying the dialog's common options.
            download.setSettings(settingsFactory.createSettings(type, download.getProtocol()));
        }
    }
}
