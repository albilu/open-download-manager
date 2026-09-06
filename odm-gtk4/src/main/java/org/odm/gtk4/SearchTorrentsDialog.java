package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.aria2.Aria2Settings;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CellRendererToggle;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Notebook;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.StringList;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeRowReference;
import org.gnome.gtk.TreeStore;
import org.gnome.gtk.TreeView;
import org.gnome.gtk.TreeViewColumn;
import org.gnome.gtk.Window;
import org.javagi.interop.MemoryCleaner;
import org.jackett.JackettClient;
import org.jackett.JackettService;
import org.jackett.JackettSettings;
import org.manager.download.Download;
import org.manager.download.DownloadFileInfo;
import org.manager.download.DownloadManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Search, inspect and queue torrents through the existing aria2 admission path. */
public final class SearchTorrentsDialog {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchTorrentsDialog.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final String[] CATEGORIES = {"All categories", "Console", "Movies", "Audio", "PC", "TV", "XXX", "Books", "Other"};
    private final GtkBuilder builder;
    private final Window dialog;
    private final DownloadManager manager;
    private final JackettService service;
    private final org.tor.TorService tor;
    private final Runnable queued;
    private final ListStore resultsStore;
    private final TreeView resultsView;
    private final TreeStore filesStore;
    private final TreeView filesView;
    private final Map<String, TreeRowReference> fileRows = new HashMap<>();
    private final Map<Integer, TorrentSelection> torrents = new LinkedHashMap<>();
    private final Set<JackettClient.TorrentResult> submitted = new HashSet<>();
    private final Label status;
    private final Label filesStatus;
    private final Notebook notebook;
    private final NetworkOptionsPane options;
    private final SpinnerActivity activity;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong previewEpoch = new AtomicLong();
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService previews = Executors.newFixedThreadPool(4,
            Thread.ofVirtual().name("torrent-preview-", 0).factory());
    private Path destination;
    private boolean searching;
    private boolean submitting;

    private static final class TorrentSelection {
        final int id;
        final JackettClient.TorrentResult row;
        final Set<Integer> excluded = new HashSet<>();
        boolean checked;
        boolean loading;
        JackettClient.TorrentSource source;
        List<DownloadFileInfo> files = List.of();
        String error = "";

        TorrentSelection(int id, JackettClient.TorrentResult row) { this.id = id; this.row = row; }
        List<Integer> selectedFiles() {
            return files.stream().map(DownloadFileInfo::index).filter(index -> !excluded.contains(index)).toList();
        }
        boolean hasFilesToDownload() { return files.isEmpty() || !selectedFiles().isEmpty(); }
    }

