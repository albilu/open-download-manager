package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.CompletableFuture;
import org.gnome.gio.File;
import org.gnome.gtk.Button;
import org.gnome.gtk.CellRendererToggle;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.Window;
import org.gnome.gobject.Value;
import org.javagi.gobject.types.Types;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

/**
 * Import-from-text-file dialog — 1:1 GTK4 port of import-list.glade. Loads a
 * URL list into the url treeview (mark toggle + extension column), filters by
 * extension, and queues the marked downloads with the Options-tab settings.
 * The old dialog's execution path was a TODO shell; this one executes.
 */
public class ImportListDialog {

    private static final Logger LOGGER = Logger.getLogger(ImportListDialog.class.getName());
    static final int MAX_IMPORT_URLS = 1_000;
    static final long MAX_IMPORT_FILE_BYTES = 8L * 1024 * 1024;

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onImportDone;
    private final GtkBuilder builder;

    private final ListStore urlStore;
    private final ListStore extensionFilterStore;
    private final DropDown extensionFilterCombo;
    private final Label diskSpaceLabel;

    private Path destinationFolder;

    public ImportListDialog(Window parent, DownloadManager downloadManager, Runnable onImportDone) {
        this.downloadManager = downloadManager;
        this.onImportDone = onImportDone;

        this.builder = UiLoader.load("/ui/import-list.ui");
        this.dialog = Widgets.require(builder, "import_dialog", Window.class);
        this.urlStore = Widgets.require(builder, "url_liststore", ListStore.class);
        this.extensionFilterStore = Widgets.require(builder, "extension_filter_store", ListStore.class);
        this.extensionFilterCombo = Widgets.require(builder, "extension_filter_combo", DropDown.class);
        this.diskSpaceLabel = Widgets.require(builder, "disk_space_label", Label.class);

        AccessibilitySupport.label(extensionFilterCombo, "Imported URL extension filter");

        dialog.setTransientFor(parent);

        StringList proxyTypes = new StringList(new String[0]);
        for (String type : DialogOptions.PROXY_TYPES) {
            proxyTypes.append(type);
        }
        Widgets.require(builder, "proxy_type_combo", DropDown.class).setModel(proxyTypes);
        loadGlobalDefaults();

        Button folderButton = Widgets.require(builder, "folder_destination", Button.class);
        folderButton.setLabel(currentDefaultDirectory());
        folderButton.onClicked(this::onChooseFolder);

        // Extension filter model (rebuilt when URLs are loaded)
        rebuildExtensionFilter(List.of());
        extensionFilterCombo.onNotify("selected", pspec -> applyExtensionFilter());

        // Mark toggle renderer: flip the row's mark column
        CellRendererToggle markRenderer = Widgets.require(builder, "mark_renderer", CellRendererToggle.class);
        markRenderer.onToggled(pathStr -> {
            TreeIter iter = new TreeIter();
            if (urlStore.getIter(iter, TreePath.fromString(pathStr))) {
                boolean current = ListStoreCells.getBoolean(urlStore, iter, 0);
                Value nv = new Value().init(Types.BOOLEAN);
                nv.setBoolean(!current);
                urlStore.setValue(iter, 0, nv);
                nv.unset();
            }
        });

        Widgets.require(builder, "cancel_button", Button.class).onClicked(dialog::close);
        Widgets.require(builder, "validate_button", Button.class).onClicked(this::onImport);

        // Load-from-file is the entry action: trigger it on open
        onFromFile();
    }

    public void present() {
        dialog.present();
    }

