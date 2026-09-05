package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.gnome.glib.MainContext;
import org.gnome.gtk.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.DownloadManager;

class ChecksumPreviewRoutingTest {
    @TempDir Path directory;
    @BeforeAll static void initGtk() { Gtk.init(); }

    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }

    private static void pumpUntil(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            MainContext context = MainContext.default_();
            while (context.pending()) context.iteration(false);
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "GTK checksum operation did not settle");
    }

    private NewDownloadDialog dialog() {
        GlobalSettings settings = new GlobalSettings().setGlobalProxyEnabled(false);
        settings.setDefaultDownloadDirectory(directory);
        DownloadManager manager = (DownloadManager) Proxy.newProxyInstance(DownloadManager.class.getClassLoader(),
                new Class<?>[] { DownloadManager.class }, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getGlobalSettings" -> settings;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new NewDownloadDialog(null, manager, () -> { });
    }

    @Test
    void untouchedDialogDefaultsPreserveGlobalProxyInheritance() throws Exception {
        GlobalSettings settings = new GlobalSettings().setGlobalProxyEnabled(true)
                .setGlobalProxyAddress("http://127.0.0.1:8080");
        NetworkOptionsPane pane = new NetworkOptionsPane(settings,
                org.manager.download.Download.Type.ARIA2, org.manager.download.Download.Protocol.HTTP);
        var download = new org.manager.download.Download(java.net.URI.create("http://file.odm.invalid/a.bin"));
        download.initSettings(new org.manager.download.DownloadSettingsFactory(settings));
        pane.applyTo(download);
        assertTrue(download.getSettings().isProxyInherited());
        field(pane, "proxyPort", SpinButton.class).setValue(8081);
        pane.applyTo(download);
        assertFalse(download.getSettings().isProxyInherited());
        assertEquals("http://127.0.0.1:8081", download.getProxyAddress());
    }

    @Test
    void previewUsesDialogProxyAndRouteChangeInvalidatesPendingResult() throws Exception {
        AtomicInteger directRequests = new AtomicInteger();
        CountDownLatch firstProxyRequest = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        String oldDigest = "a".repeat(64);
        String newDigest = "b".repeat(64);
        try (var servers = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpServer direct = server(servers, directRequests, "c".repeat(64), null, null);
            HttpServer first = server(servers, new AtomicInteger(), oldDigest, firstProxyRequest, releaseFirst);
            HttpServer second = server(servers, new AtomicInteger(), newDigest, null, null);
            NewDownloadDialog dialog = dialog();
            Window window = field(dialog, "dialog", Window.class);
            try {
                NetworkOptionsPane network = field(dialog, "networkOptions", NetworkOptionsPane.class);
                field(network, "proxyType", DropDown.class).setSelected(1);
                field(network, "proxyHost", Entry.class).setText("127.0.0.1");
                field(network, "proxyPort", SpinButton.class).setValue(first.getAddress().getPort());
                field(dialog, "urlEntry", Entry.class).setText("http://127.0.0.1:"
                        + direct.getAddress().getPort() + "/payload.bin");
                pumpUntil(() -> firstProxyRequest.getCount() == 0);
                field(network, "proxyPort", SpinButton.class).setValue(second.getAddress().getPort());
                Label checksum = field(dialog, "checksumLabel", Label.class);
                pumpUntil(() -> checksum.getLabel().contains(newDigest));
                releaseFirst.countDown();
                // Wait for all tracked probes to finish before checking the stale response.
                pumpUntil(() -> fieldUnchecked(dialog, "activity", SpinnerActivity.class).activeCount() == 0);
                assertTrue(checksum.getLabel().contains(newDigest));
                assertEquals(0, directRequests.get(), "no sibling request may bypass the dialog's selected proxy");
            } finally {
                releaseFirst.countDown();
                window.close();
                direct.stop(0);
                first.stop(0);
                second.stop(0);
            }
        }
    }

    @Test
    void incompleteProxyAndUnavailableTorDoNotFallBackToDirectPreview() throws Exception {
        AtomicInteger directRequests = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpServer direct = server(workers, directRequests, "c".repeat(64), null, null);
            NewDownloadDialog dialog = dialog();
            Window window = field(dialog, "dialog", Window.class);
            try {
                NetworkOptionsPane network = field(dialog, "networkOptions", NetworkOptionsPane.class);
                field(network, "proxyType", DropDown.class).setSelected(1);
                field(dialog, "urlEntry", Entry.class).setText("http://127.0.0.1:"
                        + direct.getAddress().getPort() + "/payload.bin");
                assertThrows(IllegalArgumentException.class, network::selectedProxyAddress);
                field(network, "tor", Switch.class).setActive(true);
                // No Tor service was provided: its readiness failure must finish without a request.
                pumpUntil(() -> fieldUnchecked(dialog, "activity", SpinnerActivity.class).activeCount() == 0);
                assertEquals(0, directRequests.get());
                assertFalse(field(dialog, "verifyChecksumCheck", CheckButton.class).getVisible());
            } finally {
                window.close();
                direct.stop(0);
            }
        }
    }

    private static <T> T fieldUnchecked(Object owner, String name, Class<T> type) {
        try { return field(owner, name, type); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private static HttpServer server(java.util.concurrent.Executor executor, AtomicInteger requests,
            String checksum, CountDownLatch entered, CountDownLatch release) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            if (entered != null) entered.countDown();
            try {
                if (release != null) release.await(8, TimeUnit.SECONDS);
                byte[] body = (checksum + "  payload.bin\n").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }
}