    public SearchTorrentsDialog(Window parent, DownloadManager manager, JackettService service,
            org.tor.TorService tor, Runnable queued) {
        this.manager = manager; this.service = service; this.tor = tor; this.queued = queued;
        builder = UiLoader.load("/ui/search-torrents.ui");
        dialog = Widgets.require(builder, "search_torrents_dialog", Window.class);
        resultsStore = Widgets.require(builder, "torrent_results_store", ListStore.class);
        resultsView = Widgets.require(builder, "torrent_results_view", TreeView.class);
        filesStore = Widgets.require(builder, "torrent_files_store", TreeStore.class);
        filesView = Widgets.require(builder, "torrent_files_view", TreeView.class);
        status = Widgets.require(builder, "torrent_status_label", Label.class);
        filesStatus = Widgets.require(builder, "torrent_files_status", Label.class);
        notebook = Widgets.require(builder, "torrent_notebook", Notebook.class);
        activity = new SpinnerActivity(Widgets.require(builder, "torrent_spinner", Spinner.class));
        options = new NetworkOptionsPane(manager.getGlobalSettings(), Download.Type.ARIA2, Download.Protocol.TORRENT);
        options.bindTorService(tor);
        Widgets.require(builder, "torrent_options_host", Box.class).append(options.widget());
        options.widget().setTooltipText("These options apply to torrent transfers. The search engine uses its own connection settings.");
        options.onProxyChanged(() -> {
            captureFileSelection();
            previewEpoch.incrementAndGet();
            torrents.values().forEach(item -> {
                item.loading = false; item.source = null; item.files = List.of(); item.error = "";
            });
            rebuildFiles();
            if (notebook.getCurrentPage() == 1) { loadFiles(); }
        });
        Widgets.require(builder, "torrent_category_combo", DropDown.class).setModel(new StringList(CATEGORIES));
        DialogSupport.configureIndependent(dialog, parent);
        AccessibilitySupport.label(entry(), "Torrent search terms");
        AccessibilitySupport.label(Widgets.require(builder, "torrent_category_combo", DropDown.class), "Torrent category");
        AccessibilitySupport.label(resultsView, "Torrent search results; check torrents to download");
        AccessibilitySupport.label(filesView, "Files grouped by checked torrent");
        Widgets.require(builder, "torrent_result_toggle", CellRendererToggle.class).onToggled(this::toggleTorrent);
        filesView.setExpanderColumn(Widgets.require(builder, "torrent_file_name_column", TreeViewColumn.class));
        Widgets.require(builder, "torrent_file_toggle", CellRendererToggle.class).onToggled(path -> {
            if (!submitting && FileTreeSupport.toggleSelection(filesStore, path)) {
                captureFileSelection(); updateControls();
            }
        });
        notebook.onSwitchPage((page, number) -> { if (number == 1) { loadFiles(); } });
        button("search").onClicked(this::search);
        entry().onActivate(this::search);
        button("download").onClicked(this::download);
        Widgets.require(builder, "torrent_files_reload", Button.class).onClicked(this::loadFiles);
        button("close").onClicked(dialog::close);
        dialog.onCloseRequest(() -> { dispose(); return false; });
        dialog.onDestroy(this::dispose);
        destination = manager.getGlobalSettings().getDefaultDownloadDirectory();
        if (destination == null) { destination = org.manager.util.OdmPaths.downloadDirectory(); }
        PathChooserButton.forFolder(Widgets.require(builder, "torrent_folder_chooser", MenuButton.class),
                dialog, "Select download folder", destination, path -> destination = path);
        updateControls();
    }

    public void present() { dialog.present(); entry().grabFocus(); }
    private Entry entry() { return Widgets.require(builder, "torrent_query_entry", Entry.class); }
    private Button button(String name) { return Widgets.require(builder, "torrent_" + name + "_button", Button.class); }

    private void search() {
        if (closed.get() || searching || submitting) { return; }
        if (service == null) { status.setLabel("Search engine unavailable."); return; }
        String query = entry().getText().strip();
        if (query.isEmpty()) { status.setLabel("Enter a search term."); return; }
        int category = (int) Widgets.require(builder, "torrent_category_combo", DropDown.class).getSelected() * 1000;
        searching = true; showResults(List.of());
        status.setLabel("Searching…");
        activity.track(CompletableFuture.supplyAsync(() -> {
            try {
                JackettClient client = service.client();
                return client.search(query, category,
                        JackettSettings.selectedIndexers(manager.getGlobalSettings(), client.publicIndexers()));
            } catch (Exception error) { throw new CompletionException(error); }
        }, io)).whenComplete((found, error) -> UiThread.marshal(() -> {
            if (closed.get()) { return; }
            searching = false;
            if (error != null) {
                LOGGER.warn("Torrent search failed: {}", JackettSettingsPane.message(error));
                status.setLabel("Search failed. " + brief(error));
            } else {
                showResults(found.results());
                found.warnings().forEach(warning -> LOGGER.warn("Torrent search indexer: {}", JackettClient.safeMessage(warning)));
                String summary = found.results().isEmpty() ? "No torrents found." : count(found.results().size(), "torrent") + " found.";
                status.setLabel(summary + (found.warnings().isEmpty() ? "" : " " + count(found.warnings().size(), "indexer") + " failed."));
            }
            updateControls();
        }));
    }

