package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.gnome.glib.MainContext;
import org.gnome.gtk.*;
import org.jackett.JackettClient;
import org.jackett.JackettService;
import org.jackett.JackettSettings;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.*;

class SearchTorrentsDialogTest {
    @TempDir Path directory;
    private static final URI MAGNET = URI.create("magnet:?xt=urn:btih:0123456789012345678901234567890123456789");
    private static final URI SECOND = URI.create("magnet:?xt=urn:btih:1123456789012345678901234567890123456789");
    @BeforeAll static void gtk() { Gtk.init(); }

    private DownloadManager manager() {
        DownloadManager manager = mock(DownloadManager.class);
        GlobalSettings settings = new GlobalSettings(); settings.setDefaultDownloadDirectory(directory);
        when(manager.getGlobalSettings()).thenReturn(settings);
        return manager;
    }

    private JackettClient.TorrentResult row(String name, long size, long seeders, long leechers, String date) {
        return new JackettClient.TorrentResult(name, size, seeders, leechers,
                date == null ? null : Instant.parse(date), "Linux", null, MAGNET);
    }

    private JackettClient.TorrentResult second(String name) {
        return new JackettClient.TorrentResult(name, 240, 100, 3, Instant.parse("2026-09-06T00:00:00Z"), "Linux", null, SECOND);
    }

    private JackettService service(JackettClient client) throws Exception {
        JackettService service = mock(JackettService.class);
        when(service.client()).thenReturn(client);
        when(service.status()).thenReturn(new JackettService.Status(JackettService.State.RUNNING, "0.24.99 — Running"));
        return service;
    }

    @Test void checkboxSelectionSurvivesNumericSortingAndUsesStableTorrentIds() throws Exception {
        SearchTorrentsDialog dialog = new SearchTorrentsDialog(null, manager(), null, null, null);
        Window window = field(dialog, "dialog", Window.class);
        try {
            Notebook tabs = field(dialog, "notebook", Notebook.class);
            assertEquals(3, tabs.getNPages());
            for (int i = 0; i < 3; i++) { assertEquals(List.of("Search", "Files", "Options").get(i), tabs.getTabLabelText(tabs.getNthPage(i))); }
            var small = row("Z small", 9, 9, 20, "2026-09-05T00:00:00Z");
            var large = second("A large");
            dialog.showResults(List.of(small, large));
            ListStore store = field(dialog, "resultsStore", ListStore.class);
            TreeView view = field(dialog, "resultsView", TreeView.class);
            view.getSelection().selectPath(TreePath.fromString("0"));
            assertFalse(button(dialog, "download").getSensitive(), "row highlight alone must not check a torrent");
            for (int sort : new int[]{6, 3, 7}) {
                ((TreeSortable) store).setSortColumnId(sort, SortType.ASCENDING);
                TreeIter first = new TreeIter(); assertTrue(store.getIterFirst(first));
                assertEquals(0, ListStoreCells.getInt(store, first, 0));
            }
            toggle(dialog, "0");
            ((TreeSortable) store).setSortColumnId(4, SortType.ASCENDING);
            TreeIter first = new TreeIter(); assertTrue(store.getIterFirst(first));
            assertEquals(1, ListStoreCells.getInt(store, first, 0));
            assertFalse(ListStoreCells.getBoolean(store, first, 9));
            toggle(dialog, "0");
            assertEquals("Download (2)", button(dialog, "download").getLabel());
            TreeStore files = field(dialog, "filesStore", TreeStore.class);
            assertEquals(2, files.iterNChildren(null));
            assertEquals("Z small", TreeStoreCells.getString(files, iter(files, "0"), 1));
            assertEquals("A large", TreeStoreCells.getString(files, iter(files, "1"), 1));
            GtkBuilder builder = field(dialog, "builder", GtkBuilder.class);
            assertNull(builder.getObject("torrent_settings_button"));
            for (String name : List.of("name", "size", "seeders", "leechers", "published")) {
                assertTrue(Widgets.require(builder, "torrent_" + name + "_column", TreeViewColumn.class).getSortColumnId() >= 0);
            }
        } finally { window.destroy(); }
    }

