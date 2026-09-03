package org.odm.gtk4;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.gnome.glib.MainLoop;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.SortType;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeRowReference;
import org.gnome.gtk.TreeSortable;
import org.gnome.gtk.TreeStore;
import org.gnome.gtk.TreeView;
import org.javagi.interop.MemoryCleaner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadOperationResult;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.CompletionActionResult;
import org.manager.schedule.ScheduleManager;
import org.mockito.Mockito;
import org.tor.TorService;

/**
 * Selection-fetch lifecycle of the detail tabs against a real GLib main
 * loop (requires a display, as the Docker test env provides via Xvfb).
 * Stale fetch results must never populate the stores, the latest selection
 * must always receive its own fetch even while an older one is pending,
 * and the fetch executor must shut down when the presenter/window is torn
 * down instead of living on as an ownerless static pool.
 */
@DisplayName("DetailTabsPresenter selection-fetch lifecycle")
class DetailTabsPresenterGtkTest {

    private static MainLoop loop;
    private static java.util.concurrent.ExecutorService loopThread;

    private DownloadManager manager;
    private ListStore trackersStore;
    private ListStore peersStore;
    private TreeStore filesStore;
    private ListStore completionDetailsStore;
    private final AtomicReference<Download> selection = new AtomicReference<>();
    private DetailTabsPresenter presenter;

    private record PresenterFixture(ListStore trackers, ListStore peers, TreeStore files,
            ListStore completionDetails, DetailTabsPresenter presenter) {
    }

