package org.odm.gtk4;

import java.net.URI;
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
import org.gnome.gtk.TreeSortable;
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
    private ListStore filesStore;
    private final AtomicReference<Download> selection = new AtomicReference<>();
    private DetailTabsPresenter presenter;

    private record PresenterFixture(ListStore trackers, ListStore peers, ListStore files,
            DetailTabsPresenter presenter) {
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
            ListStore files = Widgets.require(builder, "files_store", ListStore.class);
            return new PresenterFixture(trackers, peers, files,
                    new DetailTabsPresenter(manager, trackers, peers, files, selection::get));
        });
        trackersStore = fixture.trackers();
        peersStore = fixture.peers();
        filesStore = fixture.files();
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
    @DisplayName("file size and progress sorting use raw numeric values")
    void fileRowsUseTypedSortKeys() throws Exception {
        Download download = download("files");
        Mockito.when(manager.getDownloadTrackers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadPeers(download)).thenReturn(List.of());
        Mockito.when(manager.getDownloadFiles(download)).thenReturn(List.of(
                file("ten-kib.bin", 10 * 1024L, 1024L, 1),
                file("nine-kib.bin", 9 * 1024L, 9000L, 2)));

        onLoop(() -> {
            selection.set(download);
            presenter.load();
        });
        awaitTrue(() -> {
            try {
                return onLoop(() -> filesStore.iterNChildren(null)) == 2;
            } catch (Exception e) {
                return false;
            }
        }, "file rows must be populated");

        assertEquals("nine-kib.bin", onLoop(() -> {
            ((TreeSortable) filesStore).setSortColumnId(7, SortType.ASCENDING);
            return firstValue(filesStore, 1);
        }), "9 KiB must sort before 10 KiB by raw byte length");

        assertEquals("nine-kib.bin", onLoop(() -> {
            ((TreeSortable) filesStore).setSortColumnId(8, SortType.DESCENDING);
            return firstValue(filesStore, 1);
        }), "97.66% must sort above 10.00% by precise progress");
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