    @Test void groupedFilesKeepOverlappingIndicesAndDuplicateTitlesSeparateWhenQueuing() throws Exception {
        DownloadManagerImpl actual = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        actual.getGlobalSettings().setProperty("ui.offline", "true");
        actual.getGlobalSettings().setGlobalProxyEnabled(false);
        actual.getGlobalSettings().setDefaultDownloadDirectory(directory);
        DownloadManager manager = spy(actual);
        JackettClient client = mock(JackettClient.class);
        var first = row("Linux release", 120, 20, 1, null);
        var second = second("Linux release");
        when(client.resolve(first)).thenReturn(new JackettClient.TorrentSource(MAGNET, null));
        when(client.resolve(second)).thenReturn(new JackettClient.TorrentSource(SECOND, null));
        doReturn(CompletableFuture.completedFuture(List.of(new DownloadFileInfo(1, "linux/README", 20),
                new DownloadFileInfo(2, "linux/image.iso", 100))))
                .when(manager).previewDownloadFiles(eq(MAGNET), nullable(String.class));
        doReturn(CompletableFuture.completedFuture(List.of(new DownloadFileInfo(1, "linux/README", 40),
                new DownloadFileInfo(2, "linux/image.iso", 200))))
                .when(manager).previewDownloadFiles(eq(SECOND), nullable(String.class));
        AtomicInteger queued = new AtomicInteger();
        SearchTorrentsDialog dialog = new SearchTorrentsDialog(null, manager, service(client), null, queued::incrementAndGet);
        Window window = field(dialog, "dialog", Window.class); window.present();
        try {
            dialog.showResults(List.of(first, second)); toggle(dialog, "0"); toggle(dialog, "1");
            field(dialog, "notebook", Notebook.class).setCurrentPage(1);
            TreeStore files = field(dialog, "filesStore", TreeStore.class);
            pump(() -> FileTreeSupport.allIndexes(files).size() == 4);
            assertEquals(2, files.iterNChildren(null));
            assertTrue(TreeStoreCells.getBoolean(files, iter(files, "0"), FileTreeSupport.FOLDER_COLUMN));
            assertEquals("linux", TreeStoreCells.getString(files, iter(files, "0:0"), 1));
            fileToggle(dialog, "0:0:0");
            fileToggle(dialog, "1:0:1");
            assertEquals(List.of(2), FileTreeSupport.selectedIndexes(files, iter(files, "0")));
            assertEquals(List.of(1), FileTreeSupport.selectedIndexes(files, iter(files, "1")));
            toggle(dialog, "0"); assertEquals(1, files.iterNChildren(null));
            toggle(dialog, "0");
            assertEquals(List.of(2), FileTreeSupport.selectedIndexes(files, iter(files, "0")), "rechecking must preserve file exclusions");
            assertEquals(List.of(1), FileTreeSupport.selectedIndexes(files, iter(files, "1")));
            verify(manager, times(1)).previewDownloadFiles(eq(MAGNET), nullable(String.class));
            button(dialog, "download").emitClicked();
            pump(() -> queued.get() == 1);
            assertEquals(2, actual.getAllDownloads().size());
            for (Download accepted : actual.getAllDownloads()) {
                assertEquals(directory, accepted.getDestination());
                assertEquals(accepted.getUri().equals(MAGNET) ? "2" : "1", accepted.getSettings().toMap().get("select-file"));
                actual.cancelDownload(accepted, false).join();
            }
            assertFalse(button(dialog, "download").getSensitive());
        } finally { window.close(); }
    }

    @Test void uncheckedAndClosedPreviewsCannotRestoreRemovedGroups() throws Exception {
        DownloadManager manager = manager();
        CompletableFuture<List<DownloadFileInfo>> first = new CompletableFuture<>();
        CompletableFuture<List<DownloadFileInfo>> second = new CompletableFuture<>();
        when(manager.previewDownloadFiles(eq(MAGNET), nullable(String.class))).thenReturn(first);
        when(manager.previewDownloadFiles(eq(SECOND), nullable(String.class))).thenReturn(second);
        JackettClient client = mock(JackettClient.class);
        when(client.resolve(any())).thenAnswer(call -> new JackettClient.TorrentSource(call.getArgument(0, JackettClient.TorrentResult.class).magnet(), null));
        SearchTorrentsDialog dialog = new SearchTorrentsDialog(null, manager, service(client), null, null);
        Window window = field(dialog, "dialog", Window.class); window.present();
        try {
            dialog.showResults(List.of(row("First", 1, 1, 1, null), second("Second"))); toggle(dialog, "0"); toggle(dialog, "1");
            field(dialog, "notebook", Notebook.class).setCurrentPage(1);
            pump(() -> mockingDetails(manager).getInvocations().stream().filter(call -> call.getMethod().getName().equals("previewDownloadFiles")).count() == 2);
            toggle(dialog, "0");
            first.complete(List.of(new DownloadFileInfo(1, "late.iso", 1)));
            second.complete(List.of(new DownloadFileInfo(1, "current.iso", 2)));
            TreeStore files = field(dialog, "filesStore", TreeStore.class);
            pump(() -> FileTreeSupport.allIndexes(files).size() == 1);
            assertEquals(1, files.iterNChildren(null));
            assertEquals("Second", TreeStoreCells.getString(files, iter(files, "0"), 1));
            CompletableFuture<List<DownloadFileInfo>> closingPreview = new CompletableFuture<>();
            when(manager.previewDownloadFiles(eq(MAGNET), nullable(String.class))).thenReturn(closingPreview);
            dialog.showResults(List.of(row("New search", 1, 1, 1, null)));
            toggle(dialog, "0");
            pump(() -> mockingDetails(manager).getInvocations().stream().filter(call -> call.getMethod().getName().equals("previewDownloadFiles")).count() == 3);
            window.close();
            pump(closingPreview::isCancelled);
            assertTrue(FileTreeSupport.allIndexes(files).isEmpty());
            verify(manager, never()).queueDownload(any());
        } finally { window.destroy(); }
    }

