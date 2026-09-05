package org.odm.gtk4;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.gnome.glib.MainContext;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Switch;
import org.gnome.gtk.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.tor.TorService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TorControlBindingTest {
    @BeforeAll
    static void initGtk() throws Exception {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        Gtk.init();
    }

    @Test
    void disabledPerDownloadTorPreservesSavedRouteAndTracksService() throws Exception {
        Download download = new Download(URI.create("https://example.test/file.bin"));
        download.setUseProxy(true);
        download.setProxyAddress("socks5h://127.0.0.1:9050");
        NetworkOptionsPane pane = new NetworkOptionsPane(List.of(download));
        ServiceEvents events = new ServiceEvents();
        pane.bindTorService(events.service);
        Switch control = field(pane, "tor", Switch.class);
        assertFalse(control.getSensitive());
        assertTrue(control.getActive());
        pane.applyTo(download);
        assertEquals("socks5h://127.0.0.1:9050", download.getProxyAddress());
        assertFalse(pane.isProxyChanged());
        Window window = new Window();
        window.setChild(pane.widget());
        try {
            window.present();
            pump(() -> !events.listeners.isEmpty());
            events.setRunning(true);
            pump(control::getSensitive);
            events.setRunning(false);
            pump(() -> !control.getSensitive());
            assertTrue(pane.isTorSelected());
            assertFalse(pane.isProxyChanged());
            verify(events.service, never()).start();
        } finally {
            window.destroy();
        }
        assertTrue(events.listeners.isEmpty(), "closed panes must release their service listener");
    }

    @Test
    void settingsTorDefaultIsDisabledWhileServiceOffAndRetained() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setProperty("tor.enabled", "true");
        settings.setGlobalProxyEnabled(true).setGlobalProxyAddress("socks5h://127.0.0.1:9050");
        DownloadManager manager = mock(DownloadManager.class);
        when(manager.getGlobalSettings()).thenReturn(settings);
        ServiceEvents events = new ServiceEvents();
        SettingsDialog dialog = new SettingsDialog(null, manager, null, null, events.service);
        GtkBuilder builder = field(dialog, "builder", GtkBuilder.class);
        Switch control = Widgets.require(builder, "tor_switch", Switch.class);
        Window window = field(dialog, "dialog", Window.class);
        assertFalse(control.getSensitive());
        assertTrue(control.getActive());
        try {
            window.present();
            pump(() -> !events.listeners.isEmpty());
            events.setRunning(true);
            pump(control::getSensitive);
            events.setRunning(false);
            pump(() -> !control.getSensitive());
            assertTrue(control.getActive());
            var collect = SettingsDialog.class.getDeclaredMethod("collectSettings");
            collect.setAccessible(true);
            Object collected = collect.invoke(dialog);
            var settingsAccessor = collected.getClass().getDeclaredMethod("settings");
            settingsAccessor.setAccessible(true);
            GlobalSettings saved = (GlobalSettings) settingsAccessor.invoke(collected);
            assertTrue(saved.getBooleanProperty("tor.enabled", false));
            assertEquals("socks5h://127.0.0.1:9050", saved.getGlobalProxyAddress());
            verify(events.service, never()).start();
            verify(events.service, never()).stop();
        } finally {
            window.close();
        }
        assertTrue(events.listeners.isEmpty());
    }

    private static final class ServiceEvents {
        final TorService service = mock(TorService.class);
        final AtomicBoolean running = new AtomicBoolean();
        final List<TorService.TorServiceListener> listeners = new CopyOnWriteArrayList<>();
        ServiceEvents() {
            when(service.isRunning()).thenAnswer(ignored -> running.get());
            doAnswer(call -> { listeners.add(call.getArgument(0)); return null; })
                    .when(service).addListener(any());
            doAnswer(call -> { listeners.remove(call.getArgument(0)); return null; })
                    .when(service).removeListener(any());
        }
        void setRunning(boolean active) {
            running.set(active);
            listeners.forEach(listener -> listener.onServiceEvent(active
                    ? TorService.TorServiceEvent.STARTED : TorService.TorServiceEvent.STOPPED));
        }
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }

    private static void pump(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            MainContext context = MainContext.default_();
            while (context.pending()) { context.iteration(false); }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "GTK service binding did not settle");
    }
}
