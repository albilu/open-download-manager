package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CompletableFuture;
import org.gnome.gtk.Button;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.Window;
import org.gnome.gobject.Value;
import org.javagi.gobject.types.Types;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

/**
 * Import URL Sequence dialog — 1:1 GTK4 port of import-sequence.glade. The
 * user enters a URL pattern with a {} placeholder plus a numeric or character
 * range; the preview list shows the generated URLs; Start Download queues
 * them all.
 */
public class ImportSequenceDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportSequenceDialog.class);
    private static final String[] RANGE_MODES = {"Number", "Character"};
    static final int MAX_IMPORT_URLS = 1_000;

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onImportDone;
    private final GtkBuilder builder;

    private final Entry uriEntry;
    private final SpinButton numStartSpin;
    private final SpinButton numVersSpin;
    private final SpinButton numCountSpin;
    private final Entry charEntry;
    private final Entry charVersEntry;
    private final DropDown numModeCombo;
    private final DropDown charModeCombo;
    private final ListStore previewStore;
    private final Label diskSpaceLabel;
    private final PathChooserButton destinationChooser;

    private Path destinationFolder;
    private boolean syncingRangeMode;

    public ImportSequenceDialog(Window parent, DownloadManager downloadManager, Runnable onImportDone) {
        this.downloadManager = downloadManager;
        this.onImportDone = onImportDone;

        this.builder = UiLoader.load("/ui/import-sequence.ui");
        this.dialog = Widgets.require(builder, "import_sequence_dialog", Window.class);
        this.uriEntry = Widgets.require(builder, "uri_entry", Entry.class);
        this.numStartSpin = Widgets.require(builder, "num_start_spin", SpinButton.class);
        this.numVersSpin = Widgets.require(builder, "num_vers_spin", SpinButton.class);
        this.numCountSpin = Widgets.require(builder, "num_count_spin", SpinButton.class);
        this.charEntry = Widgets.require(builder, "char_entry", Entry.class);
        this.charVersEntry = Widgets.require(builder, "char_vers_entry", Entry.class);
        this.numModeCombo = Widgets.require(builder, "num_combo", DropDown.class);
        this.charModeCombo = Widgets.require(builder, "char_combo", DropDown.class);
        this.previewStore = Widgets.require(builder, "preview_liststore", ListStore.class);
        this.diskSpaceLabel = Widgets.require(builder, "disk_space_label", Label.class);

        AccessibilitySupport.label(uriEntry, "URL sequence pattern");
        AccessibilitySupport.label(numStartSpin, "Sequence start number");
        AccessibilitySupport.label(numVersSpin, "Sequence end number");
        AccessibilitySupport.label(numCountSpin, "Maximum generated URLs");
        AccessibilitySupport.label(charEntry, "Sequence start character");
        AccessibilitySupport.label(charVersEntry, "Sequence end character");

        dialog.setTransientFor(parent);

        StringList modes = new StringList(new String[0]);
        for (String mode : RANGE_MODES) {
            modes.append(mode);
        }
        numModeCombo.setModel(modes);
        charModeCombo.setModel(modes);
        numModeCombo.setSelected(0);
        charModeCombo.setSelected(0);
        StringList proxyTypes = new StringList(new String[0]);
        for (String type : DialogOptions.PROXY_TYPES) {
            proxyTypes.append(type);
        }
        Widgets.require(builder, "proxy_type_combo", DropDown.class).setModel(proxyTypes);
        loadGlobalDefaults();

        MenuButton destinationButton = Widgets.require(builder, "destination_folder", MenuButton.class);
        AccessibilitySupport.label(destinationButton, "Sequence destination folder");
        Path defaultDestination = Path.of(currentDefaultDirectory());
        this.destinationFolder = defaultDestination;
        this.destinationChooser = PathChooserButton.forFolder(destinationButton, dialog,
                "Select destination folder", defaultDestination, path -> {
                    destinationFolder = path;
                    updateDiskSpace(path.toString());
                });
        updateDiskSpace(defaultDestination.toString());

        // Regenerate preview on any input change
        Runnable regen = this::regeneratePreview;
        uriEntry.onChanged(() -> regen.run());
        numStartSpin.onValueChanged(() -> regen.run());
        numVersSpin.onValueChanged(() -> regen.run());
        numCountSpin.onValueChanged(() -> regen.run());
        charEntry.onChanged(() -> regen.run());
        charVersEntry.onChanged(() -> regen.run());
        numModeCombo.onNotify("selected", pspec -> syncRangeMode(numModeCombo, charModeCombo));
        charModeCombo.onNotify("selected", pspec -> syncRangeMode(charModeCombo, numModeCombo));

        Widgets.require(builder, "cancel_button", Button.class).onClicked(dialog::close);
        Widgets.require(builder, "validate_button", Button.class).onClicked(this::onImport);

        syncRangeMode(numModeCombo, charModeCombo);
    }

    public void present() {
        dialog.present();
        uriEntry.grabFocus();
    }

    /** Generates the URL list from the pattern + range. */
    private List<String> generateUrls() {
        return generateSequence(uriEntry.getText().trim(), numModeCombo.getSelected() == 1,
                (int) numStartSpin.getValue(), (int) numVersSpin.getValue(),
                charEntry.getText().trim(), charVersEntry.getText().trim(),
                (int) numCountSpin.getValue());
    }

    private void syncRangeMode(DropDown source, DropDown other) {
        if (syncingRangeMode) {
            return;
        }
        syncingRangeMode = true;
        try {
            other.setSelected(source.getSelected());
            boolean characterMode = source.getSelected() == 1;
            numStartSpin.setSensitive(!characterMode);
            numVersSpin.setSensitive(!characterMode);
            charEntry.setSensitive(characterMode);
            charVersEntry.setSensitive(characterMode);
            regeneratePreview();
        } finally {
            syncingRangeMode = false;
        }
    }

    static List<String> generateSequence(String pattern, boolean characterMode,
            int start, int end, String charFrom, String charTo, int requestedCount) {
        List<String> urls = new ArrayList<>();
        if (pattern.isEmpty() || !pattern.contains("{}")) {
            return urls;
        }
        int count = Math.min(MAX_IMPORT_URLS,
                Math.max(1, requestedCount));

        if (characterMode) {
            if (charFrom == null || charTo == null
                    || charFrom.length() != 1 || charTo.length() != 1) {
                return urls;
            }
            char a = charFrom.charAt(0);
            char b = charTo.charAt(0);
            int direction = a <= b ? 1 : -1;
            for (int c = a; urls.size() < count; c += direction) {
                urls.add(pattern.replace("{}", String.valueOf((char) c)));
                if (c == b) {
                    break;
                }
            }
            return urls;
        }

        if (end < start) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        for (int i = start; i <= end && urls.size() < count; i++) {
            urls.add(pattern.replace("{}", String.valueOf(i)));
        }
        return urls;
    }

    private void regeneratePreview() {
        previewStore.clear();
        for (String url : generateUrls()) {
            TreeIter iter = new TreeIter();
            previewStore.append(iter);
            Value v = new Value().init(Types.STRING);
            v.setString(url);
            previewStore.setValue(iter, 0, v);
            v.unset();
        }
    }

    private void onImport() {
        List<String> urls = generateUrls();
        if (urls.isEmpty()) {
            return;
        }
        Path destination = destinationFolder != null ? destinationFolder
                : Path.of(currentDefaultDirectory());
        ImportOptions options = captureOptions();
        Widgets.require(builder, "validate_button", Button.class).setSensitive(false);
        CompletableFuture.supplyAsync(() -> queueUrls(urls, destination, options))
                .whenComplete((queued, error) -> UiThread.marshal(() -> {
                    if (error != null) {
                        LOGGER.warn("URL sequence import failed", error);
                    } else {
                        LOGGER.info("Imported " + queued + " downloads from URL sequence");
                        if (onImportDone != null) {
                            onImportDone.run();
                        }
                    }
                    dialog.close();
                }));
    }

    private ImportOptions captureOptions() {
        return new ImportOptions(
                Widgets.require(builder, "tor_switch", Switch.class).getActive(),
                (int) Widgets.require(builder, "proxy_type_combo", DropDown.class).getSelected(),
                Widgets.require(builder, "proxy_host_entry", Entry.class).getText(),
                (int) Widgets.require(builder, "proxy_port_spin", SpinButton.class).getValue(),
                Widgets.require(builder, "proxy_username_entry", Entry.class).getText(),
                Widgets.require(builder, "proxy_password_entry", Entry.class).getText(),
                (int) Widgets.require(builder, "max_connections_spin", SpinButton.class).getValue(),
                (int) Widgets.require(builder, "max_download_speed_spin", SpinButton.class).getValue(),
                (int) Widgets.require(builder, "max_upload_speed_spin", SpinButton.class).getValue(),
                (int) Widgets.require(builder, "retry_limit_spin", SpinButton.class).getValue(),
                (int) Widgets.require(builder, "retry_after", SpinButton.class).getValue());
    }

    private int queueUrls(List<String> urls, Path destination, ImportOptions options) {
        int queued = 0;
        for (String url : urls.stream().limit(MAX_IMPORT_URLS).toList()) {
            try {
                Download download = downloadManager.createDownload(
                        org.manager.clipboard.UrlDetector.requireValidDownloadUrl(url), destination);
                options.apply(download);
                downloadManager.queueDownload(download);
                queued++;
            } catch (Exception e) {
                LOGGER.debug("Skipped an invalid URL-sequence entry", e);
            }
        }
        return queued;
    }

    private record ImportOptions(boolean tor, int proxyType, String proxyHost, int proxyPort,
            String proxyUser, String proxyPassword, int connections, int downloadLimitKb,
            int uploadLimitKb, int retries, int retryDelay) {
        void apply(Download download) {
            DialogOptions.applyProxy(download, tor, proxyType, proxyHost, proxyPort,
                    proxyUser, proxyPassword);
            DialogOptions.applyCommon(download.getSettings(), connections, downloadLimitKb,
                    uploadLimitKb, retries, retryDelay, null, null, null);
        }
    }

    private void loadGlobalDefaults() {
        org.manager.GlobalSettings settings = downloadManager.getGlobalSettings();
        Widgets.require(builder, "max_connections_spin", SpinButton.class)
                .setValue(settings.getIntProperty("aria2.maxConnections", 8));
        Widgets.require(builder, "max_download_speed_spin", SpinButton.class)
                .setValue(settings.getIntProperty("aria2.maxDownloadSpeedKb", 0));
        Widgets.require(builder, "max_upload_speed_spin", SpinButton.class)
                .setValue(settings.getIntProperty("aria2.maxUploadSpeedKb", 0));
        Widgets.require(builder, "retry_limit_spin", SpinButton.class)
                .setValue(settings.getIntProperty("aria2.maxTries", 5));
        Widgets.require(builder, "retry_after", SpinButton.class)
                .setValue(settings.getIntProperty("aria2.retryWait", 0));
        Widgets.require(builder, "tor_switch", Switch.class)
                .setActive(settings.getBooleanProperty("tor.enabled", false));

        DialogOptions.ProxyFields proxy = settings.isGlobalProxyEnabled()
                ? DialogOptions.parseProxy(settings.getGlobalProxyAddress())
                : DialogOptions.ProxyFields.none();
        Widgets.require(builder, "proxy_type_combo", DropDown.class).setSelected(proxy.typeIndex());
        Widgets.require(builder, "proxy_host_entry", Entry.class).setText(proxy.host());
        Widgets.require(builder, "proxy_port_spin", SpinButton.class).setValue(proxy.port());
        Widgets.require(builder, "proxy_username_entry", Entry.class).setText(proxy.username());
        Widgets.require(builder, "proxy_password_entry", Entry.class).setText(proxy.password());
    }

    private void updateDiskSpace(String dir) {
        try {
            long free = new java.io.File(dir).getUsableSpace();
            diskSpaceLabel.setLabel(String.format("%.2f GB free", free / (1024.0 * 1024 * 1024)));
        } catch (Exception e) {
            diskSpaceLabel.setLabel("");
        }
    }

    private String currentDefaultDirectory() {
        Path dir = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
        return dir != null ? dir.toString() : System.getProperty("user.home") + "/Downloads";
    }
}
