package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.gnome.glib.MainContext;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.GlobalSettings;
import org.manager.clipboard.ClipboardService;
import org.manager.clipboard.ClipboardSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadManager;
import org.mockito.ArgumentCaptor;

@Timeout(20)
class MainWindowTrayActionsTest {
    @BeforeAll
    static void gtk() throws Exception {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        Gtk.init();
    }

    @Test
    void trayAndMainWindowShareModesAndDownloadSensitivityEvenWhileHidden() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MainWindow main = fixture.main;
            AtomicReference<Map<String, TrayMenu.ActionState>> latest = new AtomicReference<>();
            main.setTrayStateListener(latest::set);
            assertEquals(8, latest.get().size());
            assertFalse(latest.get().get("pause-all").enabled());
            assertFalse(latest.get().get("resume-all").enabled());
            assertFalse(latest.get().get("clipboard-monitoring").checked());
            assertFalse(latest.get().get("clipboard-silent").enabled());
            main.activateTrayAction("resume-all");
            verify(fixture.manager, never()).resumeAllDownloads();

            main.activateTrayAction("clipboard-monitoring");
            assertTrue(fixture.monitoring.get());
            assertTrue(latest.get().get("clipboard-monitoring").checked());
            assertTrue(latest.get().get("clipboard-silent").enabled());
            main.activateTrayAction("clipboard-silent");
            assertTrue(fixture.settings.getBooleanProperty("ui.clipboardSilent", false));
            assertTrue(latest.get().get("clipboard-silent").checked());
            fixture.window().lookupAction("clipboard-silent").activate(null);
            assertFalse(latest.get().get("clipboard-silent").checked(), "Menubar changes must reach the tray");

            fixture.counts.put(Download.Status.DOWNLOADING, 1);
            fixture.listener.onDownloadStart(null);
            waitFor(() -> latest.get().get("pause-all").enabled());
            assertFalse(fixture.window().getVisible(), "Background state changes must not open the window");
            main.activateTrayAction("pause-all");
            verify(fixture.manager).pauseAllDownloads();
            waitFor(() -> latest.get().get("resume-all").enabled());
            assertFalse(latest.get().get("pause-all").enabled());

            main.activateTrayAction("offline");
            waitFor(() -> latest.get().get("offline").checked());
            assertFalse(latest.get().get("resume-all").enabled());
            main.activateTrayAction("resume-all");
            verify(fixture.manager, never()).resumeAllDownloads();
            main.activateTrayAction("offline");
            waitFor(() -> !latest.get().get("offline").checked() && latest.get().get("resume-all").enabled());
            main.activateTrayAction("resume-all");
            verify(fixture.manager).resumeAllDownloads();
            waitFor(() -> latest.get().get("pause-all").enabled());