    void showResults(List<JackettClient.TorrentResult> rows) {
        previewEpoch.incrementAndGet();
        FileTreeSupport.clear(filesStore, fileRows);
        resultsStore.clear(); torrents.clear();
        for (int i = 0; i < rows.size(); i++) {
            JackettClient.TorrentResult row = rows.get(i);
            torrents.put(i, new TorrentSelection(i, row));
            TreeIter iter = new TreeIter(); resultsStore.append(iter);
            ListStoreCells.setInt(resultsStore, iter, 0, i);
            ListStoreCells.setString(resultsStore, iter, 1, row.title());
            ListStoreCells.setString(resultsStore, iter, 2, DownloadFormats.size(row.size()));
            ListStoreCells.setLong(resultsStore, iter, 3, row.seeders());
            ListStoreCells.setLong(resultsStore, iter, 4, row.leechers());
            ListStoreCells.setString(resultsStore, iter, 5, row.published() == null ? "—" : DATE.format(row.published()));
            ListStoreCells.setLong(resultsStore, iter, 6, row.size());
            ListStoreCells.setLong(resultsStore, iter, 7, row.published() == null ? Long.MIN_VALUE : row.published().toEpochMilli());
            ListStoreCells.setString(resultsStore, iter, 8, row.tracker());
            ListStoreCells.setBoolean(resultsStore, iter, 9, false);
            ListStoreCells.setBoolean(resultsStore, iter, 10, !submitted.contains(row));
        }
        rebuildFiles(); updateControls();
    }

    private List<TorrentSelection> checkedTorrents() {
        return torrents.values().stream().filter(item -> item.checked && !submitted.contains(item.row)).toList();
    }

    private void toggleTorrent(String path) {
        TreeIter iter = new TreeIter();
        if (closed.get() || submitting || !resultsStore.getIterFromString(iter, path)) { return; }
        TorrentSelection item = torrents.get(ListStoreCells.getInt(resultsStore, iter, 0));
        if (item == null || submitted.contains(item.row)) { return; }
        captureFileSelection();
        item.checked = !item.checked;
        ListStoreCells.setBoolean(resultsStore, iter, 9, item.checked);
        rebuildFiles(); updateControls();
        if (notebook.getCurrentPage() == 1) { loadFiles(); }
    }

    private void captureFileSelection() {
        for (TorrentSelection item : checkedTorrents()) {
            TreeIter root = groupIter(item);
            if (root == null || item.files.isEmpty()) { continue; }
            Set<Integer> selected = new HashSet<>(FileTreeSupport.selectedIndexes(filesStore, root));
            item.excluded.clear();
            item.files.stream().map(DownloadFileInfo::index).filter(index -> !selected.contains(index)).forEach(item.excluded::add);
        }
    }

    private TreeIter groupIter(TorrentSelection item) {
        TreeRowReference reference = fileRows.get("G:" + item.id);
        TreePath path = reference == null ? null : reference.getPath();
        if (path == null) { return null; }
        try {
            TreeIter iter = new TreeIter();
            return filesStore.getIter(iter, path) ? iter : null;
        } finally { MemoryCleaner.free(path.handle()); }
    }

    private void rebuildFiles() {
        List<TorrentSelection> checked = checkedTorrents();
        List<FileTreeSupport.Group> groups = checked.stream().map(item -> new FileTreeSupport.Group(
                Integer.toString(item.id), item.row.title(), item.files.stream().map(file -> new FileTreeSupport.Entry(
                        !item.excluded.contains(file.index()), file.path(), file.length(), 0, file.index(),
                        FileTreeSupport.PRIORITY_NORMAL)).toList())).toList();
        if (FileTreeSupport.reconcileGroups(filesStore, fileRows, groups)) { FileTreeSupport.expandTopLevel(filesView, filesStore); }
        for (TorrentSelection item : checked) {
            TreeIter root = groupIter(item);
            if (root != null) {
                TreeStoreCells.setString(filesStore, root, 14, item.loading ? "Loading…"
                        : !item.error.isEmpty() ? "Files unavailable" : item.files.isEmpty() ? "Not loaded" : "");
            }
        }
        long loading = checked.stream().filter(item -> item.loading).count();
        long failed = checked.stream().filter(item -> !item.error.isEmpty()).count();
        long fileCount = checked.stream().mapToLong(item -> item.files.size()).sum();
        filesStatus.setLabel(checked.isEmpty() ? "Check torrents in Search to see their files."
                : loading > 0 ? "Loading files… " + count(loading, "torrent") + " remaining."
                : count(fileCount, "file") + " in " + count(checked.size(), "torrent") + "."
                        + (failed == 0 ? "" : " " + count(failed, "torrent") + " unavailable; Download includes all files."));
    }

