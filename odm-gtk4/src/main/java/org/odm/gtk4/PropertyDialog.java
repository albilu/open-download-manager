package org.odm.gtk4;

import java.util.List;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.Window;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shows and edits the shared per-record Network Options for existing downloads. */
public class PropertyDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(PropertyDialog.class);

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final List<Download> downloads;
    private final org.tor.TorService torService;
    private final NetworkOptionsPane networkOptions;
    private final Label statusLabel;
    private final Button applyButton;
    private final Button okButton;
    private boolean applying;

    public PropertyDialog(Window parent, DownloadManager downloadManager, Download download) {
        this(parent, downloadManager, List.of(download), null);
    }

    public PropertyDialog(Window parent, DownloadManager downloadManager,
            List<Download> downloads) {
        this(parent, downloadManager, downloads, null);
    }

    public PropertyDialog(Window parent, DownloadManager downloadManager,
            List<Download> downloads, org.tor.TorService torService) {
        if (downloads == null || downloads.isEmpty()) {
            throw new IllegalArgumentException("At least one download is required");
        }
        this.downloadManager = downloadManager;
        this.downloads = List.copyOf(downloads);
        this.torService = torService;

        GtkBuilder builder = UiLoader.load("/ui/property.ui");
        this.dialog = Widgets.require(builder, "property_dialog", Window.class);
        this.statusLabel = Widgets.require(builder, "property_status_label", Label.class);
        this.applyButton = Widgets.require(builder, "apply_button", Button.class);
        this.okButton = Widgets.require(builder, "ok_button", Button.class);
        this.networkOptions = new NetworkOptionsPane(this.downloads, torService);
        networkOptions.bindTorService(torService);
        Widgets.require(builder, "property_network_options_host", Box.class)
                .append(networkOptions.widget());

        Download first = this.downloads.getFirst();
        DialogSupport.configureIndependent(dialog, parent);
        dialog.setTitle(this.downloads.size() == 1
                ? "Properties — " + first.getName()
                : "Properties — " + this.downloads.size() + " downloads");
        if (networkOptions.hasMixedValues()) {
            AccessibilitySupport.status(statusLabel,
                    "Mixed values are preserved unless you change their control.");
        }

        Widgets.require(builder, "cancel_button", Button.class).onClicked(dialog::close);
        applyButton.onClicked(() -> onApply(false));
        okButton.onClicked(() -> onApply(true));
    }

    public void present() {
        dialog.present();
        networkOptions.grabFocus();
    }

    private void onApply(boolean closeAfterSuccess) {
        if (applying) {
            return;
        }
        if (!networkOptions.hasChanges()) {
            if (closeAfterSuccess) {
                dialog.close();
            } else {
                AccessibilitySupport.status(statusLabel, "No settings changed");
            }
            return;
        }

        DialogOptions.NetworkValues network = networkOptions.values();
        PropertySettingsBatch.Values values = new PropertySettingsBatch.Values(
                network.connections(), network.downloadLimitKb(), network.uploadLimitKb(),
                network.maxRetries(), network.retryDelaySeconds(), network.referer(),
                network.userAgent(), network.cookie(), network.torActive(),
                network.torSocksPort(),
                network.proxyTypeIndex(), network.proxyHost(), network.proxyPort(),
                network.proxyUsername(), network.proxyPassword(),
                networkOptions.changedCapabilities(), networkOptions.isProxyChanged());
        applying = true;
        applyButton.setSensitive(false);
        okButton.setSensitive(false);
        AccessibilitySupport.status(statusLabel, "Applying settings…");
        DialogOptions.ensureTorAvailable(
                        networkOptions.isProxyChanged() && network.torActive(), torService)
                .thenCompose(ignored -> PropertySettingsBatch.apply(
                        downloads, downloadManager, values))
                .whenComplete((ignored, error) -> UiThread.marshal(() -> {
                    applying = false;
                    applyButton.setSensitive(true);
                    okButton.setSensitive(true);
                    if (error != null) {
                        LOGGER.warn("Failed to apply settings to selected downloads", error);
                        AccessibilitySupport.status(statusLabel,
                                "Could not apply settings: " + UiErrors.message(error)
                                        + ". Previous values were restored.",
                                org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                        return;
                    }
                    networkOptions.clearChanges();
                    LOGGER.info("Applied settings to " + downloads.size() + " download(s)");
                    if (closeAfterSuccess) {
                        dialog.close();
                    } else {
                        AccessibilitySupport.status(statusLabel, "Settings applied");
                    }
                }));
    }
}