    @Test void partialBatchFailureRetriesOnlyTheFailedCheckedTorrent() throws Exception {
        DownloadManager manager = manager();
        AtomicInteger secondAttempts = new AtomicInteger();
        when(manager.queueDownload(any())).thenAnswer(call -> {
            Download download = call.getArgument(0);
            return download.getUri().equals(SECOND) && secondAttempts.incrementAndGet() == 1
                    ? CompletableFuture.failedFuture(new IOException("queue unavailable")) : CompletableFuture.completedFuture(null);
        });
        JackettClient client = mock(JackettClient.class);
        when(client.resolve(any())).thenAnswer(call -> new JackettClient.TorrentSource(call.getArgument(0, JackettClient.TorrentResult.class).magnet(), null));
        SearchTorrentsDialog dialog = new SearchTorrentsDialog(null, manager, service(client), null, null);
        Window window = field(dialog, "dialog", Window.class);
        try {
            dialog.showResults(List.of(row("First", 1, 1, 1, null), second("Second"))); toggle(dialog, "0"); toggle(dialog, "1");
            button(dialog, "download").emitClicked();
            Label status = field(dialog, "status", Label.class);
            pump(() -> status.getLabel().contains("1 failed"));
            assertEquals("Download (1)", button(dialog, "download").getLabel());
            button(dialog, "download").emitClicked();
            pump(() -> status.getLabel().equals("Queued 1 torrent."));
            assertFalse(button(dialog, "download").getSensitive());
            verify(manager, times(3)).queueDownload(any());
            verify(manager, times(1)).queueDownload(argThat(download -> download.getUri().equals(MAGNET)));
            assertEquals(2, secondAttempts.get());
        } finally { window.destroy(); }
    }

    @Test void indexerFailuresProduceABoundedSearchSummary() throws Exception {
        JackettClient client = mock(JackettClient.class);
        when(client.publicIndexers()).thenReturn(List.of(new JackettClient.Indexer("linux", "Linux", true, "", List.of())));
        when(client.search("linux", 0, Set.of("linux"))).thenReturn(new JackettClient.SearchResults(
                List.of(row("Linux", 1, 1, 1, null), second("Other")), List.of("IndexerException: " + "stack trace ".repeat(200), "Timeout")));
        SearchTorrentsDialog dialog = new SearchTorrentsDialog(null, manager(), service(client), null, null);
        Window window = field(dialog, "dialog", Window.class);
        try {
            GtkBuilder builder = field(dialog, "builder", GtkBuilder.class);
            Widgets.require(builder, "torrent_query_entry", Entry.class).setText("linux");
            button(dialog, "search").emitClicked();
            Label status = field(dialog, "status", Label.class);
            pump(() -> status.getLabel().contains("found"));
            assertEquals("2 torrents found. 2 indexers failed.", status.getLabel());
            assertFalse(status.getWrap());
            assertEquals(org.gnome.pango.EllipsizeMode.END, status.getEllipsize());
        } finally { window.destroy(); }
    }

    @Test void searchEngineSettingsKeepOnlyTheRequestedControlsAndPreserveInternalPort() throws Exception {
        GlobalSettings values = new GlobalSettings();
        values.setProperty(JackettSettings.PORT, "19117");
        values.setProperty(JackettSettings.START_WITH_ODM, "true");
        values.setProperty(JackettSettings.INDEXERS, "linux");
        Window parent = new Window();
        JackettSettingsPane pane = new JackettSettingsPane(parent, values, null);
        try {
            GlobalSettings snapshot = values.copy(); pane.collect(snapshot);
            assertEquals("19117", snapshot.getProperty(JackettSettings.PORT, ""));
            assertEquals("true", snapshot.getProperty(JackettSettings.START_WITH_ODM, ""));
            assertEquals("linux", snapshot.getProperty(JackettSettings.INDEXERS, ""));
            GtkBuilder builder = field(pane, "builder", GtkBuilder.class);
            for (String id : List.of("jackett_archive_button", "jackett_dashboard_button", "jackett_port_spin",
                    "jackett_configure_button", "jackett_paths_label")) { assertNull(builder.getObject(id)); }
            assertEquals("Test", button(pane, "test").getLabel());
            assertFalse(button(pane, "test").getSensitive());
            pane.reset(); pane.collect(snapshot);
            assertEquals("", snapshot.getProperty(JackettSettings.INDEXERS, "missing"));
            assertEquals("19117", snapshot.getProperty(JackettSettings.PORT, ""), "a hidden implementation setting must not be overwritten by the pane");
            assertEquals("false", snapshot.getProperty(JackettSettings.START_WITH_ODM, ""));
        } finally { pane.close(); parent.destroy(); }
    }

