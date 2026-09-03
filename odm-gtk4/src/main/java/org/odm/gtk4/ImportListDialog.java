package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gio.File;
import org.gnome.gtk.Button;
import org.gnome.gtk.CellRendererToggle;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.MenuButton;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportListDialog.class);

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onImportDone;
    private final GtkBuilder builder;
    private final ImportLimits importLimits;

    private final ListStore urlStore;
    private final ListStore extensionFilterStore;
    private final DropDown extensionFilterCombo;
    private final Label diskSpaceLabel;
    private final PathChooserButton destinationChooser;

    private Path destinationFolder;

    private ImportListDialog(Window parent, DownloadManager downloadManager, Runnable onImportDone,
            List<String> initialUrls, ImportLimits importLimits) {
        this.downloadManager = downloadManager;
        this.onImportDone = onImportDone;
        this.importLimits = importLimits != null ? importLimits : ImportLimits.defaults();

        this.builder = UiLoader.load("/ui/import-list.ui");
        this.dialog = Widgets.require(builder, "import_dialog", Window.class);
        this.urlStore = Widgets.require(builder, "url_liststore", ListStore.class);
        this.extensionFilterStore = Widgets.require(builder, "extension_filter_store", ListStore.class);
        this.extensionFilterCombo = Widgets.require(builder, "extension_filter_combo", DropDown.class);
        this.diskSpaceLabel = Widgets.require(builder, "disk_space_label", Label.class);

        AccessibilitySupport.label(extensionFilterCombo, "Imported URL extension filter");
        AccessibilitySupport.label(Widgets.require(builder, "url_treeview",
                org.gnome.gtk.TreeView.class), "URLs to import");
        AccessibilitySupport.label(Widgets.require(builder, "folder_destination", MenuButton.class),
                "Import destination folder");
        AccessibilitySupport.label(Widgets.require(builder, "max_connections_spin", SpinButton.class),
                "Maximum connections");
        AccessibilitySupport.label(Widgets.require(builder, "retry_limit_spin", SpinButton.class),
                "Retry limit");
        AccessibilitySupport.label(Widgets.require(builder, "max_download_speed_spin", SpinButton.class),
                "Maximum download speed in KB per second");
        AccessibilitySupport.label(Widgets.require(builder, "max_upload_speed_spin", SpinButton.class),
                "Maximum upload speed in KB per second");
        AccessibilitySupport.label(Widgets.require(builder, "retry_after", SpinButton.class),
                "Seconds before retry");
        AccessibilitySupport.label(Widgets.require(builder, "referrer", Entry.class), "HTTP referrer");
        AccessibilitySupport.label(Widgets.require(builder, "cookie", Entry.class), "HTTP cookie header");
        AccessibilitySupport.label(Widgets.require(builder, "user_agent", Entry.class), "HTTP user agent");
        AccessibilitySupport.label(Widgets.require(builder, "proxy_type_combo", DropDown.class), "Proxy type");
        AccessibilitySupport.label(Widgets.require(builder, "proxy_host_entry", Entry.class), "Proxy host");
        AccessibilitySupport.label(Widgets.require(builder, "proxy_port_spin", SpinButton.class), "Proxy port");
        AccessibilitySupport.label(Widgets.require(builder, "proxy_username_entry", Entry.class),
                "Proxy username");
        AccessibilitySupport.label(Widgets.require(builder, "proxy_password_entry", Entry.class),
                "Proxy password");
        AccessibilitySupport.label(Widgets.require(builder, "tor_switch", Switch.class),
                "Route imported downloads through Tor");

        dialog.setTransientFor(parent);

        StringList proxyTypes = new StringList(new String[0]);
        for (String type : DialogOptions.PROXY_TYPES) {
            proxyTypes.append(type);
        }
        Widgets.require(builder, "proxy_type_combo", DropDown.class).setModel(proxyTypes);
        loadGlobalDefaults();

        MenuButton folderButton = Widgets.require(builder, "folder_destination", MenuButton.class);
        Path defaultDestination = Path.of(currentDefaultDirectory());
        this.destinationFolder = defaultDestination;
        this.destinationChooser = PathChooserButton.forFolder(folderButton, dialog,
                "Select destination folder", defaultDestination, path -> {
                    destinationFolder = path;
                    updateDiskSpace(path.toString());
                });
        updateDiskSpace(defaultDestination.toString());

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
        loadUrls(initialUrls);
    }

    public void present() {
        dialog.present();
    }

    /**
     * Opens the file selector first. The heavier import window is constructed
     * only after a real file was selected and its contents passed validation.
     */
    public static void chooseAndPresent(Window parent, DownloadManager downloadManager,
            Runnable onImportDone) {
        FileDialog fileDialog = new FileDialog();
        fileDialog.setTitle("Select URL list file");
        fileDialog.open(parent, null, result -> {
            try {
                File file = fileDialog.openFinish(result);
                if (file != null && file.getPath() != null) {
                    Path path = Path.of(file.getPath().toString());
                    ImportLimits limits = ImportLimits.from(downloadManager.getGlobalSettings());
                    loadSelectionThenPresent(path, limits,
                            java.util.concurrent.ForkJoinPool.commonPool(),
                            lines -> UiThread.marshal(() ->
                                    new ImportListDialog(parent, downloadManager,
                                            onImportDone, lines, limits).present()),
                            error -> UiThread.marshal(() -> showLoadError(parent, error)));
                }
            } catch (Exception e) {
                LOGGER.debug("List file selection cancelled or failed", e);
            }
        });
    }

    /**
     * Testable ordering seam for the chooser-first workflow. A null selection
     * is a cancellation and schedules neither loading nor presentation.
     */
    static void loadSelectionThenPresent(Path selectedPath, Executor executor,
            Consumer<List<String>> presenter, Consumer<Throwable> onFailure) {
        loadSelectionThenPresent(selectedPath, ImportLimits.defaults(), executor,
                presenter, onFailure);
    }

    static void loadSelectionThenPresent(Path selectedPath, ImportLimits limits,
            Executor executor, Consumer<List<String>> presenter,
            Consumer<Throwable> onFailure) {
        if (selectedPath == null) {
            return;
        }
        CompletableFuture.supplyAsync(() -> readImportLines(selectedPath, limits), executor)
                .whenComplete((lines, error) -> {
                    if (error == null) {
                        presenter.accept(lines);
                    } else {
                        onFailure.accept(rootCause(error));
                    }
                });
    }

    private static void showLoadError(Window parent, Throwable error) {
        LOGGER.warn("URL list import was rejected", error);
        org.gnome.gtk.AlertDialog alert = new org.gnome.gtk.AlertDialog();
        alert.setMessage("Could not import URL list");
        alert.setDetail(error.getMessage() != null
                ? error.getMessage() : "The selected file is unreadable or too large.");
        alert.setModal(true);
        alert.show(parent);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    static List<String> readImportLines(Path path) {
        return readImportLines(path, ImportLimits.defaults());
    }

    static List<String> readImportLines(Path path, ImportLimits limits) {
        ImportLimits effective = limits != null ? limits : ImportLimits.defaults();
        try {
            byte[] bytes;
            try (java.io.InputStream input = Files.newInputStream(path)) {
                bytes = input.readNBytes(Math.toIntExact(effective.maxSourceBytes()) + 1);
            }
            if (bytes.length > effective.maxSourceBytes()) {
                throw new IllegalArgumentException("URL list exceeds the configured "
                        + effective.maxSourceSizeMiB() + " MiB limit");
            }
            try (java.util.stream.Stream<String> stream = new String(bytes,
                    java.nio.charset.StandardCharsets.UTF_8).lines()) {
                List<String> lines = stream.map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .limit(effective.maxUrls() + 1L)
                        .toList();
                if (lines.size() > effective.maxUrls()) {
                    throw new IllegalArgumentException("URL list exceeds the configured "
                            + effective.maxUrls() + " URL limit");
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
        for (String rawLine : lines.stream().limit(importLimits.maxUrls()).toList()) {
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
        LOGGER.info("Loaded " + Math.min(lines.size(), importLimits.maxUrls())
                + " URLs into import list");
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
        String filterExt = selected == 0 ? null
                : extensionFilterCombo.getSelectedItem() instanceof org.gnome.gtk.StringObject so
                ? so.getString()
                : null;
        if (selected != 0 && filterExt == null) {
            return;
        }
        TreeIter iter = new TreeIter();
        if (urlStore.getIterFirst(iter)) {
            do {
                String ext = ListStoreCells.getString(urlStore, iter, 2);
                Value nv = new Value().init(Types.BOOLEAN);
                nv.setBoolean(selected == 0 || filterExt.equals(ext));
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
                        LOGGER.warn("List import failed", error);
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
                (int) Widgets.require(builder, "max_upload_speed_spin", SpinButton.class).getValue(),
                (int) Widgets.require(builder, "retry_limit_spin", SpinButton.class).getValue(),
                (int) Widgets.require(builder, "retry_after", SpinButton.class).getValue(),
                Widgets.require(builder, "referrer", Entry.class).getText(),
                Widgets.require(builder, "user_agent", Entry.class).getText(),
                Widgets.require(builder, "cookie", Entry.class).getText());
    }

    private int queueUrls(List<String> urls, Path destination, ImportOptions options) {
        int queued = 0;
        for (String url : urls.stream().limit(importLimits.maxUrls()).toList()) {
            try {
                Download download = downloadManager.createDownload(
                        org.manager.clipboard.UrlDetector.requireValidDownloadUrl(url), destination);
                options.apply(download);
                downloadManager.queueDownload(download);
                queued++;
            } catch (Exception e) {
                LOGGER.debug("Skipped an invalid URL-list entry", e);
            }
        }
        return queued;
    }

    record ImportOptions(boolean tor, int proxyType, String proxyHost, int proxyPort,
            String proxyUser, String proxyPassword, int connections, int downloadLimitKb,
            int uploadLimitKb, int retries, int retryDelay, String referer,
            String userAgent, String cookie) {
        void apply(Download download) {
            DialogOptions.applyProxy(download, tor, proxyType, proxyHost, proxyPort,
                    proxyUser, proxyPassword);
            DialogOptions.applyCommon(download.getSettings(), connections, downloadLimitKb,
                    uploadLimitKb, retries, retryDelay, referer, userAgent, cookie);
        }
    }

    private void loadGlobalDefaults() {
        org.manager.GlobalSettings settings = downloadManager.getGlobalSettings();
        org.manager.download.DownloadSettingsFactory.NetworkDefaults network =
                org.manager.download.DownloadSettingsFactory.NetworkDefaults.from(settings);
        Widgets.require(builder, "max_connections_spin", SpinButton.class)
                .setValue(network.maxConnections());
        Widgets.require(builder, "max_download_speed_spin", SpinButton.class)
                .setValue(network.downloadLimitKb());
        Widgets.require(builder, "max_upload_speed_spin", SpinButton.class)
                .setValue(network.uploadLimitKb());
        Widgets.require(builder, "retry_limit_spin", SpinButton.class)
                .setValue(network.maxRetries());
        Widgets.require(builder, "retry_after", SpinButton.class)
                .setValue(network.retryDelaySeconds());
        Widgets.require(builder, "referrer", Entry.class)
                .setText(network.referer());
        Widgets.require(builder, "cookie", Entry.class)
                .setText(network.cookie());
        Widgets.require(builder, "user_agent", Entry.class)
                .setText(network.userAgent());
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
