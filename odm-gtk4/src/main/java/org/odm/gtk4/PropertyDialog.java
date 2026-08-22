package org.odm.gtk4;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.Window;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

/**
 * Download Properties dialog — 1:1 GTK4 port of property.glade. Shows and
 * edits an existing download's settings; Apply/OK push changes through the
 * handler's changeSettings (implemented in Step 1: aria2 via changeOption RPC,
 * process tools via restart-with-new-settings).
 */
public class PropertyDialog {

    private static final Logger LOGGER = Logger.getLogger(PropertyDialog.class.getName());
    private static final String[] PROXY_TYPES = {"None", "HTTP", "HTTPS", "SOCKS4", "SOCKS5"};

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Download download;

    private final SpinButton maxConnectionsSpin;
    private final SpinButton retryLimitSpin;
    private final SpinButton maxDownloadSpeedSpin;
    private final SpinButton maxUploadSpeedSpin;
    private final SpinButton retryAfterSpin;
    private final Entry referrerEntry;
    private final Entry cookieEntry;
    private final Entry userAgentEntry;
    private final DropDown proxyTypeCombo;
    private final Entry proxyHostEntry;
    private final SpinButton proxyPortSpin;
    private final Entry proxyUsernameEntry;
    private final Entry proxyPasswordEntry;
    private final Switch torSwitch;

    public PropertyDialog(Window parent, DownloadManager downloadManager, Download download) {
        this.downloadManager = downloadManager;
        this.download = download;

        GtkBuilder builder = UiLoader.load("/ui/property.ui");
        this.dialog = Widgets.require(builder, "property_dialog", Window.class);
        this.maxConnectionsSpin = Widgets.require(builder, "max_connections_spin", SpinButton.class);
        this.retryLimitSpin = Widgets.require(builder, "retry_limit_spin", SpinButton.class);
        this.maxDownloadSpeedSpin = Widgets.require(builder, "max_download_speed_spin", SpinButton.class);
        this.maxUploadSpeedSpin = Widgets.require(builder, "max_upload_speed_spin", SpinButton.class);
        this.retryAfterSpin = Widgets.require(builder, "retry_after", SpinButton.class);
        this.referrerEntry = Widgets.require(builder, "referrer", Entry.class);
        this.cookieEntry = Widgets.require(builder, "cookie", Entry.class);
        this.userAgentEntry = Widgets.require(builder, "user_agent", Entry.class);
        this.proxyTypeCombo = Widgets.require(builder, "proxy_type_combo", DropDown.class);
        this.proxyHostEntry = Widgets.require(builder, "proxy_host_entry", Entry.class);
        this.proxyPortSpin = Widgets.require(builder, "proxy_port_spin", SpinButton.class);
        this.proxyUsernameEntry = Widgets.require(builder, "proxy_username_entry", Entry.class);
        this.proxyPasswordEntry = Widgets.require(builder, "proxy_password_entry", Entry.class);
        this.torSwitch = Widgets.require(builder, "tor_switch", Switch.class);

        dialog.setTransientFor(parent);
        dialog.setTitle("Properties — " + download.getName());

        StringList proxyTypes = new StringList(new String[0]);
        for (String type : PROXY_TYPES) {
            proxyTypes.append(type);
        }
        proxyTypeCombo.setModel(proxyTypes);

        loadCurrentSettings();

        Widgets.require(builder, "cancel_button", Button.class).onClicked(dialog::close);
        Widgets.require(builder, "apply_button", Button.class).onClicked(this::onApply);
        Widgets.require(builder, "ok_button", Button.class).onClicked(() -> {
            onApply();
            dialog.close();
        });
    }

    public void present() {
        dialog.present();
    }

    private void loadCurrentSettings() {
        // Engine-neutral seam: works for every engine (aria2 options,
        // typed curl/httrack/yt-dlp fields, or persisted-neutral defaults)
        org.manager.download.ExternalToolSettings settings =
                download.getSettings();
        maxConnectionsSpin.setValue(settings.getMaxConnections());
        maxDownloadSpeedSpin.setValue(settings.getDownloadLimitKB());
        maxUploadSpeedSpin.setValue(settings.getUploadLimitKB());
        if (settings.getMaxRetries() > 0) {
            retryLimitSpin.setValue(settings.getMaxRetries());
        }
        if (settings.getRetryDelaySeconds() > 0) {
            retryAfterSpin.setValue(settings.getRetryDelaySeconds());
        }
        if (settings.getReferer() != null) {
            referrerEntry.setText(settings.getReferer());
        }
        if (settings.getUserAgent() != null) {
            userAgentEntry.setText(settings.getUserAgent());
        }
        if (settings.getCookieHeader() != null && settings.getCookieHeader().startsWith("Cookie: ")) {
            cookieEntry.setText(settings.getCookieHeader().substring("Cookie: ".length()));
        }
    }

    private void onApply() {
        org.manager.download.ExternalToolSettings settings = download.getSettings();
        settings.setMaxConnections((int) maxConnectionsSpin.getValue());
        settings.setDownloadLimitKB((int) maxDownloadSpeedSpin.getValue());
        int upKb = (int) maxUploadSpeedSpin.getValue();
        if (upKb > 0) {
            settings.setUploadLimitKB(upKb);
        }
        int retry = (int) retryLimitSpin.getValue();
        if (retry > 0) {
            settings.setMaxRetries(retry);
        }
        int retryWait = (int) retryAfterSpin.getValue();
        if (retryWait > 0) {
            settings.setRetryDelaySeconds(retryWait);
        }
        if (!referrerEntry.getText().isBlank()) {
            settings.setReferer(referrerEntry.getText().trim());
        }
        if (!userAgentEntry.getText().isBlank()) {
            settings.setUserAgent(userAgentEntry.getText().trim());
        }
        if (!cookieEntry.getText().isBlank()) {
            settings.setCookieHeader("Cookie: " + cookieEntry.getText().trim());
        }
        downloadManager.changeSettings(download)
                .thenRun(() -> LOGGER.info("Applied settings to download: " + download.getName()))
                .exceptionally(e -> {
                    LOGGER.log(Level.WARNING, "Failed to apply settings to " + download.getName(), e);
                    return null;
                });
    }
}