    private void onChooseFolder() {
        FileDialog fileDialog = new FileDialog();
        fileDialog.setTitle("Select destination folder");
        fileDialog.selectFolder(dialog, null, result -> {
            try {
                File folder = fileDialog.selectFolderFinish(result);
                if (folder != null && folder.getPath() != null) {
                    destinationFolder = Path.of(folder.getPath().toString());
                    Widgets.require(builder, "folder_destination", Button.class)
                            .setLabel(destinationFolder.toString());
                    updateDiskSpace(destinationFolder.toString());
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Folder selection cancelled or failed", e);
            }
        });
    }

    private void onFromFile() {
        FileDialog fileDialog = new FileDialog();
        fileDialog.setTitle("Select URL list file");
        fileDialog.open(dialog, null, result -> {
            try {
                File file = fileDialog.openFinish(result);
                if (file != null && file.getPath() != null) {
                    Path path = Path.of(file.getPath().toString());
                    CompletableFuture.supplyAsync(() -> readImportLines(path))
                            .whenComplete((lines, error) -> UiThread.marshal(() -> {
                                if (error == null) {
                                    loadUrls(lines);
                                } else {
                                    LOGGER.log(Level.WARNING, "URL list import was rejected", error);
                                    AccessibilitySupport.status(diskSpaceLabel,
                                            "List is too large or unreadable",
                                            org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                                }
                            }));
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "List file selection cancelled or failed", e);
            }
        });
    }

    static List<String> readImportLines(Path path) {
        try {
            byte[] bytes;
            try (java.io.InputStream input = Files.newInputStream(path)) {
                bytes = input.readNBytes(Math.toIntExact(MAX_IMPORT_FILE_BYTES) + 1);
            }
            if (bytes.length > MAX_IMPORT_FILE_BYTES) {
                throw new IllegalArgumentException("URL list exceeds the 8 MiB limit");
            }
            try (java.util.stream.Stream<String> stream = new String(bytes,
                    java.nio.charset.StandardCharsets.UTF_8).lines()) {
                List<String> lines = stream.map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .limit(MAX_IMPORT_URLS + 1L)
                        .toList();
                if (lines.size() > MAX_IMPORT_URLS) {
                    throw new IllegalArgumentException("URL list exceeds the 1,000 URL limit");
                }
                return lines;
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void loadUrls(List<String> lines) {
        urlStore.clear();
        List<String> extensions = new ArrayList<>();
        for (String rawLine : lines.stream().limit(MAX_IMPORT_URLS).toList()) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String ext = extensionOf(line);
            TreeIter iter = new TreeIter();
            urlStore.append(iter);
            setBool(urlStore, iter, 0, true);
            setStr(urlStore, iter, 1, line);
            setStr(urlStore, iter, 2, ext);
            if (!ext.isEmpty() && !extensions.contains(ext)) {
                extensions.add(ext);
            }
        }
        rebuildExtensionFilter(extensions);
        LOGGER.info("Loaded " + Math.min(lines.size(), MAX_IMPORT_URLS) + " URLs into import list");
    }

    private void rebuildExtensionFilter(List<String> extensions) {
        StringList list = new StringList(new String[0]);
        list.append("(all)");
        for (String ext : extensions) {
            list.append(ext);
        }
        extensionFilterCombo.setModel(list);
    }

    private void applyExtensionFilter() {
        // The treeview shows all rows; the filter only toggles which rows are marked.
        long selected = extensionFilterCombo.getSelected();
        if (selected == 0) {
            return; // "(all)": nothing to change
        }
        String filterExt = extensionFilterCombo.getSelectedItem() instanceof org.gnome.gtk.StringObject so
                ? so.getString()
                : null;
        if (filterExt == null) {
            return;
        }
        TreeIter iter = new TreeIter();
        if (urlStore.getIterFirst(iter)) {
            do {
                String ext = ListStoreCells.getString(urlStore, iter, 2);
                Value nv = new Value().init(Types.BOOLEAN);
                nv.setBoolean(filterExt.equals(ext));
                urlStore.setValue(iter, 0, nv);
                nv.unset();
            } while (urlStore.iterNext(iter));
        }
    }

    private void onImport() {
        Path destination = destinationFolder != null ? destinationFolder
                : Path.of(currentDefaultDirectory());
        List<String> urls = new ArrayList<>();
        TreeIter iter = new TreeIter();
        if (urlStore.getIterFirst(iter)) {
            do {
                boolean isMarked = ListStoreCells.getBoolean(urlStore, iter, 0);
                if (!isMarked) {
                    continue;
                }
                String url = ListStoreCells.getString(urlStore, iter, 1);
                urls.add(url);
            } while (urlStore.iterNext(iter));
        }
        ImportOptions options = captureOptions();
        Widgets.require(builder, "validate_button", Button.class).setSensitive(false);
        CompletableFuture.supplyAsync(() -> queueUrls(urls, destination, options))
                .whenComplete((queued, error) -> UiThread.marshal(() -> {
                    if (error != null) {
                        LOGGER.log(Level.WARNING, "List import failed", error);
                    } else {
                        LOGGER.info("Imported " + queued + " downloads from list");
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
                Widgets.require(builder, "start_automatically_check1", CheckButton.class).getActive());
    }

    private int queueUrls(List<String> urls, Path destination, ImportOptions options) {
        int queued = 0;
        for (String url : urls.stream().limit(MAX_IMPORT_URLS).toList()) {
            try {
                Download download = downloadManager.createDownload(new URI(url), destination);
                options.apply(download);
                if (options.startAutomatically()) {
                    downloadManager.queueDownload(download);
                }
                queued++;
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Skipped an invalid URL-list entry", e);
            }
        }
        return queued;
    }

    private record ImportOptions(boolean tor, int proxyType, String proxyHost, int proxyPort,
            String proxyUser, String proxyPassword, int connections, int downloadLimitKb,
            boolean startAutomatically) {
        void apply(Download download) {
            DialogOptions.applyProxy(download, tor, proxyType, proxyHost, proxyPort,
                    proxyUser, proxyPassword);
            DialogOptions.applyCommon(download.getSettings(), connections, downloadLimitKb,
                    0, 0, 0, null, null, null);
        }
    }

    private void loadGlobalDefaults() {
        org.manager.GlobalSettings settings = downloadManager.getGlobalSettings();
        Widgets.require(builder, "max_connections_spin", SpinButton.class)
                .setValue(settings.getIntProperty("aria2.maxConnections", 8));
        Widgets.require(builder, "max_download_speed_spin", SpinButton.class)
                .setValue(settings.getIntProperty("aria2.maxDownloadSpeedKb", 0));
        Widgets.require(builder, "start_automatically_check1", CheckButton.class)
                .setActive(settings.getBooleanProperty("ui.startAutomatically", true));
        CheckButton moveDescriptor = Widgets.require(builder, "move_torrent_check1", CheckButton.class);
        moveDescriptor.setActive(false);
        moveDescriptor.setSensitive(false);
        moveDescriptor.setTooltipText("This URL-list importer does not move local descriptor files");
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

    private static String extensionOf(String url) {
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return (dot > slash && dot >= 0) ? path.substring(dot + 1) : "";
    }

    private String currentDefaultDirectory() {
        Path dir = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
        return dir != null ? dir.toString() : System.getProperty("user.home") + "/Downloads";
    }

    private static void setStr(ListStore store, TreeIter iter, int column, String value) {
        Value v = new Value().init(Types.STRING);
        v.setString(value);
        store.setValue(iter, column, v);
        v.unset();
    }

    private static void setBool(ListStore store, TreeIter iter, int column, boolean value) {
        Value v = new Value().init(Types.BOOLEAN);
        v.setBoolean(value);
        store.setValue(iter, column, v);
        v.unset();
    }
}
