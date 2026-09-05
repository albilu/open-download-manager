package org.odm.gtk4;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.gnome.gtk.*;
import org.javagi.base.Out;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadSourceFile;

/** GTK-thread presentation with bounded background reads and selection-safe mutations. */
final class SourcesPresenter {
    final Box root;
    final ListStore store;
    final TreeView view;
    final DropDown filesDrop;
    final Entry entry;
    final Button add;
    final Button remove;
    final Button prefer;
    final Label status;
    private final DownloadManager manager;
    private final Supplier<Download> selection;
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "odm-sources-fetch");
        thread.setDaemon(true);
        return thread;
    });
    private List<DownloadSourceFile> files = List.of();
    private Download displayed;
    private boolean loading;
    private boolean editing;
    private boolean updating;
    private boolean closed;
    private String operationMessage;

    SourcesPresenter(DownloadManager manager, Supplier<Download> selection) {
        // Initialize java-gi's GLib bindings here, before worker completions can race
        // GTK notify callbacks while those native wrapper classes are being loaded.
        org.gnome.glib.MainContext.default_();
        this.manager = manager;
        this.selection = selection;
        GtkBuilder builder = UiLoader.load("/ui/sources.ui");
        root = Widgets.require(builder, "sources_root", Box.class);
        store = Widgets.require(builder, "sources_store", ListStore.class);
        view = Widgets.require(builder, "sources_view", TreeView.class);
        filesDrop = Widgets.require(builder, "source_file_drop", DropDown.class);
        entry = Widgets.require(builder, "mirror_entry", Entry.class);
        add = Widgets.require(builder, "add_mirror_button", Button.class);
        remove = Widgets.require(builder, "remove_mirror_button", Button.class);
        prefer = Widgets.require(builder, "prefer_mirror_button", Button.class);
        status = Widgets.require(builder, "sources_status", Label.class);
        filesDrop.onNotify("selected", _ -> { if (!updating) { populate(); } });
        view.getSelection().onChanged(this::updateButtons);
        entry.onChanged(this::updateButtons);
        add.onClicked(() -> change(null, entry.getText().strip(), false));
        remove.onClicked(() -> change(selectedUri(), null, false));
        prefer.onClicked(() -> change(selectedUri(), selectedUri(), true));
        AccessibilitySupport.label(filesDrop, "Source file");
        AccessibilitySupport.label(entry, "Mirror URL");
        updateButtons();
    }

    void load() {
        if (closed) { return; }
        Download target = selection.get();
        if (target != displayed) {
            displayed = target;
            operationMessage = null;
            files = List.of();
            filesDrop.setModel(new StringList(new String[0]));
            entry.setText("");
            store.clear();
            status.setLabel("");
            updateButtons();
        }
        if (target == null || loading || editing) { return; }
        loading = true;
        CompletableFuture.supplyAsync(() -> manager.getDownloadSources(target), executor)
                .whenComplete((result, error) -> UiThread.marshal(() -> {
                    loading = false;
                    if (closed) { return; }
                    if (selection.get() != target) { load(); return; }
                    if (error != null) {
                        showStatus(message(error));
                        return;
                    }
                    DownloadSourceFile old = selectedFile();
                    List<String> oldKeys = files.stream().map(DownloadSourceFile::key).toList();
                    files = result == null ? List.of() : List.copyOf(result);
                    if (!oldKeys.equals(files.stream().map(DownloadSourceFile::key).toList())) {
                        updating = true;
                        filesDrop.setModel(new StringList(files.stream()
                                .map(DownloadSourceFile::name).toArray(String[]::new)));
                        int selected = 0;
                        for (int i = 0; old != null && i < files.size(); i++) {
                            if (Objects.equals(old.key(), files.get(i).key())) { selected = i; }
                        }
                        filesDrop.setSelected(selected);
                        updating = false;
                    }
                    populate();
                    showStatus(operationMessage != null ? operationMessage : files.isEmpty()
                            ? "Live mirrors are available for HTTP, FTP, SFTP and Metalink downloads using aria2."
                            : files.getFirst().gid().isEmpty()
                                    ? "Start or resume the download to edit its mirrors."
                                    : "Mirrors must provide the same file. Hover over a source to see its current URL.");
                }));
    }

    private DownloadSourceFile selectedFile() {
        long index = filesDrop.getSelected();
        return index >= 0 && index < files.size() ? files.get((int) index) : null;
    }

    private String selectedUri() {
        TreeIter iter = new TreeIter();
        Out<TreeModel> model = new Out<>();
        return view.getSelection().getSelected(model, iter)
                ? ListStoreCells.getString(store, iter, 0) : null;
    }

    private void populate() {
        String selected = selectedUri();
        store.clear();
        DownloadSourceFile file = selectedFile();
        if (file != null) {
            for (var source : file.sources()) {
                TreeIter iter = new TreeIter();
                store.append(iter);
                ListStoreCells.setString(store, iter, 0, source.uri());
                ListStoreCells.setString(store, iter, 1, source.state());
                ListStoreCells.setString(store, iter, 2, DownloadFormats.size(source.bytesPerSecond()) + "/s");
                ListStoreCells.setString(store, iter, 3, source.uri()
                        + (source.currentUri().isBlank() ? "" : "\nCurrent URL: " + source.currentUri()));
                if (Objects.equals(selected, source.uri())) { view.getSelection().selectIter(iter); }
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        DownloadSourceFile file = selectedFile();
        boolean enabled = !editing && file != null && !file.gid().isEmpty();
        entry.setSensitive(enabled);
        add.setSensitive(enabled && !entry.getText().isBlank());
        remove.setSensitive(enabled && selectedUri() != null && file.sources().size() > 1);
        prefer.setSensitive(enabled && selectedUri() != null);
    }

    private void change(String removeUri, String addUri, boolean first) {
        Download target = selection.get();
        DownloadSourceFile file = selectedFile();
        if (editing || target == null || target != displayed || file == null) { return; }
        editing = true;
        operationMessage = null;
        updateButtons();
        showStatus("Updating mirrors…");
        manager.changeDownloadSource(target, file, removeUri, addUri, first)
                .whenComplete((unused, error) -> UiThread.marshal(() -> {
                    editing = false;
                    if (closed) { return; }
                    if (selection.get() == target) {
                        if (error == null) { entry.setText(""); }
                        operationMessage = error == null ? "Mirrors updated." : message(error);
                        showStatus(operationMessage);
                    }
                    updateButtons();
                    load();
                }));
    }

    private void showStatus(String text) {
        status.setLabel(text);
        status.setTooltipText(text);
    }

    private static String message(Throwable error) {
        while (error.getCause() != null) { error = error.getCause(); }
        return error.getMessage() == null ? "Could not update mirrors" : error.getMessage();
    }

    void shutdown() {
        closed = true;
        executor.shutdownNow();
    }
}
