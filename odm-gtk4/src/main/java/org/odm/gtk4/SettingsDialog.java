package org.odm.gtk4;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnome.gio.File;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.Entry;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.Window;
import org.manager.GlobalSettings;
import org.manager.download.DownloadManager;

/**
 * Settings dialog (GTK4 port). Unlike the old dialog — where 25 of the wired
 * widget ids didn't exist and apply() silently persisted defaults — every
 * control here exists, and saving writes through GlobalSettings.save() to the
 * XDG config file and applies runtime toggles immediately.
 */
public class SettingsDialog {

    private static final Logger LOGGER = Logger.getLogger(SettingsDialog.class.getName());

    private final Window dialog;
    private final DownloadManager downloadManager;

    private final SpinButton maxConcurrentSpin;
    private final SpinButton speedLimitSpin;
    private final Label downloadDirLabel;
    private final CheckButton globalProxyCheck;
    private final Entry globalProxyEntry;
    private final CheckButton proxyRotationCheck;
    private final Entry proxyListEntry;
    private final SpinButton maxRetriesSpin;
    private final CheckButton clipboardCheck;
    private final CheckButton torrentFolderCheck;
    private final CheckButton metalinkFolderCheck;
    private final Label statusLabel;

    private Path selectedDownloadDir;

    public SettingsDialog(Window parent, DownloadManager downloadManager) {
        this.downloadManager = downloadManager;

        GtkBuilder builder = UiLoader.load("/ui/settings.ui");
        this.dialog = Widgets.require(builder, "settings_dialog", Window.class);
        this.maxConcurrentSpin = Widgets.require(builder, "max_concurrent_spin", SpinButton.class);
        this.speedLimitSpin = Widgets.require(builder, "speed_limit_spin", SpinButton.class);
        this.downloadDirLabel = Widgets.require(builder, "download_dir_label", Label.class);
        this.globalProxyCheck = Widgets.require(builder, "global_proxy_check", CheckButton.class);
        this.globalProxyEntry = Widgets.require(builder, "global_proxy_entry", Entry.class);
        this.proxyRotationCheck = Widgets.require(builder, "proxy_rotation_check", CheckButton.class);
        this.proxyListEntry = Widgets.require(builder, "proxy_list_entry", Entry.class);
        this.maxRetriesSpin = Widgets.require(builder, "max_retries_spin", SpinButton.class);
        this.clipboardCheck = Widgets.require(builder, "clipboard_check", CheckButton.class);
        this.torrentFolderCheck = Widgets.require(builder, "torrent_folder_check", CheckButton.class);
        this.metalinkFolderCheck = Widgets.require(builder, "metalink_folder_check", CheckButton.class);
        this.statusLabel = Widgets.require(builder, "settings_status_label", Label.class);

        dialog.setTransientFor(parent);
        loadFromSettings();

        Widgets.require(builder, "download_dir_button", Button.class)
                .onClicked(this::onChooseDownloadDir);
        Widgets.require(builder, "proxy_list_button", Button.class)
                .onClicked(this::onChooseProxyList);
        Widgets.require(builder, "settings_cancel_button", Button.class)
                .onClicked(dialog::close);
        Widgets.require(builder, "settings_save_button", Button.class)
                .onClicked(this::onSave);
        globalProxyCheck.onToggled(() -> globalProxyEntry.setSensitive(globalProxyCheck.getActive()));
        proxyRotationCheck.onToggled(this::updateRotationSensitivity);
    }

    public void present() {
        dialog.present();
    }

    private void loadFromSettings() {
        GlobalSettings settings = downloadManager.getGlobalSettings();
        maxConcurrentSpin.setValue(settings.getMaxConcurrentDownloads());
        speedLimitSpin.setValue(settings.getGlobalSpeedLimit());
        Path dir = settings.getDefaultDownloadDirectory();
        downloadDirLabel.setLabel(dir != null ? dir.toString() : "");
        globalProxyCheck.setActive(settings.isGlobalProxyEnabled());
        globalProxyEntry.setText(settings.getGlobalProxyAddress() != null
                ? settings.getGlobalProxyAddress() : "");
        globalProxyEntry.setSensitive(settings.isGlobalProxyEnabled());
        proxyRotationCheck.setActive(settings.isProxyRotationEnabled());
        proxyListEntry.setText(settings.getProxyListFilePath() != null
                ? settings.getProxyListFilePath() : "");
        maxRetriesSpin.setValue(settings.getProxyRotationMaxRetries());
        clipboardCheck.setActive(downloadManager.isClipboardMonitoringEnabled());
        torrentFolderCheck.setActive(downloadManager.isTorrentFolderMonitoringEnabled());
        metalinkFolderCheck.setActive(downloadManager.isMetaLinkFolderMonitoringEnabled());
        updateRotationSensitivity();
    }

    private void updateRotationSensitivity() {
        boolean rotation = proxyRotationCheck.getActive();
        proxyListEntry.setSensitive(rotation);
        maxRetriesSpin.setSensitive(rotation);
    }

    private void onChooseDownloadDir() {
        FileDialog fileDialog = new FileDialog();
        fileDialog.setTitle("Select download folder");
        fileDialog.selectFolder(dialog, null, result -> {
            try {
                File folder = fileDialog.selectFolderFinish(result);
                if (folder != null && folder.getPath() != null) {
                    selectedDownloadDir = Path.of(folder.getPath().toString());
                    downloadDirLabel.setLabel(selectedDownloadDir.toString());
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Folder selection cancelled or failed", e);
            }
        });
    }

    private void onChooseProxyList() {
        FileDialog fileDialog = new FileDialog();
        fileDialog.setTitle("Select proxy list file");
        fileDialog.open(dialog, null, result -> {
            try {
                File file = fileDialog.openFinish(result);
                if (file != null && file.getPath() != null) {
                    proxyListEntry.setText(file.getPath().toString());
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Proxy list selection cancelled or failed", e);
            }
        });
    }

    private void onSave() {
        GlobalSettings settings = downloadManager.getGlobalSettings();
        settings.setMaxConcurrentDownloads((int) maxConcurrentSpin.getValue());
        settings.setGlobalSpeedLimit((int) speedLimitSpin.getValue());
        if (selectedDownloadDir != null) {
            settings.setDefaultDownloadDirectory(selectedDownloadDir);
        }
        settings.setGlobalProxyEnabled(globalProxyCheck.getActive());
        settings.setGlobalProxyAddress(globalProxyEntry.getText().trim());
        settings.setProxyRotationEnabled(proxyRotationCheck.getActive());
        settings.setProxyListFilePath(proxyListEntry.getText().trim());
        settings.setProxyRotationMaxRetries((int) maxRetriesSpin.getValue());

        // Persist to the XDG config file
        settings.save();
        downloadManager.setGlobalSettings(settings);

        // Apply runtime feature toggles immediately
        downloadManager.setClipboardMonitoringEnabled(clipboardCheck.getActive());
        downloadManager.setTorrentFolderMonitoringEnabled(torrentFolderCheck.getActive());
        downloadManager.setMetaLinkFolderMonitoringEnabled(metalinkFolderCheck.getActive());

        statusLabel.setLabel("Settings saved.");
        LOGGER.info("Settings saved to " + GlobalSettings.getConfigFilePath());
        dialog.close();
    }
}
