package org.odm.gtk4;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;
import org.manager.download.DownloadManager;
import org.tor.TorService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TorServiceControllerTest {
    private final GlobalSettings settings = new GlobalSettings() {
        @Override public boolean save() { return true; }
    };
    private final DownloadManager manager = mock(DownloadManager.class);
    private final TorService service = mock(TorService.class);

    private TorServiceController controller() {
        when(manager.getGlobalSettings()).thenReturn(settings);
        when(manager.setTorServiceAvailable(anyBoolean(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(service.getSocksPort()).thenReturn(9050);
        when(service.stop()).thenReturn(true);
        return new TorServiceController(manager, service);
    }

    @Test
    void toolbarStartsAndStopsServiceWithoutChangingNetworkPreferences() throws Exception {
        settings.setGlobalProxyEnabled(true).setGlobalProxyAddress("http://proxy.example:8080");
        settings.setProperty("tor.enabled", "false");
        when(service.start()).thenReturn(CompletableFuture.completedFuture(true));
        TorServiceController controller = controller();
        assertTrue(controller.setEnabled(true).get(5, TimeUnit.SECONDS));
        assertTrue(TorServiceController.isEnabledAtStartup(settings));
        assertEquals("http://proxy.example:8080", settings.getGlobalProxyAddress());
        assertFalse(settings.getBooleanProperty("tor.enabled", true));
        assertFalse(controller.setEnabled(false).get(5, TimeUnit.SECONDS));
        verify(service).stop();
        verify(manager).setTorServiceAvailable(false, 9050);
        verify(manager).setTorServiceAvailable(true, 9050);
        verify(manager, never()).applyGlobalSettingsToActiveDownloads();
        assertTrue(settings.isGlobalProxyEnabled());
        assertEquals("http://proxy.example:8080", settings.getGlobalProxyAddress());
        assertFalse(TorServiceController.isEnabledAtStartup(settings));
    }

    @Test
    void explicitNetworkTorSurvivesServiceOffAndFailedStart() throws Exception {
        settings.setProperty("tor.enabled", "true");
        settings.setGlobalProxyEnabled(true).setGlobalProxyAddress("socks5h://127.0.0.1:9050");
        assertTrue(TorServiceController.isEnabledAtStartup(settings), "legacy state migrates");
        TorServiceController controller = controller();
        assertFalse(controller.setEnabled(false).get(5, TimeUnit.SECONDS));
        assertFalse(TorServiceController.isEnabledAtStartup(settings), "service off overrides legacy default");
        when(service.start()).thenReturn(CompletableFuture.completedFuture(false));
        assertFalse(controller.setEnabled(true).get(5, TimeUnit.SECONDS));
        assertTrue(settings.getBooleanProperty("tor.enabled", false));
        assertEquals("socks5h://127.0.0.1:9050", settings.getGlobalProxyAddress());
    }

    @Test
    void offDuringStartupStopsLateDaemonWithoutResumingRecords() throws Exception {
        CompletableFuture<Boolean> starting = new CompletableFuture<>();
        when(service.start()).thenReturn(starting);
        TorServiceController controller = controller();
        CompletableFuture<Boolean> on = controller.setEnabled(true);
        verify(service, timeout(2000)).start();
        CompletableFuture<Boolean> off = controller.setEnabled(false);
        verify(service, timeout(2000)).stop();
        starting.complete(true);
        assertFalse(on.get(5, TimeUnit.SECONDS));
        assertFalse(off.get(5, TimeUnit.SECONDS));
        verify(manager, never()).setTorServiceAvailable(true, 9050);
    }

    @Test
    void dialogCannotStartTorService() {
        when(service.isRunning()).thenReturn(false);
        var error = assertThrows(java.util.concurrent.CompletionException.class,
                () -> DialogOptions.ensureTorAvailable(true, service).join());
        assertTrue(error.getCause().getMessage().contains("Edit → Tor"));
        verify(service, never()).start();
        assertDoesNotThrow(() -> DialogOptions.ensureTorAvailable(false, service).join());
    }
}