    private record Preview(JackettClient.TorrentSource source, List<DownloadFileInfo> files) { }

    private void loadFiles() {
        if (submitting || closed.get()) { return; }
        String proxy;
        try { proxy = options.selectedProxyAddress(); }
        catch (IllegalArgumentException invalid) { filesStatus.setLabel(brief(invalid)); return; }
        captureFileSelection();
        for (TorrentSelection item : checkedTorrents()) {
            if (item.loading || !item.files.isEmpty()) { continue; }
            long epoch = previewEpoch.get();
            item.loading = true; item.error = "";
            activity.track(CompletableFuture.supplyAsync(() -> {
                CompletableFuture<List<DownloadFileInfo>> metadata = null;
                try {
                    if (closed.get() || previewEpoch.get() != epoch) { throw new java.util.concurrent.CancellationException(); }
                    JackettClient.TorrentSource source = service.client().resolve(item.row);
                    if (closed.get() || previewEpoch.get() != epoch) { throw new java.util.concurrent.CancellationException(); }
                    if (source.magnet() == null) { return new Preview(source, source.files()); }
                    metadata = manager.previewDownloadFiles(source.magnet(), proxy);
                    return new Preview(source, metadata.get());
                } catch (InterruptedException interrupted) {
                    if (metadata != null) { metadata.cancel(true); }
                    Thread.currentThread().interrupt();
                    throw new CompletionException(interrupted);
                } catch (Exception error) { throw new CompletionException(error); }
            }, previews)).whenComplete((preview, error) -> UiThread.marshal(() -> {
                if (closed.get() || previewEpoch.get() != epoch) { return; }
                captureFileSelection();
                item.loading = false;
                if (error != null) {
                    item.error = JackettSettingsPane.message(error);
                    LOGGER.debug("Torrent files for {}: {}", item.row.title(), item.error);
                } else {
                    item.source = preview.source(); item.files = List.copyOf(preview.files());
                }
                rebuildFiles(); updateControls();
            }));
        }
        rebuildFiles(); updateControls();
    }

    private record Submission(TorrentSelection item, JackettClient.TorrentSource source, List<Integer> files, boolean inspected) { }
    private record Admission(TorrentSelection item, boolean accepted, boolean retained) { }

