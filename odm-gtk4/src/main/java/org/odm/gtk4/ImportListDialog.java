package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gio.File;
import org.gnome.gtk.Button;
import org.gnome.gtk.Box;
import org.gnome.gtk.CellRendererToggle;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.Popover;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.Window;
import org.gnome.gobject.Value;
import org.gnome.pango.EllipsizeMode;
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
    private final org.tor.TorService torService;

    private final ListStore urlStore;
    private final MenuButton extensionFilterCombo;
    private final Label extensionFilterLabel;
    private final Map<String, CheckButton> extensionChecks = new LinkedHashMap<>();
    private CheckButton allExtensionsCheck;
    private boolean updatingExtensionFilter;
    private final DropDown engineCombo;
    private final Label diskSpaceLabel;
    private final Label itemCountLabel;
    private final PathChooserButton destinationChooser;
    private final NetworkOptionControls networkControls;

    private Path destinationFolder;

    private ImportListDialog(Window parent, DownloadManager downloadManager, Runnable onImportDone,
            List<String> initialUrls, ImportLimits importLimits) {
        this(parent, downloadManager, onImportDone, initialUrls, importLimits, null);
    }

    private ImportListDialog(Window parent, DownloadManager downloadManager, Runnable onImportDone,
            List<String> initialUrls, ImportLimits importLimits,
            org.tor.TorService torService) {
        this.downloadManager = downloadManager;
        this.onImportDone = onImportDone;
        this.importLimits = importLimits != null ? importLimits : ImportLimits.defaults();
        this.torService = torService;

        this.builder = UiLoader.load("/ui/import-list.ui");
        this.dialog = Widgets.require(builder, "import_dialog", Window.class);
        this.urlStore = Widgets.require(builder, "url_liststore", ListStore.class);
        this.extensionFilterCombo = Widgets.require(builder, "extension_filter_combo", MenuButton.class);
        this.extensionFilterLabel = new Label(I18n.tr("(all)"));
        extensionFilterLabel.setEllipsize(EllipsizeMode.END);
        extensionFilterLabel.setMaxWidthChars(24);
        extensionFilterCombo.setChild(extensionFilterLabel);
        extensionFilterCombo.setAlwaysShowArrow(true);
        extensionFilterCombo.setCanShrink(true);
        this.engineCombo = Widgets.require(builder, "engine_combo", DropDown.class);
        this.engineCombo.setModel(new StringList(java.util.Arrays.stream(ImportEngine.values())
                .map(ImportEngine::label).toArray(String[]::new)));
        this.engineCombo.setSelected(ImportEngine.AUTO.ordinal());
        this.diskSpaceLabel = Widgets.require(builder, "disk_space_label", Label.class);
        this.itemCountLabel = Widgets.require(builder, "item_count_label", Label.class);

        AccessibilitySupport.label(extensionFilterCombo, I18n.tr("Imported URL extension filter"));
        AccessibilitySupport.label(engineCombo, I18n.tr("Import download engine"));
        AccessibilitySupport.label(Widgets.require(builder, "url_treeview",
                org.gnome.gtk.TreeView.class), I18n.tr("URLs to import"));
        AccessibilitySupport.label(Widgets.require(builder, "folder_destination", MenuButton.class),
                I18n.tr("Import destination folder"));
        AccessibilitySupport.label(Widgets.require(builder, "max_connections_spin", SpinButton.class),
                I18n.tr("Maximum connections"));
        AccessibilitySupport.label(Widgets.require(builder, "retry_limit_spin", SpinButton.class),
                I18n.tr("Retry limit"));
        AccessibilitySupport.label(Widgets.require(builder, "max_download_speed_spin", SpinButton.class),
                I18n.tr("Maximum download speed in KB per second"));
        AccessibilitySupport.label(Widgets.require(builder, "max_upload_speed_spin", SpinButton.class),
                I18n.tr("Maximum upload speed in KB per second"));
        AccessibilitySupport.label(Widgets.require(builder, "retry_after", SpinButton.class),
                I18n.tr("Seconds before retry"));
        AccessibilitySupport.label(Widgets.require(builder, "referrer", Entry.class), I18n.tr("HTTP referrer"));
        AccessibilitySupport.label(Widgets.require(builder, "cookie", Entry.class), I18n.tr("HTTP cookie header"));
        AccessibilitySupport.label(Widgets.require(builder, "user_agent", Entry.class), I18n.tr("HTTP user agent"));
        AccessibilitySupport.label(Widgets.require(builder, "proxy_type_combo", DropDown.class), I18n.tr("Proxy type"));
        AccessibilitySupport.label(Widgets.require(builder, "proxy_host_entry", Entry.class), I18n.tr("Proxy host"));
        AccessibilitySupport.label(Widgets.require(builder, "proxy_port_spin", SpinButton.class), I18n.tr("Proxy port"));
        AccessibilitySupport.label(Widgets.require(builder, "proxy_username_entry", Entry.class),
                I18n.tr("Proxy username"));
        AccessibilitySupport.label(Widgets.require(builder, "proxy_password_entry", Entry.class),
                I18n.tr("Proxy password"));
        AccessibilitySupport.label(Widgets.require(builder, "tor_switch", Switch.class),
                I18n.tr("Route imported downloads through Tor"));

        DialogSupport.configureIndependent(dialog, parent);

        StringList proxyTypes = new StringList(new String[0]);
        for (String type : DialogOptions.PROXY_TYPES) {
            proxyTypes.append(type);
        }
        DropDown proxyType = Widgets.require(builder, "proxy_type_combo", DropDown.class);
        proxyType.setModel(proxyTypes);
        this.networkControls = new NetworkOptionControls(
                Widgets.require(builder, "max_connections_spin", SpinButton.class),
                Widgets.require(builder, "max_download_speed_spin", SpinButton.class),
                Widgets.require(builder, "max_upload_speed_spin", SpinButton.class),
                Widgets.require(builder, "retry_limit_spin", SpinButton.class),
                Widgets.require(builder, "retry_after", SpinButton.class),
                Widgets.require(builder, "referrer", Entry.class),
                Widgets.require(builder, "user_agent", Entry.class),
                Widgets.require(builder, "cookie", Entry.class), proxyType,
                Widgets.require(builder, "proxy_host_entry", Entry.class),
                Widgets.require(builder, "proxy_port_spin", SpinButton.class),
                Widgets.require(builder, "proxy_username_entry", Entry.class),
                Widgets.require(builder, "proxy_password_entry", Entry.class),
                Widgets.require(builder, "tor_switch", Switch.class));
        networkControls.bindTorService(dialog, torService);
        loadGlobalDefaults();

        MenuButton folderButton = Widgets.require(builder, "folder_destination", MenuButton.class);
        Path defaultDestination = Path.of(currentDefaultDirectory());
        this.destinationFolder = defaultDestination;
        this.destinationChooser = PathChooserButton.forFolder(folderButton, dialog,
                I18n.tr("Select destination folder"), defaultDestination, path -> {
                    destinationFolder = path;
                    updateDiskSpace(path.toString());
                });
        updateDiskSpace(defaultDestination.toString());

        engineCombo.onNotify("selected", pspec -> refreshSelection());

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
                refreshSelection();
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
        chooseAndPresent(parent, downloadManager, onImportDone, null);
    }

    public static void chooseAndPresent(Window parent, DownloadManager downloadManager,
            Runnable onImportDone, org.tor.TorService torService) {
        FileDialog fileDialog = new FileDialog();
        DialogSupport.configureIndependent(fileDialog);
        fileDialog.setTitle(I18n.tr("Select URL list file"));
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
                                            onImportDone, lines, limits, torService).present()),
                            error -> UiThread.marshal(() -> showLoadError(parent, error)));
                }
            } catch (Exception e) {
                LOGGER.debug("List file selection cancelled or failed", e);
            }
        });
    }

    /** Presents the same selectable list and Options tab for extracted HTML links. */
    static ImportListDialog presentHtmlUrls(Window parent, DownloadManager downloadManager,
            Runnable onImportDone, List<String> urls, ImportLimits limits,
            org.tor.TorService torService) {
        var imported = new ImportListDialog(parent, downloadManager, onImportDone,
                urls != null ? urls : List.of(), limits, torService);
        imported.dialog.setTitle(I18n.tr("Import Links from HTML"));
        imported.present();
        return imported;
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
        alert.setMessage(I18n.tr("Could not import URL list"));
        alert.setDetail(UiErrors.message(error));
        DialogSupport.configureIndependent(alert);
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
                throw new IllegalArgumentException(I18n.format("URL list exceeds the configured %d MiB limit",
                        effective.maxSourceSizeMiB()));
            }
            try (java.util.stream.Stream<String> stream = new String(bytes,
                    java.nio.charset.StandardCharsets.UTF_8).lines()) {
                List<String> lines = stream.map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .limit(effective.maxUrls() + 1L)
                        .toList();
                if (lines.size() > effective.maxUrls()) {
                    throw new IllegalArgumentException(I18n.format("URL list exceeds the configured %d URL limit",
                            effective.maxUrls()));
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
        List<String> candidates = DownloadSubmission.validUrls(lines, importLimits.maxUrls());
        for (String line : candidates) {
            String ext = extensionOf(line);
            TreeIter iter = new TreeIter();
            urlStore.append(iter);
            setBool(urlStore, iter, 0, true);
            setStr(urlStore, iter, 1, line);
            setStr(urlStore, iter, 2, ext);
            if (!extensions.contains(ext)) {
                extensions.add(ext);
            }
        }
        rebuildExtensionFilter(extensions);
        refreshSelection();
        LOGGER.info("Loaded " + candidates.size()
                + " URLs into import list");
    }

    private void rebuildExtensionFilter(List<String> extensions) {
        extensionChecks.clear();
        Box choices = new Box(Orientation.VERTICAL, 6);
        choices.setMarginStart(8);
        choices.setMarginEnd(8);
        choices.setMarginTop(8);
        choices.setMarginBottom(8);
        allExtensionsCheck = CheckButton.withLabel(I18n.tr("All"));
        allExtensionsCheck.setActive(true);
        choices.append(allExtensionsCheck);
        for (String ext : extensions) {
            CheckButton check = CheckButton.withLabel(extensionLabel(ext));
            check.setActive(true);
            extensionChecks.put(ext, check);
            choices.append(check);
            check.onToggled(this::onExtensionFilterChanged);
        }
        allExtensionsCheck.onToggled(() -> {
            if (updatingExtensionFilter) {
                return;
            }
            updatingExtensionFilter = true;
            try {
                boolean selected = allExtensionsCheck.getActive();
                extensionChecks.values().forEach(check -> check.setActive(selected));
            } finally {
                updatingExtensionFilter = false;
            }
            onExtensionFilterChanged();
        });
        allExtensionsCheck.setSensitive(!extensions.isEmpty());
        ScrolledWindow scroller = new ScrolledWindow();
        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.setMaxContentHeight(360);
        scroller.setPropagateNaturalHeight(true);
        scroller.setChild(choices);
        Popover popover = new Popover();
        popover.setHasArrow(false);
        popover.setChild(scroller);
        extensionFilterCombo.setPopover(popover);
        onExtensionFilterChanged();
    }

    private static String extensionLabel(String extension) {
        return extension.isEmpty() ? I18n.tr("No extension") : extension;
    }

    private void onExtensionFilterChanged() {
        if (updatingExtensionFilter) {
            return;
        }
        Set<String> selected = new LinkedHashSet<>();
        extensionChecks.forEach((extension, check) -> {
            if (check.getActive()) {
                selected.add(extension);
            }
        });
        boolean all = selected.size() == extensionChecks.size();
        updatingExtensionFilter = true;
        try {
            allExtensionsCheck.setActive(all);
            allExtensionsCheck.setInconsistent(!all && !selected.isEmpty());
        } finally {
            updatingExtensionFilter = false;
        }
        String summary = all ? I18n.tr("(all)") : selected.isEmpty() ? I18n.tr("(none)")
                : String.join(", ", selected.stream().map(ImportListDialog::extensionLabel).toList());
        extensionFilterLabel.setLabel(summary);
        extensionFilterCombo.setTooltipText(summary);
        applyExtensionFilter(selected);
    }

    private void applyExtensionFilter(Set<String> selected) {
        // All rows remain visible; checked extensions determine the marked rows.
        TreeIter iter = new TreeIter();
        if (urlStore.getIterFirst(iter)) {
            do {
                String ext = ListStoreCells.getString(urlStore, iter, 2);
                Value nv = new Value().init(Types.BOOLEAN);
                nv.setBoolean(selected.contains(ext));
                urlStore.setValue(iter, 0, nv);
                nv.unset();
            } while (urlStore.iterNext(iter));
        }
        refreshSelection();
    }

    private void onImport() {
        Path destination = destinationFolder != null ? destinationFolder
                : Path.of(currentDefaultDirectory());
        List<String> urls = markedUrls();
        ImportOptions options = captureOptions();
        ImportEngine engine = selectedEngine();
        Widgets.require(builder, "validate_button", Button.class).setSensitive(false);
        DialogOptions.ensureTorAvailable(options.tor(), torService)
                .thenCompose(ignored -> CompletableFuture.supplyAsync(
                        () -> DownloadSubmission.queueUrls(downloadManager, urls, destination,
                                engine, options::apply, importLimits.maxUrls())))
                .whenComplete((queued, error) -> UiThread.marshal(() -> {
                    if (error != null) {
                        LOGGER.warn("List import failed", error);
                        Widgets.require(builder, "validate_button", Button.class)
                                .setSensitive(true);
                        AccessibilitySupport.status(diskSpaceLabel,
                                I18n.format("Could not import this list: %s", UiErrors.message(error)),
                                org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                        return;
                    }
                    LOGGER.info("Imported " + queued + " downloads from list");
                    if (onImportDone != null) {
                        onImportDone.run();
                    }
                    dialog.close();
                }));
    }

    private List<String> markedUrls() {
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
        return urls;
    }

    private void refreshSelection() {
        List<String> selected = markedUrls();
        int total = urlStore.iterNChildren(null);
        itemCountLabel.setLabel(I18n.plural("%2$d of %1$d item selected", "%2$d of %1$d items selected",
                total, selected.size()));
        networkControls.applyCapabilities(NetworkOptionControls.commonCapabilities(
                downloadManager.getGlobalSettings(), selected, selectedEngine().type()));
    }

    private ImportEngine selectedEngine() {
        long selected = engineCombo.getSelected();
        ImportEngine[] engines = ImportEngine.values();
        return selected >= 0 && selected < engines.length ? engines[(int) selected] : ImportEngine.AUTO;
    }

    private ImportOptions captureOptions() {
        return new ImportOptions(
                Widgets.require(builder, "tor_switch", Switch.class).getActive(),
                DialogOptions.torSocksPort(torService),
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

    record ImportOptions(boolean tor, int torSocksPort, int proxyType,
            String proxyHost, int proxyPort,
            String proxyUser, String proxyPassword, int connections, int downloadLimitKb,
            int uploadLimitKb, int retries, int retryDelay, String referer,
            String userAgent, String cookie) {
        void apply(Download download) {
            DialogOptions.applyProxy(download, tor, proxyType, proxyHost, proxyPort,
                    proxyUser, proxyPassword, torSocksPort);
            DialogOptions.applyCommon(download, connections, downloadLimitKb,
                    uploadLimitKb, retries, retryDelay, referer, userAgent, cookie);
        }
    }

    private void loadGlobalDefaults() {
        org.manager.GlobalSettings settings = downloadManager.getGlobalSettings();
        org.manager.download.DownloadSettingsFactory.NetworkDefaults network =
                org.manager.download.DownloadSettingsFactory.NetworkDefaults.from(settings);
        networkControls.setConnectionsValue(network.maxConnections());
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

        DialogOptions.ProxyFields proxy = DialogOptions.parseProxy(
                DialogOptions.manualProxyAddress(settings));
        Widgets.require(builder, "proxy_type_combo", DropDown.class).setSelected(proxy.typeIndex());
        Widgets.require(builder, "proxy_host_entry", Entry.class).setText(proxy.host());
        Widgets.require(builder, "proxy_port_spin", SpinButton.class).setValue(proxy.port());
        Widgets.require(builder, "proxy_username_entry", Entry.class).setText(proxy.username());
        Widgets.require(builder, "proxy_password_entry", Entry.class).setText(proxy.password());
    }

    private void updateDiskSpace(String dir) {
        try {
            long free = new java.io.File(dir).getUsableSpace();
            diskSpaceLabel.setLabel(I18n.format("%s free", DownloadFormats.size(free)));
        } catch (Exception e) {
            diskSpaceLabel.setLabel("");
        }
    }

    private static String extensionOf(String url) {
        String path = URI.create(url).getPath();
        if (path == null) {
            return "";
        }
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String currentDefaultDirectory() {
        Path dir = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
        return dir != null ? dir.toString()
                : org.manager.util.OdmPaths.downloadDirectory().toString();
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
