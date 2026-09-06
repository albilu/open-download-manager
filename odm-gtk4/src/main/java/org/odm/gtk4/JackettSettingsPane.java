package org.odm.gtk4;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CellRendererToggle;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreeView;
import org.gnome.gtk.Window;
import org.jackett.JackettClient;
import org.jackett.JackettService;
import org.jackett.JackettSettings;
import org.manager.GlobalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Preferences and automatic public-indexer checks for the owned search engine. */
final class JackettSettingsPane {
    private static final Logger LOGGER = LoggerFactory.getLogger(JackettSettingsPane.class);
    private final GtkBuilder builder;
    private final JackettService service;
    private GlobalSettings settings;
    private final Box root;
    private final ListStore store;
    private final TreeView tree;
    private final Label status;
    private final Label actionStatus;
    private final CheckButton autostart;
    private final Set<String> selected = new LinkedHashSet<>();
    private final Set<String> configured = new LinkedHashSet<>();
    private final Set<String> configuring = new LinkedHashSet<>();
    private final Map<String, CheckResult> tests = new HashMap<>();
    private final Map<String, CompletableFuture<CheckResult>> checks = new HashMap<>();
    private final AtomicLong checkEpoch = new AtomicLong();
    private final ExecutorService probes = Executors.newFixedThreadPool(4,
            Thread.ofVirtual().name("jackett-probe-", 0).factory());
    private final ExecutorService configurationChanges = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("jackett-config-", 0).factory());
    private List<JackettClient.Indexer> indexers = List.of();
    private boolean selectionLoaded;
    private boolean busy;
    private volatile boolean closed;
    private boolean stopping;
    private long operationEpoch;
    private int timer;
    private JackettService.State previousState;

    private record CheckResult(boolean configured, String status, String detail) { }
    private enum IndexerAction { PROBE, CONFIGURE, REMOVE }

    JackettSettingsPane(Window parent, GlobalSettings settings, JackettService service) {
        this.service = service;
        builder = UiLoader.load("/ui/jackett-settings.ui");
        root = Widgets.require(builder, "jackett_settings_page", Box.class);
        store = Widgets.require(builder, "jackett_indexers_store", ListStore.class);
        tree = Widgets.require(builder, "jackett_indexers_view", TreeView.class);
        status = Widgets.require(builder, "jackett_status_label", Label.class);
        actionStatus = Widgets.require(builder, "jackett_action_status", Label.class);
        autostart = Widgets.require(builder, "jackett_autostart_check", CheckButton.class);
        AccessibilitySupport.label(tree, "Public torrent indexers");
        load(settings);
        button("install").onClicked(() -> run("Installing Jackett…", () -> { service.install(); return null; }, ignored -> { }));
        button("start").onClicked(() -> run("Starting Jackett…", () -> { service.start(); return null; }, ignored -> refreshIndexers()));
        button("stop").onClicked(() -> {
            operationEpoch++; busy = false; stopping = true;
            cancelChecks();
            run("Stopping Jackett…", () -> { service.stop(); return null; }, ignored -> { });
        });
        button("refresh").onClicked(this::refreshIndexers);
        Widgets.require(builder, "jackett_indexer_toggle", CellRendererToggle.class).onToggled(this::toggleIndexer);
        root.onDestroy(this::close);
        updateStatus();
        timer = org.gnome.glib.GLib.timeoutAddSeconds(org.gnome.glib.GLib.PRIORITY_DEFAULT, 1, () -> {
            if (closed) { timer = 0; return false; }
            updateStatus(); return true;
        });
    }

    Box widget() { return root; }

    void load(GlobalSettings values) {
        settings = values;
        autostart.setActive(values.getBooleanProperty(JackettSettings.START_WITH_ODM, false));
        selected.clear();
        selected.addAll(JackettSettings.selectedIndexers(values, indexers));
        selectionLoaded = !indexers.isEmpty();
        if (selectionLoaded) { populate(); }
    }

    void collect(GlobalSettings values) {
        values.setProperty(JackettSettings.START_WITH_ODM, Boolean.toString(autostart.getActive()));
        if (selectionLoaded) { values.setProperty(JackettSettings.INDEXERS, String.join(",", selected)); }
    }

    void reset() {
        cancelChecks();
        load(new GlobalSettings());
        selected.clear();
        selectionLoaded = true;
        populate();
    }

    void close() {
        if (closed) { return; }
        closed = true;
        cancelChecks();
        probes.shutdownNow();
        configurationChanges.shutdownNow();
        if (timer != 0) { org.gnome.glib.Source.remove(timer); timer = 0; }
    }

    private Button button(String name) { return Widgets.require(builder, "jackett_" + name + "_button", Button.class); }

    private void updateStatus() {
        if (closed) { return; }
        if (service == null) {
            status.setLabel("Search engine unavailable"); updateControls(); return;
        }
        JackettService.Status current = service.status();
        status.setLabel(current.message());
        boolean becameRunning = current.state() == JackettService.State.RUNNING && previousState != current.state();
        if (current.state() != JackettService.State.RUNNING && previousState == JackettService.State.RUNNING) {
            cancelChecks();
        }
        previousState = current.state();
        updateControls();
        if (becameRunning && !busy) { refreshIndexers(); }
    }

    private void updateControls() {
        boolean running = service != null && service.status().state() == JackettService.State.RUNNING;
        button("install").setSensitive(service != null && !busy && !running);
        button("start").setSensitive(service != null && service.isInstalled() && !busy && !running);
        boolean starting = service != null && service.status().state() == JackettService.State.STARTING;
        button("stop").setSensitive(service != null && !stopping && (running || starting));
        button("refresh").setSensitive(running && !busy);
        tree.setSensitive(!busy && running && !stopping);
    }

    private void toggleIndexer(String path) {
        TreeIter iter = new TreeIter();
        if (busy || closed || service == null || service.status().state() != JackettService.State.RUNNING
                || !store.getIterFromString(iter, path)) { return; }
        String id = ListStoreCells.getString(store, iter, 1);
        if (configuring.contains(id)) { return; }
        if (selected.contains(id)) {
            indexers.stream().filter(row -> row.id().equals(id)).findFirst()
                    .ifPresent(row -> check(row, IndexerAction.REMOVE));
            return;
        }
        if (configured.contains(id)) { selected.add(id); updateRow(id); return; }
        indexers.stream().filter(row -> row.id().equals(id)).findFirst().ifPresent(row -> check(row, IndexerAction.CONFIGURE));
    }

    private void refreshIndexers() {
        if (busy || closed || service == null) { return; }
        cancelChecks();
        run("Loading indexers…", () -> service.client().publicIndexers(), rows -> {
            indexers = rows;
            configured.clear();
            rows.stream().filter(JackettClient.Indexer::configured).map(JackettClient.Indexer::id).forEach(configured::add);
            if (!selectionLoaded) {
                selected.addAll(JackettSettings.selectedIndexers(settings, indexers)); selectionLoaded = true;
            }
            selected.retainAll(configured);
            tests.clear();
            populate();
            rows.stream().sorted(java.util.Comparator.comparing(row -> !selected.contains(row.id())))
                    .forEach(row -> check(row, IndexerAction.PROBE));
            updateCheckSummary();
        });
    }

    private void populate() {
        store.clear();
        for (JackettClient.Indexer indexer : indexers) {
            TreeIter iter = new TreeIter(); store.append(iter);
            ListStoreCells.setString(store, iter, 1, indexer.id());
            ListStoreCells.setString(store, iter, 2, indexer.name());
            writeRow(iter, indexer.id());
        }
        updateControls();
    }

    private void updateRow(String id) {
        TreeIter iter = new TreeIter();
        if (store.getIterFirst(iter)) {
            do {
                if (id.equals(ListStoreCells.getString(store, iter, 1))) { writeRow(iter, id); return; }
            } while (store.iterNext(iter));
        }
    }

    private void writeRow(TreeIter iter, String id) {
        CheckResult result = tests.get(id);
        ListStoreCells.setBoolean(store, iter, 0, selected.contains(id));
        // GtkTreeView suppresses null tooltips; an empty string still opens a bubble.
        String detail = result == null || result.detail().isBlank() ? null
                : org.gnome.glib.GLib.markupEscapeText(result.detail(), -1);
        ListStoreCells.setString(store, iter, 3, detail);
        ListStoreCells.setString(store, iter, 4, result == null ? "Waiting…" : result.status());
        ListStoreCells.setBoolean(store, iter, 5, !configuring.contains(id));
    }

    private void check(JackettClient.Indexer row, IndexerAction action) {
        long epoch = checkEpoch.get();
        boolean alreadyConfigured = configured.contains(row.id());
        if (action != IndexerAction.PROBE) { configuring.add(row.id()); }
        String progress = switch (action) {
            case PROBE -> "Testing…";
            case CONFIGURE -> "Configuring…";
            case REMOVE -> "Removing…";
        };
        tests.put(row.id(), new CheckResult(alreadyConfigured, progress, ""));
        updateRow(row.id());
        CompletableFuture<CheckResult> previous = checks.get(row.id());
        // Serialize configuration changes behind any in-flight test for this indexer.
        CompletableFuture<?> ready = previous == null ? CompletableFuture.completedFuture(null)
                : previous.handle((value, error) -> null);
        CompletableFuture<CheckResult> check = ready.thenApplyAsync(ignored -> {
            if (closed || checkEpoch.get() != epoch) { throw new java.util.concurrent.CancellationException(); }
            boolean saved = alreadyConfigured;
            try {
                JackettClient client = service.client();
                if (action == IndexerAction.REMOVE) {
                    client.unconfigure(row.id());
                    return new CheckResult(false, "Waiting…", "");
                }
                if (action == IndexerAction.CONFIGURE && !saved) {
                    client.configure(row.id(), client.configuration(row.id()));
                    saved = true;
                }
                if (closed || checkEpoch.get() != epoch) { throw new java.util.concurrent.CancellationException(); }
                client.test(row.id());
                return new CheckResult(saved, "Passed", "");
            } catch (Exception error) {
                String detail = message(error);
                LOGGER.debug("Indexer {}: {}", row.name(), detail);
                return new CheckResult(saved, action == IndexerAction.REMOVE ? "Removal failed"
                        : action == IndexerAction.CONFIGURE && !saved ? "Setup failed" : "Failed", detail);
            }
        }, action == IndexerAction.PROBE ? probes : configurationChanges);
        checks.put(row.id(), check);
        check.whenComplete((result, error) -> UiThread.marshal(() -> {
            if (closed || checkEpoch.get() != epoch || checks.get(row.id()) != check) { return; }
            checks.remove(row.id()); configuring.remove(row.id());
            CheckResult outcome = error == null ? result : new CheckResult(alreadyConfigured, "Failed", message(error));
            tests.put(row.id(), outcome);
            if (outcome.configured()) {
                configured.add(row.id());
                if (action == IndexerAction.CONFIGURE) { selected.add(row.id()); }
            } else {
                configured.remove(row.id()); selected.remove(row.id());
            }
            indexers = indexers.stream().map(indexer -> indexer.id().equals(row.id())
                    ? new JackettClient.Indexer(indexer.id(), indexer.name(), outcome.configured(), outcome.detail(), indexer.categories())
                    : indexer).toList();
            updateRow(row.id()); updateCheckSummary();
            if (action == IndexerAction.REMOVE && !outcome.configured()) { check(row, IndexerAction.PROBE); }
        }));
    }

    private void cancelChecks() {
        checkEpoch.incrementAndGet();
        checks.values().forEach(check -> check.cancel(true));
        checks.clear(); configuring.clear();
    }

    private void updateCheckSummary() {
        long passed = tests.values().stream().filter(result -> result.status().equals("Passed")).count();
        actionStatus.setLabel(checks.isEmpty() ? passed + " of " + indexers.size() + " indexers passed."
                : "Testing indexers… " + (indexers.size() - checks.size()) + "/" + indexers.size());
    }

    private <T> void run(String text, java.util.concurrent.Callable<T> action, java.util.function.Consumer<T> success) {
        if (busy || closed || service == null) { return; }
        long epoch = ++operationEpoch;
        busy = true; actionStatus.setLabel(text); updateControls();
        CompletableFuture.supplyAsync(() -> {
            try { return action.call(); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }).whenComplete((value, error) -> UiThread.marshal(() -> {
            if (closed || epoch != operationEpoch) { return; }
            busy = false; stopping = false;
            if (error != null) { actionStatus.setLabel(message(error)); }
            else { actionStatus.setLabel(""); success.accept(value); }
            updateStatus();
        }));
    }

    static String message(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) { error = error.getCause(); }
        return JackettClient.safeMessage(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }
}