    @Test void openingAndRefreshingSettingsDoesNotProbeIndexers() throws Exception {
        JackettClient client = mock(JackettClient.class);
        when(client.publicIndexers()).thenReturn(List.of(new JackettClient.Indexer("linux", "Linux", true, "", List.of())));
        for (int i = 0; i < 2; i++) {
            SettingsDialog dialog = new SettingsDialog(null, manager(), null, null, null, service(client));
            Window window = field(dialog, "dialog", Window.class);
            try {
                JackettSettingsPane pane = field(dialog, "jackett", JackettSettingsPane.class);
                Button test = button(pane, "test");
                pump(test::getSensitive);
                ListStore store = field(pane, "store", ListStore.class);
                assertEquals("Not tested", rowStatus(store, "linux"));
                verify(client, never()).test(anyString());
                button(pane, "refresh").emitClicked();
                pump(test::getSensitive);
                assertEquals("Not tested", rowStatus(store, "linux"));
                verify(client, never()).test(anyString());
            } finally { window.destroy(); }
        }
        verify(client, times(4)).publicIndexers();
    }

    @Test void indexersAreTestedOnDemandAndCheckingAppliesTheirDefaultConfiguration() throws Exception {
        JackettClient client = mock(JackettClient.class);
        when(client.publicIndexers()).thenReturn(List.of(new JackettClient.Indexer("good", "Good", false, "", List.of()),
                new JackettClient.Indexer("bad", "Bad", false, "", List.of()), new JackettClient.Indexer("existing", "Existing", true, "", List.of())));
        var defaults = (com.fasterxml.jackson.databind.node.ArrayNode) new ObjectMapper().readTree("""
                [{"id":"enabled","type":"inputbool","value":true},
                 {"id":"categories","type":"inputcheckbox","values":["x"],"options":{"x":"X"}}]
                """);
        when(client.configuration(anyString())).thenReturn(defaults);
        doThrow(new IOException("site unavailable & retry failed")).when(client).test("bad");
        doThrow(new IOException("defaults require extra setup")).when(client).configure(eq("bad"), any());
        GlobalSettings settings = new GlobalSettings(); settings.setProperty(JackettSettings.INDEXERS, "");
        Window parent = new Window();
        JackettSettingsPane pane = new JackettSettingsPane(parent, settings, service(client));
        try {
            ListStore store = field(pane, "store", ListStore.class);
            testIndexers(pane);
            pump(() -> rowStatus(store, "good").equals("Passed") && rowStatus(store, "bad").equals("Failed"));
            verify(client).test("good"); verify(client).test("bad"); verify(client).test("existing");
            verify(client, never()).configure(anyString(), any());
            assertEquals("0.24.99 — Running", field(pane, "status", Label.class).getLabel());
            assertFalse(indexerTooltip(pane, store, "good"), "successful checks must not open an empty tooltip");
            assertTrue(indexerTooltip(pane, store, "bad"), "failure details remain available as a tooltip");
            ((TreeSortable) store).setSortColumnId(2, SortType.DESCENDING);
            toggleIndexer(pane, store, "good");
            pump(() -> rowChecked(store, "good"));
            verify(client).configure(eq("good"), eq(defaults)); verify(client).test("good");
            assertEquals("Passed", rowStatus(store, "good"));
            toggleIndexer(pane, store, "existing");
            toggleIndexer(pane, store, "bad");
            pump(() -> rowStatus(store, "bad").equals("Setup failed"));
            assertFalse(rowChecked(store, "bad"));
            GlobalSettings saved = settings.copy(); pane.collect(saved);
            assertEquals(Set.of("good", "existing"), Set.of(saved.getProperty(JackettSettings.INDEXERS, "").split(",")));
            verify(client, never()).configure(eq("existing"), any());
        } finally { pane.close(); parent.destroy(); }
    }

