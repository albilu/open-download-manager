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

        AccessibilitySupport.label(maxConnectionsSpin, "Maximum connections");
        AccessibilitySupport.label(retryLimitSpin, "Retry limit");
        AccessibilitySupport.label(maxDownloadSpeedSpin,
                "Maximum download speed in KiB per second");
        AccessibilitySupport.label(maxUploadSpeedSpin,
                "Maximum upload speed in KiB per second");
        AccessibilitySupport.label(proxyTypeCombo, "Proxy type");
        AccessibilitySupport.label(proxyHostEntry, "Proxy host");
        AccessibilitySupport.label(proxyPortSpin, "Proxy port");
        AccessibilitySupport.label(proxyUsernameEntry, "Proxy username");
        AccessibilitySupport.label(proxyPasswordEntry, "Proxy password");
        AccessibilitySupport.label(torSwitch, "Route this download through Tor");

        dialog.setTransientFor(parent);
        dialog.setTitle("Properties — " + download.getName());

        StringList proxyTypes = new StringList(new String[0]);
        for (String type : DialogOptions.PROXY_TYPES) {
            proxyTypes.append(type);
        }
        proxyTypeCombo.setModel(proxyTypes);

        CheckButton startAutomatically = Widgets.require(builder,
                "start_automatically_check", CheckButton.class);
        CheckButton moveTorrent = Widgets.require(builder, "move_torrent_check", CheckButton.class);
        startAutomatically.setSensitive(false);
        startAutomatically.setTooltipText("Starting is controlled from the main download list");
        moveTorrent.setSensitive(false);
        moveTorrent.setTooltipText("Descriptor movement only applies while adding a new download");

        loadCurrentSettings();
        applyCapabilities();

        Widgets.require(builder, "cancel_button", Button.class).onClicked(dialog::close);
        Widgets.require(builder, "apply_button", Button.class).onClicked(this::onApply);
        Widgets.require(builder, "ok_button", Button.class).onClicked(() -> {
            onApply();
            dialog.close();
        });
    }

    public void present() {
        dialog.present();
        maxConnectionsSpin.grabFocus();
    }

    private void loadCurrentSettings() {
        // Engine-neutral seam: works for every engine (aria2 options,
        // typed curl/httrack/yt-dlp fields, or persisted-neutral defaults)
        org.manager.download.ExternalToolSettings settings =
                download.getSettings();
        maxConnectionsSpin.setValue(settings.getMaxConnections());
        maxDownloadSpeedSpin.setValue(settings.getDownloadLimitKB());
        maxUploadSpeedSpin.setValue(settings.getUploadLimitKB());
        retryLimitSpin.setValue(settings.getMaxRetries());
        retryAfterSpin.setValue(settings.getRetryDelaySeconds());
        if (settings.getReferer() != null) {
            referrerEntry.setText(settings.getReferer());
        }
        if (settings.getUserAgent() != null) {
            userAgentEntry.setText(settings.getUserAgent());
        }
        if (settings.getCookieHeader() != null) {
            cookieEntry.setText(settings.getCookieHeader().replaceFirst("(?i)^Cookie:\\s*", ""));
        }
        DialogOptions.ProxyFields proxy = download.isUseProxy()
                ? DialogOptions.parseProxy(download.getProxyAddress())
                : DialogOptions.ProxyFields.none();
        boolean torProxy = proxy.typeIndex() == 4
                && "127.0.0.1".equals(proxy.host()) && proxy.port() == 9050;
        torSwitch.setActive(torProxy);
        proxyTypeCombo.setSelected(torProxy ? 0 : proxy.typeIndex());
        proxyHostEntry.setText(torProxy ? "" : proxy.host());
        proxyPortSpin.setValue(torProxy ? 0 : proxy.port());
        proxyUsernameEntry.setText(torProxy ? "" : proxy.username());
        proxyPasswordEntry.setText(torProxy ? "" : proxy.password());
    }

    private void onApply() {
        org.manager.download.ExternalToolSettings settings = download.getSettings();
        DialogOptions.applyCommon(settings,
                (int) maxConnectionsSpin.getValue(),
                (int) maxDownloadSpeedSpin.getValue(),
                (int) maxUploadSpeedSpin.getValue(),
                (int) retryLimitSpin.getValue(),
                (int) retryAfterSpin.getValue(),
                referrerEntry.getText(), userAgentEntry.getText(), cookieEntry.getText());
        DialogOptions.applyProxy(download, torSwitch.getActive(),
                (int) proxyTypeCombo.getSelected(), proxyHostEntry.getText(),
                (int) proxyPortSpin.getValue(), proxyUsernameEntry.getText(),
                proxyPasswordEntry.getText());
        downloadManager.changeSettings(download)
                .thenRun(() -> LOGGER.info("Applied settings to download: " + download.getName()))
                .exceptionally(e -> {
                    LOGGER.log(Level.WARNING, "Failed to apply settings to " + download.getName(), e);
                    return null;
                });
    }

    private void applyCapabilities() {
        org.manager.download.ExternalToolSettings settings = download.getSettings();
        configureCapability(maxConnectionsSpin, settings,
                org.manager.download.ExternalToolSettings.Capability.CONNECTIONS, "connections");
        configureCapability(maxDownloadSpeedSpin, settings,
                org.manager.download.ExternalToolSettings.Capability.DOWNLOAD_LIMIT, "download limits");
        configureCapability(maxUploadSpeedSpin, settings,
                org.manager.download.ExternalToolSettings.Capability.UPLOAD_LIMIT, "upload limits");
        configureCapability(retryLimitSpin, settings,
                org.manager.download.ExternalToolSettings.Capability.MAX_RETRIES, "retry limits");
        configureCapability(retryAfterSpin, settings,
                org.manager.download.ExternalToolSettings.Capability.RETRY_DELAY, "retry delays");
        configureCapability(referrerEntry, settings,
                org.manager.download.ExternalToolSettings.Capability.REFERER, "HTTP referers");
        configureCapability(userAgentEntry, settings,
                org.manager.download.ExternalToolSettings.Capability.USER_AGENT, "user agents");
        configureCapability(cookieEntry, settings,
                org.manager.download.ExternalToolSettings.Capability.COOKIE, "cookie headers");
    }

    private static void configureCapability(org.gnome.gtk.Widget widget,
            org.manager.download.ExternalToolSettings settings,
            org.manager.download.ExternalToolSettings.Capability capability,
            String description) {
        boolean supported = settings.supports(capability);
        widget.setSensitive(supported);
        widget.setTooltipText(supported ? null
                : "This download engine does not support " + description);
    }
}
