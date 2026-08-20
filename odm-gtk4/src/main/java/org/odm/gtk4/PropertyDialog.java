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
        if (download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings) {
            maxConnectionsSpin.setValue(aria2Settings.getMaxConnectionPerServer());
            String downLimit = aria2Settings.getOption("max-download-limit");
            if (downLimit != null) {
                maxDownloadSpeedSpin.setValue(Long.parseLong(downLimit) / 1024.0);
            }
            String upLimit = aria2Settings.getOption("max-upload-limit");
            if (upLimit != null) {
                maxUploadSpeedSpin.setValue(Long.parseLong(upLimit) / 1024.0);
            }
            String retry = aria2Settings.getOption("max-tries");
            if (retry != null) {
                retryLimitSpin.setValue(Integer.parseInt(retry));
            }
            String retryWait = aria2Settings.getOption("retry-wait");
            if (retryWait != null) {
                retryAfterSpin.setValue(Integer.parseInt(retryWait));
            }
            String referer = aria2Settings.getOption("referer");
            if (referer != null) {
                referrerEntry.setText(referer);
            }
            String ua = aria2Settings.getOption("user-agent");
            if (ua != null) {
                userAgentEntry.setText(ua);
            }
            String cookieHeader = aria2Settings.getOption("header");
            if (cookieHeader != null && cookieHeader.startsWith("Cookie: ")) {
                cookieEntry.setText(cookieHeader.substring("Cookie: ".length()));
            }
        }
    }

    private void onApply() {
        if (download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings) {
            aria2Settings.setMaxConnectionPerServer((int) maxConnectionsSpin.getValue());
            int downKb = (int) maxDownloadSpeedSpin.getValue();
            aria2Settings.setOption("max-download-limit", downKb > 0 ? String.valueOf(downKb * 1024L) : "0");
            int upKb = (int) maxUploadSpeedSpin.getValue();
            if (upKb > 0) {
                aria2Settings.setOption("max-upload-limit", String.valueOf(upKb * 1024L));
            }
            int retry = (int) retryLimitSpin.getValue();
            if (retry > 0) {
                aria2Settings.setOption("max-tries", String.valueOf(retry));
            }
            int retryWait = (int) retryAfterSpin.getValue();
            if (retryWait > 0) {
                aria2Settings.setOption("retry-wait", String.valueOf(retryWait));
            }
            if (!referrerEntry.getText().isBlank()) {
                aria2Settings.setOption("referer", referrerEntry.getText().trim());
            }
            if (!userAgentEntry.getText().isBlank()) {
                aria2Settings.setOption("user-agent", userAgentEntry.getText().trim());
            }
            if (!cookieEntry.getText().isBlank()) {
                aria2Settings.setOption("header", "Cookie: " + cookieEntry.getText().trim());
            }
        }
        downloadManager.changeSettings(download)
                .thenRun(() -> LOGGER.info("Applied settings to download: " + download.getName()))
                .exceptionally(e -> {
                    LOGGER.log(Level.WARNING, "Failed to apply settings to " + download.getName(), e);
                    return null;
                });
    }
}