            fixture.monitoring.set(false);
            fixture.listener.onDownloadStatusChanged(null, Download.Status.DOWNLOADING, Download.Status.DOWNLOADING);
            waitFor(() -> !latest.get().get("clipboard-monitoring").checked());
            assertFalse(latest.get().get("clipboard-silent").enabled());
        }
    }

    @Test
    void openNewDownloadAndExitUseTheExistingWindowFlow() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MainWindow main = fixture.main;
            main.activateTrayAction("open");
            assertTrue(fixture.window().getVisible());
            fixture.window().setVisible(false);
            main.activateTrayAction("new-download");
            assertTrue(fixture.window().getVisible());
            Window downloadDialog = null;
            var windows = Window.getToplevels();
            for (int i = 0; i < windows.getNItems(); i++) {
                Window candidate = (Window) windows.getItem(i);
                if (candidate.getVisible() && "New Download".equals(candidate.getTitle())) { downloadDialog = candidate; }
            }
            assertNotNull(downloadDialog);
            downloadDialog.close();
            AtomicBoolean exited = new AtomicBoolean();
            main.setFinalCloseDelegate(() -> exited.set(true));
            main.activateTrayAction("quit");
            assertNotNull(main.exitConfirmationDialog());
            assertFalse(exited.get());
            main.exitConfirmationDialog().response(org.gnome.gtk.ResponseType.ACCEPT.getValue());
            assertTrue(exited.get());
            assertTrue(main.trayActionStates().values().stream().noneMatch(TrayMenu.ActionState::enabled));
        }
    }

    @Test
    void downloadErrorLabelAndDetailsHideJavaExceptionsWithoutChangingTheRecord() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String original = "java.io.IOException: Disk full\n\tat org.odm.Transfer.run(Transfer.java:12)";
            Download download = new Download(java.net.URI.create("https://example.test/file.zip"));
            download.setStatus(Download.Status.ERROR);
            download.setErrorMessage(original);
            when(fixture.manager.getDownloads(anyInt(), anyInt())).thenReturn(List.of(download));
            when(fixture.manager.getDownload(download.getId())).thenReturn(download);
            fixture.counts.put(Download.Status.ERROR, 1);
            fixture.listener.onDownloadError(download, original);
            org.gnome.gtk.GtkBuilder builder = fixture.builder();
            var view = Widgets.require(builder, "download_treeview", org.gnome.gtk.TreeView.class);
            waitFor(() -> view.getModel().iterNChildren(null) == 1);
            view.getSelection().selectAll();
            var label = Widgets.require(builder, "info_error_value", org.gnome.gtk.Label.class);
            assertEquals("Disk full", label.getLabel());
            Widgets.require(builder, "info_error_button", org.gnome.gtk.Button.class).emitClicked();
            Window details = null;
            var windows = Window.getToplevels();
            for (int i = 0; i < windows.getNItems(); i++) {
                Window candidate = (Window) windows.getItem(i);
                if ("Download Error".equals(candidate.getTitle())) { details = candidate; }
            }
            assertNotNull(details);
            try {
                var output = (org.gnome.gtk.TextView) find(details, "action_output_text_view");
                var buffer = output.getBuffer();
                var start = new org.gnome.gtk.TextIter();
                var end = new org.gnome.gtk.TextIter();
                buffer.getBounds(start, end);
                assertEquals("Disk full", buffer.getText(start, end, false));
                assertEquals(original, download.getErrorMessage());
            } finally { details.close(); }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DownloadManager manager = mock(DownloadManager.class);
        final GlobalSettings settings = spy(new GlobalSettings());
        final AtomicBoolean monitoring = new AtomicBoolean();
        final Map<Download.Status, Integer> counts = new ConcurrentHashMap<>();
        final MainWindow main;
        final DownloadListener listener;

        Fixture() {
            doReturn(true).when(settings).save();
            settings.setProperty("ui.offline", "false");
            settings.setProperty("ui.clipboardSilent", "false");
            when(manager.getGlobalSettings()).thenReturn(settings);
            ClipboardService clipboard = mock(ClipboardService.class);
            when(clipboard.getSettings()).thenReturn(new ClipboardSettings());
            when(manager.getClipboardService()).thenReturn(clipboard);
            when(manager.isClipboardMonitoringEnabled()).thenAnswer(ignored -> monitoring.get());
            doAnswer(call -> { monitoring.set(call.getArgument(0)); return null; })
                    .when(manager).setClipboardMonitoringEnabled(anyBoolean());
            when(manager.getAllDownloads()).thenReturn(List.of());
            when(manager.getDownloadCountByStatus(any())).thenAnswer(call -> counts.getOrDefault(call.getArgument(0), 0));
            when(manager.getDownloadCount()).thenAnswer(ignored -> counts.values().stream().mapToInt(Integer::intValue).sum());
            when(manager.pauseAllDownloads()).thenAnswer(ignored -> {
                counts.clear(); counts.put(Download.Status.PAUSED, 1);
                return CompletableFuture.completedFuture(null);
            });
            when(manager.resumeAllDownloads()).thenAnswer(ignored -> {
                counts.clear(); counts.put(Download.Status.DOWNLOADING, 1);
                return CompletableFuture.completedFuture(null);
            });
            when(manager.saveState()).thenReturn(CompletableFuture.completedFuture(null));
            when(manager.reconsiderQueuedDownloads()).thenReturn(CompletableFuture.completedFuture(null));
            main = new MainWindow(null, manager, mock(org.tor.TorService.class),
                    mock(org.manager.schedule.ScheduleManager.class));
            var listeners = ArgumentCaptor.forClass(DownloadListener.class);
            verify(manager).addDownloadListener(listeners.capture());
            listener = listeners.getValue();
        }

        ApplicationWindow window() throws Exception {
            var field = MainWindow.class.getDeclaredField("window");
            field.setAccessible(true);
            return (ApplicationWindow) field.get(main);
        }

        org.gnome.gtk.GtkBuilder builder() throws Exception {
            var field = MainWindow.class.getDeclaredField("uiBuilder");
            field.setAccessible(true);
            return (org.gnome.gtk.GtkBuilder) field.get(main);
        }

        @Override public void close() {
            main.dispose();
            while (MainContext.default_().pending()) { MainContext.default_().iteration(false); }
        }
    }

    private static org.gnome.gtk.Widget find(org.gnome.gtk.Widget parent, String id) {
        if (id.equals(parent.getBuildableId())) { return parent; }
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            var found = find(child, id);
            if (found != null) { return found; }
        }
        return null;
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            while (MainContext.default_().pending()) { MainContext.default_().iteration(false); }
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "Window action state did not update");
    }
}
