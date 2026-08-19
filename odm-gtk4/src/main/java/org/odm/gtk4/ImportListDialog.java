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
    private static final String[] PROXY_TYPES = {"None", "HTTP", "SOCKS4", "SOCKS5"};

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

        dialog.setTransientFor(parent);

        StringList proxyTypes = new StringList(new String[0]);
        for (String type : PROXY_TYPES) {
            proxyTypes.append(type);
        }
        Widgets.require(builder, "proxy_type_combo", DropDown.class).setModel(proxyTypes);

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
                Value v = new Value().init(Types.BOOLEAN);
                urlStore.getValue(iter, 0, v);
                boolean current = v.getBoolean();
                v.unset();
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
                    loadUrls(Files.readAllLines(Path.of(file.getPath().toString())));
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "List file selection cancelled or failed", e);
            }
        });
    }

    private void loadUrls(List<String> lines) {
        urlStore.clear();
        List<String> extensions = new ArrayList<>();
        for (String rawLine : lines) {
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
        LOGGER.info("Loaded " + lines.size() + " lines into import list");
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
                Value v = new Value().init(Types.STRING);
                urlStore.getValue(iter, 2, v);
                String ext = v.getString();
                v.unset();
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
        int queued = 0;
        TreeIter iter = new TreeIter();
        if (urlStore.getIterFirst(iter)) {
            do {
                Value marked = new Value().init(Types.BOOLEAN);
                urlStore.getValue(iter, 0, marked);
                boolean isMarked = marked.getBoolean();
                marked.unset();
                if (!isMarked) {
                    continue;
                }
                Value urlVal = new Value().init(Types.STRING);
                urlStore.getValue(iter, 1, urlVal);
                String url = urlVal.getString();
                urlVal.unset();
                try {
                    Download download = downloadManager.createDownload(new URI(url), destination);
                    applyOptions(download);
                    downloadManager.queueDownload(download);
                    queued++;
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to queue " + url, e);
                }
            } while (urlStore.iterNext(iter));
        }
        LOGGER.info("Imported " + queued + " downloads from list");
        if (onImportDone != null) {
            onImportDone.run();
        }
        dialog.close();
    }

    private void applyOptions(Download download) {
        if (Widgets.require(builder, "tor_switch", Switch.class).getActive()) {
            download.setUseProxy(true);
            download.setProxyAddress("socks5://127.0.0.1:9050");
        } else if (Widgets.require(builder, "proxy_type_combo", DropDown.class).getSelected() > 0
                && !Widgets.require(builder, "proxy_host_entry", Entry.class).getText().isBlank()) {
            String type = PROXY_TYPES[(int) Widgets.require(builder, "proxy_type_combo", DropDown.class)
                    .getSelected()].toLowerCase();
            String host = Widgets.require(builder, "proxy_host_entry", Entry.class).getText().trim();
            int port = (int) Widgets.require(builder, "proxy_port_spin", SpinButton.class).getValue();
            String user = Widgets.require(builder, "proxy_username_entry", Entry.class).getText().trim();
            String pass = Widgets.require(builder, "proxy_password_entry", Entry.class).getText();
            StringBuilder proxy = new StringBuilder(type).append("://");
            if (!user.isEmpty()) {
                proxy.append(user);
                if (!pass.isEmpty()) {
                    proxy.append(':').append(pass);
                }
                proxy.append('@');
            }
            proxy.append(host).append(':').append(port);
            download.setUseProxy(true);
            download.setProxyAddress(proxy.toString());
        }

        if (download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings) {
            aria2Settings.setMaxConnectionPerServer(
                    (int) Widgets.require(builder, "max_connections_spin", SpinButton.class).getValue());
            int downKb = (int) Widgets.require(builder, "max_download_speed_spin", SpinButton.class).getValue();
            if (downKb > 0) {
                aria2Settings.setOption("max-download-limit", String.valueOf(downKb * 1024L));
            }
        }
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