    @Test void selectingAndRemovingIndexersPreservesBothTestOutcomesAcrossReopening() throws Exception {
        JackettClient client = mock(JackettClient.class);
        when(client.publicIndexers()).thenReturn(List.of(
                new JackettClient.Indexer("good", "Good", false, "", List.of()),
                new JackettClient.Indexer("bad", "Bad", false, "", List.of())));
        when(client.configuration(anyString())).thenReturn(new ObjectMapper().createArrayNode());
        doThrow(new IOException("still unavailable")).when(client).test("bad");
        JackettService session = service(client);
        GlobalSettings settings = new GlobalSettings();
        settings.setProperty(JackettSettings.INDEXERS, "");
        Window parent = new Window();
        JackettSettingsPane pane = new JackettSettingsPane(parent, settings, session);
        try {
            ListStore store = field(pane, "store", ListStore.class);
            testIndexers(pane);
            pump(button(pane, "test")::getSensitive);
            for (String id : List.of("good", "bad")) {
                String expected = id.equals("good") ? "Passed" : "Failed";
                toggleIndexer(pane, store, id);
                pump(() -> rowChecked(store, id));
                assertEquals(expected, rowStatus(store, id));
                toggleIndexer(pane, store, id);
                pump(() -> !rowChecked(store, id));
                assertEquals(expected, rowStatus(store, id));
                verify(client).configure(eq(id), any());
                verify(client).unconfigure(id);
                verify(client).test(id);
            }
        } finally { pane.close(); }
        JackettSettingsPane reopened = new JackettSettingsPane(parent, settings, session);
        try {
            pump(button(reopened, "test")::getSensitive);
            ListStore store = field(reopened, "store", ListStore.class);
            assertEquals("Passed", rowStatus(store, "good"));
            assertEquals("Failed", rowStatus(store, "bad"));
            assertEquals("still unavailable", ListStoreCells.getString(store, indexerIter(store, "bad"), 3));
            verify(client).test("good"); verify(client).test("bad");
        } finally { reopened.close(); parent.destroy(); }
    }

    @Test void completedIndexerResultsSurviveReopeningAndRefreshAndCanBeRetested() throws Exception {
        JackettClient client = mock(JackettClient.class);
        when(client.publicIndexers()).thenReturn(List.of(
                new JackettClient.Indexer("good", "Good", true, "", List.of()),
                new JackettClient.Indexer("bad", "Bad", true, "", List.of())));
        doNothing().doThrow(new IOException("new failure")).when(client).test("good");
        doThrow(new IOException("old failure")).doNothing().when(client).test("bad");
        JackettService session = service(client);

        for (int opening = 0; opening < 3; opening++) {
            SettingsDialog dialog = new SettingsDialog(null, manager(), null, null, null, session);
            Window window = field(dialog, "dialog", Window.class);
            try {
                JackettSettingsPane pane = field(dialog, "jackett", JackettSettingsPane.class);
                ListStore store = field(pane, "store", ListStore.class);
                Button test = button(pane, "test");
                pump(test::getSensitive);
                if (opening == 0) {
                    assertEquals("Not tested", rowStatus(store, "good"));
                    test.emitClicked();
                    pump(test::getSensitive);
                } else if (opening == 1) {
                    assertEquals("Passed", rowStatus(store, "good"));
                    assertEquals("Failed", rowStatus(store, "bad"));
                    assertEquals("old failure", ListStoreCells.getString(store, indexerIter(store, "bad"), 3));
                    assertFalse(indexerTooltip(pane, store, "good"));
                    button(pane, "refresh").emitClicked();
                    pump(test::getSensitive);
                    assertEquals("Passed", rowStatus(store, "good"));
                    assertEquals("Failed", rowStatus(store, "bad"));
                    verify(client).test("good"); verify(client).test("bad");
                    test.emitClicked();
                    pump(test::getSensitive);
                }
                assertEquals(opening == 0 ? "Passed" : "Failed", rowStatus(store, "good"));
                assertEquals(opening == 0 ? "Failed" : "Passed", rowStatus(store, "bad"));
                assertEquals("1 of 2 indexers passed.", field(pane, "actionStatus", Label.class).getLabel());
                if (opening > 0) {
                    assertEquals("new failure", ListStoreCells.getString(store, indexerIter(store, "good"), 3));
                    assertFalse(indexerTooltip(pane, store, "bad"));
                }
            } finally { window.close(); }
        }
        verify(client, times(2)).test("good"); verify(client, times(2)).test("bad");

        // A new application session owns a new service and starts without test history.
        SettingsDialog nextSession = new SettingsDialog(null, manager(), null, null, null, service(client));
        Window window = field(nextSession, "dialog", Window.class);
        try {
            JackettSettingsPane pane = field(nextSession, "jackett", JackettSettingsPane.class);
            Button test = button(pane, "test");
            pump(test::getSensitive);
            ListStore store = field(pane, "store", ListStore.class);
            assertEquals("Not tested", rowStatus(store, "good"));
            assertEquals("Not tested", rowStatus(store, "bad"));
            assertEquals("", field(pane, "actionStatus", Label.class).getLabel());
            verify(client, times(2)).test("good"); verify(client, times(2)).test("bad");
        } finally { window.close(); }
    }