    @BeforeAll
    static void initGtk() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        loopThread = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-glib-loop");
            t.setDaemon(true);
            return t;
        });
        loopThread.execute(() -> {
            Gtk.init();
            loop = new MainLoop(null, false);
            ready.countDown();
            loop.run();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "GTK loop did not initialize");
    }

    @AfterAll
    static void tearDown() {
        loop.quit();
        loopThread.shutdownNow();
    }

    @BeforeEach
    void setUpPresenter() throws Exception {
        manager = Mockito.mock(DownloadManager.class);
        PresenterFixture fixture = onLoop(() -> {
            GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
            ListStore trackers = Widgets.require(builder, "trackers_store", ListStore.class);
            ListStore peers = Widgets.require(builder, "peers_store", ListStore.class);
            TreeStore files = Widgets.require(builder, "files_store", TreeStore.class);
            TreeView filesView = Widgets.require(builder, "files_view", TreeView.class);
            ListStore completionDetails = Widgets.require(builder,
                    "completion_details_store", ListStore.class);
            return new PresenterFixture(trackers, peers, files, completionDetails,
                    new DetailTabsPresenter(manager, trackers, peers, files, filesView,
                            completionDetails, selection::get));
        });
        trackersStore = fixture.trackers();
        peersStore = fixture.peers();
        filesStore = fixture.files();
        completionDetailsStore = fixture.completionDetails();
        presenter = fixture.presenter();
    }

    @Test
    @Timeout(60)
    @DisplayName("a stale fetch completing after a newer selection never reaches the stores")
    void staleFetchResultIsDropped() throws Exception {
        Download a = download("a");
        Download b = download("b");
        CountDownLatch releaseA = new CountDownLatch(1);
        Mockito.when(manager.getDownloadTrackers(a)).thenAnswer(inv -> {
            releaseA.await();
            return List.of(List.of("tracker-a"));
        });
        Mockito.when(manager.getDownloadTrackers(b)).thenReturn(List.of(List.of("tracker-b")));

        onLoop(() -> {
            selection.set(a);
            presenter.load();
            selection.set(b);
            presenter.load();
        });

        awaitStoreEquals(List.of("tracker-b"), "selection B must be populated");
        onLoop(releaseA::countDown);
        Thread.sleep(300);
        assertEquals(List.of("tracker-b"), onLoop(() -> firstColumn(trackersStore)),
                "the stale fetch for A must be discarded after B was shown");
    }

    @Test
    @Timeout(60)
    @DisplayName("the latest selection always gets its own fetch while another is pending")
    void latestSelectionIsAlwaysFetched() throws Exception {
        Download a = download("a");
        Download b = download("b");
        CountDownLatch releaseA = new CountDownLatch(1);
        Mockito.when(manager.getDownloadTrackers(a)).thenAnswer(inv -> {
            releaseA.await();
            return List.of(List.of("tracker-a"));
        });
        Mockito.when(manager.getDownloadTrackers(b)).thenReturn(List.of(List.of("tracker-b")));

        onLoop(() -> {
            selection.set(a);
            presenter.load();
            selection.set(b);
            presenter.load();
        });

        awaitStoreEquals(List.of("tracker-b"), "selection B must be fetched despite A pending");
        onLoop(releaseA::countDown);
    }

    @Test
    @Timeout(60)
    @DisplayName("clearing the selection clears the stores")
    void clearedSelectionClearsStores() throws Exception {
        Download a = download("a");
        Mockito.when(manager.getDownloadTrackers(a)).thenReturn(List.of(List.of("tracker-a")));

        onLoop(() -> {
            selection.set(a);
            presenter.load();
        });
        awaitStoreEquals(List.of("tracker-a"), "selection A must be populated");

        onLoop(() -> {
            selection.set(null);
            presenter.load();
        });
        awaitStoreEquals(List.of(), "clearing the selection must empty the stores");
    }

    @Test
    @Timeout(60)
    @DisplayName("completion action results and output are rendered in the Actions store")
    void completionActionResultsPopulateDetailsStore() throws Exception {
        Download download = download("completed");
        download.setCompletionActionResults(List.of(new CompletionActionResult(
                "result-1",
                AfterCompletionAction.ActionType.ANTIVIRUS_CHECK,
                "Antivirus check using ClamAV",
                CompletionActionResult.Status.FAILED,
                "Scanner executable was not found",
                "clamscan: command not found",
                AfterCompletionAction.Severity.HIGH,
                Instant.parse("2026-09-02T10:00:00Z"),
                Instant.parse("2026-09-02T10:00:01Z"))));
        Mockito.when(manager.getDownloadTrackers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadPeers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadFiles(download)).thenReturn(List.of());

        onLoop(() -> {
            selection.set(download);
            presenter.load();
        });

        assertEquals("Antivirus check using ClamAV",
                onLoop(() -> firstValue(completionDetailsStore, 0)));
        assertEquals("Failed (high)",
                onLoop(() -> firstValue(completionDetailsStore, 1)));
        assertEquals("Scanner executable was not found",
                onLoop(() -> firstValue(completionDetailsStore, 2)));
        assertEquals("clamscan: command not found",
                onLoop(() -> firstValue(completionDetailsStore,
                        DetailTabsPresenter.ACTION_OUTPUT_COLUMN)));
        assertTrue(onLoop(() -> firstBoolean(completionDetailsStore,
                        DetailTabsPresenter.ACTION_EXPOSES_OUTPUT_COLUMN)));
    }

    @Test
    @Timeout(60)
    @DisplayName("manual Recheck Data results are rendered in the Actions store")
    void recheckDataResultsPopulateDetailsStore() throws Exception {
        Download download = download("rechecked");
        download.setOperationResults(List.of(new DownloadOperationResult(
                "recheck-1",
                DownloadOperationResult.OperationType.RECHECK_DATA,
                "Recheck Data",
                DownloadOperationResult.Status.ACCEPTED,
                "aria2 accepted the integrity recheck request",
                Instant.parse("2026-09-02T10:00:00Z"),
                Instant.parse("2026-09-02T10:00:01Z"))));
        Mockito.when(manager.getDownloadTrackers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadPeers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadFiles(download)).thenReturn(List.of());

        onLoop(() -> {
            selection.set(download);
            presenter.load();
        });

        assertEquals("Recheck Data",
                onLoop(() -> firstValue(completionDetailsStore, 0)));
        assertEquals("Accepted",
                onLoop(() -> firstValue(completionDetailsStore, 1)));
        assertEquals("aria2 accepted the integrity recheck request",
                onLoop(() -> firstValue(completionDetailsStore, 2)));
        assertFalse(onLoop(() -> firstBoolean(completionDetailsStore,
                        DetailTabsPresenter.ACTION_EXPOSES_OUTPUT_COLUMN)),
                "manual operation rows do not open completion-action logs");
    }

    @Test
    @Timeout(60)
    @DisplayName("file size and progress sorting use raw numeric values")
    void fileRowsUseTypedSortKeys() throws Exception {
        Download download = download("files");
        Mockito.when(manager.getDownloadTrackers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadPeers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadFiles(download)).thenReturn(List.of(
                file("season/ten-kib.bin", 10 * 1024L, 1024L, 1),
                file("season/nine-kib.bin", 9 * 1024L, 9000L, 2)));

        onLoop(() -> {
            selection.set(download);
            presenter.load();
        });
        awaitTrue(() -> {
            try {
                return onLoop(() -> {
                    TreeIter folder = new TreeIter();
                    return filesStore.getIterFirst(folder)
                            && filesStore.iterNChildren(folder) == 2;
                });
            } catch (Exception e) {
                return false;
            }
        }, "file rows must be populated");

        assertEquals("nine-kib.bin", onLoop(() -> {
            ((TreeSortable) filesStore).setSortColumnId(7, SortType.ASCENDING);
            return firstChildValue(filesStore, 1);
        }), "9 KiB must sort before 10 KiB by raw byte length");

        assertEquals("nine-kib.bin", onLoop(() -> {
            ((TreeSortable) filesStore).setSortColumnId(8, SortType.DESCENDING);
            return firstChildValue(filesStore, 1);
        }), "97.66% must sort above 10.00% by precise progress");

        assertEquals("season/nine-kib.bin",
                onLoop(() -> firstChildValue(filesStore,
                        DetailTabsPresenter.FILE_PATH_COLUMN)),
                "the hidden path must remain available for reveal-in-folder actions");
    }

    @Test
    @Timeout(60)
    @DisplayName("unchanged detail rows update in place instead of being redrawn")
    void stableRowsAreUpdatedInPlace() throws Exception {
        Download download = download("stable-files");
        Mockito.when(manager.getDownloadTrackers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadPeers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadFiles(download)).thenReturn(
                List.of(file("stable.bin", 100, 10, 1)),
                List.of(file("stable.bin", 100, 50, 1)));

        onLoop(() -> {
            selection.set(download);
            presenter.load();
        });
        awaitTrue(() -> {
            try {
                return "10.00%".equals(onLoop(() -> firstTreeValue(filesStore, 6)));
            } catch (Exception e) {
                return false;
            }
        }, "initial file progress must be populated");

        TreeRowReference stableReference = onLoop(() -> {
            TreePath path = TreePath.fromString("0");
            try {
                return new TreeRowReference(filesStore, path);
            } finally {
                MemoryCleaner.free(path.handle());
            }
        });
        try {
            onLoop(presenter::load);
            awaitTrue(() -> {
                try {
                    return "50.00%".equals(onLoop(() -> firstTreeValue(filesStore, 6)));
                } catch (Exception e) {
                    return false;
                }
            }, "updated file progress must reach the existing row");
            assertTrue(onLoop(stableReference::valid),
                    "clearing and appending would invalidate the original GTK row reference");
        } finally {
            onLoop(() -> MemoryCleaner.free(stableReference.handle()));
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("file refresh waits until priority cell editing finishes")
    void fileRefreshWaitsForPriorityEditing() throws Exception {
        Download download = download("editing-files");
        Mockito.when(manager.getDownloadTrackers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadPeers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadFiles(download)).thenReturn(
                List.of(file("editing.bin", 100, 10, 1)),
                List.of(file("editing.bin", 100, 50, 1)));

        onLoop(() -> {
            selection.set(download);
            presenter.load();
        });
        awaitTrue(() -> {
            try {
                return "10.00%".equals(onLoop(() -> firstTreeValue(filesStore, 6)));
            } catch (Exception e) {
                return false;
            }
        }, "initial file progress must be populated");

        onLoop(() -> {
            presenter.setFilePriorityEditing(true);
            presenter.load();
        });
        awaitTrue(() -> {
            try {
                Mockito.verify(manager, Mockito.atLeast(2)).getDownloadFiles(download);
                return true;
            } catch (AssertionError e) {
                return false;
            }
        }, "the background refresh must still be fetched while editing");
        assertEquals("10.00%", onLoop(() -> firstTreeValue(filesStore, 6)),
                "the active combo editor's row must not be rewritten");

        onLoop(() -> presenter.setFilePriorityEditing(false));
        awaitTrue(() -> {
            try {
                return "50.00%".equals(onLoop(() -> firstTreeValue(filesStore, 6)));
            } catch (Exception e) {
                return false;
            }
        }, "fresh file state must appear immediately after editing");
    }

    @Test
    @Timeout(60)
    @DisplayName("shutdown stops the fetch executor; later loads fetch nothing")
    void shutdownStopsExecutorAndFurtherFetches() throws Exception {
        Download a = download("a");
        Mockito.when(manager.getDownloadTrackers(a)).thenReturn(List.of(List.of("tracker-a")));

        onLoop(presenter::shutdown);
        assertTrue(presenter.isShutdown(), "shutdown must terminate the fetch executor");

        onLoop(() -> {
            selection.set(a);
            presenter.load();
        });
        Thread.sleep(300);
        Mockito.verify(manager, Mockito.never()).getDownloadTrackers(a);
        assertEquals(List.of(), onLoop(() -> firstColumn(trackersStore)));
    }

    @Test
    @Timeout(60)
    @DisplayName("MainWindow teardown shuts the detail presenter's executor down")
    void mainWindowTeardownShutsPresenterDown() throws Exception {
        DownloadManager stub = stubManager();
        MainWindow window = onLoop(() -> new MainWindow(null, stub, new TorService("tor"),
                new ScheduleManager(stub)));
        try {
            assertFalse(window.detailTabsPresenter.isShutdown(),
                    "a live window must keep its detail fetch executor running");
        } finally {
            onLoop(window::dispose);
        }
        assertTrue(window.detailTabsPresenter.isShutdown(),
                "window teardown must shut down the detail presenter's executor");
    }

    private static Download download(String name) {
        try {
            Download d = new Download(new URI("https://example.com/" + name));
            d.setName(name);
            return d;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Map<String, Object> file(String path, long length, long completed, int index) {
        return Map.of(
                "path", path,
                "length", Long.toString(length),
                "completedLength", Long.toString(completed),
                "index", Integer.toString(index),
                "selected", "true");
    }

    private static DownloadManager stubManager() {
        return (DownloadManager) java.lang.reflect.Proxy.newProxyInstance(
                DownloadManager.class.getClassLoader(),
                new Class<?>[]{DownloadManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getGlobalSettings" -> new org.manager.GlobalSettings();
                    case "getAllDownloads", "getDownloads" -> java.util.List.of();
                    case "isClipboardMonitoringEnabled", "isTorrentFolderMonitoringEnabled",
                            "isMetaLinkFolderMonitoringEnabled" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    /** Runs a block on the GLib loop thread and reports the outcome. */
    private static void onLoop(Runnable block) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        UiThread.marshal(() -> {
            try {
                block.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    /** Runs a value-producing block on the GLib loop thread and returns it. */
    private static <T> T onLoop(java.util.function.Supplier<T> block) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        UiThread.marshal(() -> {
            try {
                done.complete(block.get());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(10, TimeUnit.SECONDS);
    }

    private static List<String> firstColumn(ListStore store) {
        List<String> values = new ArrayList<>();
        TreeIter iter = new TreeIter();
        if (store.getIterFirst(iter)) {
            do {
                values.add(ListStoreCells.getString(store, iter, 0));
            } while (store.iterNext(iter));
        }
        return values;
    }

    private static String firstValue(ListStore store, int column) {
        TreeIter iter = new TreeIter();
        return store.getIterFirst(iter) ? ListStoreCells.getString(store, iter, column) : null;
    }

    private static boolean firstBoolean(ListStore store, int column) {
        TreeIter iter = new TreeIter();
        return store.getIterFirst(iter) && ListStoreCells.getBoolean(store, iter, column);
    }

    private static String firstTreeValue(TreeStore store, int column) {
        TreeIter iter = new TreeIter();
        return store.getIterFirst(iter) ? TreeStoreCells.getString(store, iter, column) : null;
    }

    private static String firstChildValue(TreeStore store, int column) {
        TreeIter parent = new TreeIter();
        TreeIter child = new TreeIter();
        return store.getIterFirst(parent) && store.iterChildren(child, parent)
                ? TreeStoreCells.getString(store, child, column) : null;
    }

    private void awaitStoreEquals(List<String> expected, String message) throws Exception {
        awaitTrue(() -> {
            try {
                return expected.equals(onLoop(() -> firstColumn(trackersStore)));
            } catch (Exception e) {
                return false;
            }
        }, message + " — expected " + expected);
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timeout: " + message);
            }
            Thread.sleep(50);
        }
    }
}