    private void download() {
        if (submitting || closed.get() || checkedTorrents().stream().anyMatch(item -> item.loading)) { return; }
        captureFileSelection();
        final DialogOptions.NetworkValues values;
        try { options.selectedProxyAddress(); values = options.values(); }
        catch (IllegalArgumentException invalid) { status.setLabel(brief(invalid)); return; }
        List<Submission> batch = checkedTorrents().stream().filter(TorrentSelection::hasFilesToDownload)
                .map(item -> new Submission(item, item.source, item.selectedFiles(), !item.files.isEmpty())).toList();
        if (batch.isEmpty()) { status.setLabel("Check torrents and select at least one file."); return; }
        Path folder = destination;
        submitting = true; updateControls(); status.setLabel("Adding torrents to queue…");
        activity.track(CompletableFuture.supplyAsync(() -> {
            List<Admission> admissions = new ArrayList<>();
            for (Submission submission : batch) {
                if (closed.get()) { break; }
                URI staged = null;
                Download draft = null;
                try {
                    JackettClient.TorrentSource source = submission.source() == null
                            ? service.client().resolve(submission.item().row) : submission.source();
                    if (closed.get()) { break; }
                    staged = source.stageForDownload();
                    draft = DownloadSubmission.draft(manager, staged, folder, Download.Type.ARIA2);
                    draft.setName(submission.item().row.title());
                    values.applyTo(draft);
                    if (submission.inspected() && draft.getSettings() instanceof Aria2Settings aria2) {
                        aria2.setSelectedFiles(NewDownloadDialog.encodeFileSelection(submission.files()));
                    }
                    DownloadSubmission.submit(manager, draft,
                            DialogOptions.ensureTorAvailable(values.torActive(), tor), closed, null).join();
                    admissions.add(new Admission(submission.item(), true, false));
                } catch (Exception error) {
                    LOGGER.warn("Could not queue torrent {}: {}", submission.item().row.title(), JackettSettingsPane.message(error));
                    admissions.add(new Admission(submission.item(), false, draft != null && manager.getDownload(draft.getId()) != null));
                } finally {
                    if (staged != null && "file".equals(staged.getScheme())
                            && (draft == null || manager.getDownload(draft.getId()) == null)) {
                        try { Files.deleteIfExists(Path.of(staged)); }
                        catch (java.io.IOException ignored) { }
                    }
                }
            }
            return admissions;
        }, io)).whenComplete((admissions, error) -> UiThread.marshal(() -> {
            if (closed.get()) { return; }
            submitting = false;
            if (error != null) { status.setLabel("Could not queue torrents. " + brief(error)); }
            else {
                long accepted = admissions.stream().filter(Admission::accepted).count();
                long retained = admissions.stream().filter(Admission::retained).count();
                long failed = admissions.size() - accepted - retained;
                for (Admission admission : admissions) {
                    if (admission.accepted() || admission.retained()) {
                        submitted.add(admission.item().row); admission.item().checked = false;
                    }
                }
                status.setLabel("Queued " + count(accepted, "torrent") + "."
                        + (failed == 0 ? "" : " " + failed + " failed; retry checked items.")
                        + (retained == 0 ? "" : " " + retained + " need attention in Downloads."));
                if ((accepted > 0 || retained > 0) && queued != null) { queued.run(); }
                updateResultChecks(); rebuildFiles();
            }
            updateControls();
        }));
    }

    private void updateResultChecks() {
        TreeIter iter = new TreeIter();
        if (resultsStore.getIterFirst(iter)) {
            do {
                TorrentSelection item = torrents.get(ListStoreCells.getInt(resultsStore, iter, 0));
                ListStoreCells.setBoolean(resultsStore, iter, 9, item.checked && !submitted.contains(item.row));
                ListStoreCells.setBoolean(resultsStore, iter, 10, !submitted.contains(item.row));
            } while (resultsStore.iterNext(iter));
        }
    }

    private void updateControls() {
        List<TorrentSelection> checked = checkedTorrents();
        long count = checked.stream().filter(TorrentSelection::hasFilesToDownload).count();
        button("search").setSensitive(!searching && !submitting);
        button("download").setLabel(count == 0 ? "Download" : "Download (" + count + ")");
        button("download").setSensitive(count > 0 && !submitting && checked.stream().noneMatch(item -> item.loading));
        Widgets.require(builder, "torrent_files_reload", Button.class).setSensitive(!checked.isEmpty()
                && checked.stream().anyMatch(item -> !item.loading && item.files.isEmpty()) && !submitting);
        resultsView.setSensitive(!submitting);
        filesView.setSensitive(!submitting);
    }

    private static String count(long number, String noun) { return number + " " + noun + (number == 1 ? "" : "s"); }
    private static String brief(Throwable error) {
        String text = JackettSettingsPane.message(error).split(" --->| at ", 2)[0];
        return text.length() > 140 ? text.substring(0, 140) + "…" : text;
    }

    private void dispose() {
        synchronized (closed) { if (closed.getAndSet(true)) { return; } }
        previewEpoch.incrementAndGet();
        FileTreeSupport.clear(filesStore, fileRows);
        activity.dispose(); io.shutdownNow(); previews.shutdownNow();
    }
}