    @Test void closingDuringRetestPreservesTheLastCompletedResult() throws Exception {
        JackettClient client = mock(JackettClient.class);
        when(client.publicIndexers()).thenReturn(List.of(new JackettClient.Indexer("linux", "Linux", true, "", List.of())));
        CompletableFuture<Void> finishRetest = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) { throw new IOException("last completed failure"); }
            finishRetest.join();
            finished.incrementAndGet();
            return null;
        }).when(client).test("linux");
        JackettService session = service(client);
        Window parent = new Window();
        JackettSettingsPane first = new JackettSettingsPane(parent, new GlobalSettings(), session);
        try {
            ListStore store = field(first, "store", ListStore.class);
            testIndexers(first);
            pump(() -> rowStatus(store, "linux").equals("Failed"));
            testIndexers(first);
            pump(() -> calls.get() == 2);
            assertEquals("Testing…", rowStatus(store, "linux"));
        } finally { first.close(); finishRetest.complete(null); }
        pump(() -> finished.get() == 1);

        JackettSettingsPane reopened = new JackettSettingsPane(parent, new GlobalSettings(), session);
        try {
            Button test = button(reopened, "test");
            pump(test::getSensitive);
            ListStore store = field(reopened, "store", ListStore.class);
            assertEquals("Failed", rowStatus(store, "linux"));
            assertEquals("last completed failure", ListStoreCells.getString(store, indexerIter(store, "linux"), 3));
            verify(client, times(2)).test("linux");
        } finally { reopened.close(); parent.destroy(); }
    }

    @Test void uncheckingWaitsForTheProbeRemovesConfigurationAndRecheckingUsesDefaults() throws Exception {
        JackettClient client = mock(JackettClient.class);
        when(client.publicIndexers()).thenReturn(List.of(new JackettClient.Indexer("linux", "Linux", true, "", List.of())));
        var defaults = new ObjectMapper().createArrayNode();
        when(client.configuration("linux")).thenReturn(defaults);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        AtomicInteger probes = new AtomicInteger();
        doAnswer(call -> {
            if (probes.incrementAndGet() == 1) { assertTrue(releaseProbe.await(5, TimeUnit.SECONDS)); }
            return null;
        }).when(client).test("linux");
        Window parent = new Window();
        GlobalSettings values = new GlobalSettings(); values.setProperty(JackettSettings.INDEXERS, "linux");
        JackettSettingsPane pane = new JackettSettingsPane(parent, values, service(client));
        try {
            ListStore store = field(pane, "store", ListStore.class);
            testIndexers(pane);
            pump(() -> probes.get() == 1);
            assertFalse(button(pane, "test").getSensitive(), "an in-flight test cannot be started again");
            assertTrue(rowChecked(store, "linux"));
            toggleIndexer(pane, store, "linux");
            assertEquals("Removing…", rowStatus(store, "linux"));
            assertTrue(rowChecked(store, "linux"), "the check remains until Jackett confirms removal");
            verify(client, never()).unconfigure(anyString());
            releaseProbe.countDown();
            pump(() -> !rowChecked(store, "linux") && rowStatus(store, "linux").equals("Passed"));
            verify(client).unconfigure("linux");
            GlobalSettings saved = values.copy(); pane.collect(saved);
            assertEquals("", saved.getProperty(JackettSettings.INDEXERS, "missing"));
            assertFalse(field(pane, "configured", Set.class).contains("linux"));
            toggleIndexer(pane, store, "linux");
            pump(() -> rowChecked(store, "linux"));
            verify(client).configure("linux", defaults);
            verify(client).test("linux");
            testIndexers(pane);
            pump(() -> rowStatus(store, "linux").equals("Passed"));
            verify(client, times(2)).test("linux");
            pane.collect(saved); assertEquals("linux", saved.getProperty(JackettSettings.INDEXERS, ""));
        } finally { releaseProbe.countDown(); pane.close(); parent.destroy(); }
    }

    @Test void failedIndexerRemovalRetainsTheCheckAndCanBeRetried() throws Exception {
        JackettClient client = mock(JackettClient.class);
        when(client.publicIndexers()).thenReturn(List.of(new JackettClient.Indexer("linux", "Linux", true, "", List.of())));
        doThrow(new IOException("Jackett is busy")).doNothing().when(client).unconfigure("linux");
        Window parent = new Window();
        GlobalSettings values = new GlobalSettings(); values.setProperty(JackettSettings.INDEXERS, "linux");
        JackettSettingsPane pane = new JackettSettingsPane(parent, values, service(client));
        try {
            ListStore store = field(pane, "store", ListStore.class);
            pump(() -> rowStatus(store, "linux").equals("Not tested"));
            toggleIndexer(pane, store, "linux");
            pump(() -> rowStatus(store, "linux").equals("Removal failed"));
            assertTrue(rowChecked(store, "linux"));
            GlobalSettings saved = values.copy(); pane.collect(saved);
            assertEquals("linux", saved.getProperty(JackettSettings.INDEXERS, ""));
            assertTrue(indexerTooltip(pane, store, "linux"));
            toggleIndexer(pane, store, "linux");
            pump(() -> !rowChecked(store, "linux") && rowStatus(store, "linux").equals("Not tested"));
            verify(client, times(2)).unconfigure("linux");
            verify(client, never()).test(anyString());
            assertFalse(indexerTooltip(pane, store, "linux"));
        } finally { pane.close(); parent.destroy(); }
    }

    @Test void removalDoesNotWaitForUnrelatedIndexerProbes() throws Exception {
        JackettClient client = mock(JackettClient.class);
        when(client.publicIndexers()).thenReturn(java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> new JackettClient.Indexer("id" + i, "Indexer " + i, i == 0, "", List.of())).toList());
        CountDownLatch slowProbes = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            if (!call.getArgument(0).equals("id0")) {
                slowProbes.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return null;
        }).when(client).test(anyString());
        Window parent = new Window();
        GlobalSettings values = new GlobalSettings(); values.setProperty(JackettSettings.INDEXERS, "id0");
        JackettSettingsPane pane = new JackettSettingsPane(parent, values, service(client));
        try {
            ListStore store = field(pane, "store", ListStore.class);
            testIndexers(pane);
            pump(() -> slowProbes.getCount() == 0 && rowStatus(store, "id0").equals("Passed"));
            toggleIndexer(pane, store, "id0");
            pump(() -> !rowChecked(store, "id0"));
            verify(client).unconfigure("id0");
            assertEquals(1, release.getCount(), "slow indexers are still being probed");
        } finally { release.countDown(); pane.close(); parent.destroy(); }
    }

    @Test void probesAreBoundedKeepTheTableUsableAndCannotReselectAfterClose() throws Exception {
        JackettClient client = mock(JackettClient.class);
        when(client.publicIndexers()).thenReturn(java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> new JackettClient.Indexer("id" + i, "Indexer " + i, true, "", List.of())).toList());
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(); AtomicInteger maximum = new AtomicInteger();
        doAnswer(call -> {
            int running = active.incrementAndGet(); maximum.accumulateAndGet(running, Math::max);
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); } finally { active.decrementAndGet(); }
            return null;
        }).when(client).test(anyString());
        Window parent = new Window();
        GlobalSettings values = new GlobalSettings(); values.setProperty(JackettSettings.INDEXERS, "");
        JackettSettingsPane pane = new JackettSettingsPane(parent, values, service(client));
        try {
            ListStore store = field(pane, "store", ListStore.class);
            testIndexers(pane);
            pump(() -> active.get() == 4);
            assertTrue(field(pane, "tree", TreeView.class).getSensitive());
            toggleIndexer(pane, store, "id6");
            assertTrue(rowChecked(store, "id6"));
            pane.close(); release.countDown();
            pump(() -> active.get() == 0);
            assertEquals(4, maximum.get());
            verify(client, never()).configure(anyString(), any());
        } finally { release.countDown(); pane.close(); parent.destroy(); }
    }

    @Test void searchEngineIsBeforeAdvancedAndResetTargetsTheCorrectTab() throws Exception {
        SettingsDialog dialog = new SettingsDialog(null, manager(), null);
        Window window = field(dialog, "dialog", Window.class);
        try {
            Notebook notebook = field(dialog, "settingsNotebook", Notebook.class);
            assertEquals(7, notebook.getNPages());
            assertEquals("Search Engine", notebook.getTabLabelText(notebook.getNthPage(5)));
            assertEquals("Advanced", notebook.getTabLabelText(notebook.getNthPage(6)));
            GtkBuilder builder = field(dialog, "builder", GtkBuilder.class);
            CheckButton scheduler = Widgets.require(builder, "enable_scheduling_check", CheckButton.class);
            CheckButton override = Widgets.require(builder, "override_output_path_check", CheckButton.class);
            override.setActive(true);
            scheduler.setActive(true);
            dialog.resetTabToDefaults(0);
            assertTrue(override.getActive(), "General reset must preserve the Advanced override setting");
            dialog.resetTabToDefaults(5);
            assertTrue(scheduler.getActive());
            assertTrue(override.getActive());
            assertTrue(dialog.statusText().startsWith("Search Engine defaults"));
            dialog.resetTabToDefaults(6);
            assertFalse(scheduler.getActive());
            assertFalse(override.getActive());
            assertTrue(dialog.statusText().startsWith("Advanced defaults"));
        } finally { window.destroy(); }
    }

    @Test void stoppingAStartingServiceIgnoresTheOldStartupCompletion() throws Exception {
        var state = new java.util.concurrent.atomic.AtomicReference<>(new JackettService.Status(JackettService.State.STOPPED, "Stopped"));
        var stopped = new CountDownLatch(1);
        JackettService service = mock(JackettService.class);
        when(service.status()).thenAnswer(call -> state.get()); when(service.isInstalled()).thenReturn(true);
        doAnswer(call -> {
            state.set(new JackettService.Status(JackettService.State.STARTING, "Starting"));
            assertTrue(stopped.await(5, TimeUnit.SECONDS)); throw new IOException("Startup cancelled");
        }).when(service).start();
        doAnswer(call -> {
            state.set(new JackettService.Status(JackettService.State.STOPPED, "Stopped"));
            stopped.countDown(); return null;
        }).when(service).stop();
        Window parent = new Window();
        JackettSettingsPane pane = new JackettSettingsPane(parent, new GlobalSettings(), service);
        try {
            GtkBuilder builder = field(pane, "builder", GtkBuilder.class);
            Button stop = Widgets.require(builder, "jackett_stop_button", Button.class);
            Widgets.require(builder, "jackett_start_button", Button.class).emitClicked();
            pump(stop::getSensitive); stop.emitClicked();
            pump(() -> Widgets.require(builder, "jackett_start_button", Button.class).getSensitive());
            assertEquals("Stopped", Widgets.require(builder, "jackett_status_label", Label.class).getLabel());
            verify(service).stop(); verify(service, never()).client();
        } finally { stopped.countDown(); pane.close(); parent.destroy(); }
    }

    private static Button button(SearchTorrentsDialog dialog, String name) throws Exception {
        return Widgets.require(field(dialog, "builder", GtkBuilder.class), "torrent_" + name + "_button", Button.class);
    }
    private static Button button(JackettSettingsPane pane, String name) throws Exception {
        return Widgets.require(field(pane, "builder", GtkBuilder.class), "jackett_" + name + "_button", Button.class);
    }
    private static void testIndexers(JackettSettingsPane pane) throws Exception {
        Button test = button(pane, "test");
        pump(test::getSensitive);
        test.emitClicked();
    }
    private static void toggle(SearchTorrentsDialog dialog, String path) throws Exception {
        Widgets.require(field(dialog, "builder", GtkBuilder.class), "torrent_result_toggle", CellRendererToggle.class).emitToggled(path);
    }
    private static void fileToggle(SearchTorrentsDialog dialog, String path) throws Exception {
        Widgets.require(field(dialog, "builder", GtkBuilder.class), "torrent_file_toggle", CellRendererToggle.class).emitToggled(path);
    }
    private static void toggleIndexer(JackettSettingsPane pane, ListStore store, String id) throws Exception {
        TreeIter row = indexerIter(store, id); assertNotNull(row);
        TreePath path = store.getPath(row);
        try { Widgets.require(field(pane, "builder", GtkBuilder.class), "jackett_indexer_toggle", CellRendererToggle.class).emitToggled(path.toString()); }
        finally { org.javagi.interop.MemoryCleaner.free(path.handle()); }
    }
    private static boolean indexerTooltip(JackettSettingsPane pane, ListStore store, String id) throws Exception {
        TreeView tree = field(pane, "tree", TreeView.class);
        TreePath path = store.getPath(indexerIter(store, id));
        try {
            tree.setCursor(path, null, false);
            return tree.emitQueryTooltip(0, 0, true, new Tooltip());
        } finally { org.javagi.interop.MemoryCleaner.free(path.handle()); }
    }
    private static TreeIter indexerIter(ListStore store, String id) {
        TreeIter iter = new TreeIter();
        if (store.getIterFirst(iter)) { do { if (id.equals(ListStoreCells.getString(store, iter, 1))) { return iter; } } while (store.iterNext(iter)); }
        return null;
    }
    private static String rowStatus(ListStore store, String id) { TreeIter iter = indexerIter(store, id); return iter == null ? "" : ListStoreCells.getString(store, iter, 4); }
    private static boolean rowChecked(ListStore store, String id) { TreeIter iter = indexerIter(store, id); return iter != null && ListStoreCells.getBoolean(store, iter, 0); }
    private static TreeIter iter(TreeStore store, String path) { TreeIter iter = new TreeIter(); assertTrue(store.getIterFromString(iter, path)); return iter; }
    private static <T> T field(Object object, String name, Class<T> type) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(object));
    }
    private static void pump(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            while (MainContext.default_().pending()) { MainContext.default_().iteration(false); }
            if (done.getAsBoolean()) { return; }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("GTK operation did not finish");
    }
}
