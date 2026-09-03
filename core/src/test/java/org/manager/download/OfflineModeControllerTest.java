package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

class OfflineModeControllerTest {

    @Test
    void onlyActuallyRunningStatusesBelongToOfflineMode() {
        EnumSet<Download.Status> active = EnumSet.of(
                Download.Status.STARTING,
                Download.Status.CONNECTING,
                Download.Status.DOWNLOADING,
                Download.Status.SEEDING);

        for (Download.Status status : Download.Status.values()) {
            assertEquals(active.contains(status),
                    OfflineModeController.isActivelyTransferring(download(status.name(), status)),
                    status.name());
        }
    }

    @Test
    void leavingOfflineResumesOnlyDownloadsItPaused() {
        Download running = download("running", Download.Status.DOWNLOADING);
        Download userPaused = download("user-paused", Download.Status.PAUSED);
        Download queued = download("queued", Download.Status.QUEUED);
        ManagerStub stub = new ManagerStub(List.of(running, userPaused, queued));
        stub.pause = download -> {
            download.setStatus(Download.Status.PAUSED);
            return CompletableFuture.completedFuture(null);
        };
        stub.resume = download -> {
            download.setStatus(Download.Status.DOWNLOADING);
            return CompletableFuture.completedFuture(null);
        };
        OfflineModeController controller = controller(stub);

        controller.setOffline(true).join();
        assertEquals(Download.Status.PAUSED, running.getStatus());
        assertEquals(Download.Status.PAUSED, userPaused.getStatus());
        assertEquals(List.of(running), stub.pauseCalls);

        controller.setOffline(false).join();
        assertEquals(Download.Status.DOWNLOADING, running.getStatus());
        assertEquals(Download.Status.PAUSED, userPaused.getStatus());
        assertEquals(Download.Status.QUEUED, queued.getStatus());
        assertEquals(List.of(running), stub.resumeCalls);
    }

    @Test
    void failedOfflinePauseDoesNotClaimResumeOwnership() {
        Download running = download("failure", Download.Status.DOWNLOADING);
        ManagerStub stub = new ManagerStub(List.of(running));
        stub.pause = ignored -> CompletableFuture.failedFuture(
                new IllegalStateException("pause failed"));
        OfflineModeController controller = controller(stub);

        controller.setOffline(true).join();
        controller.setOffline(false).join();

        assertTrue(stub.resumeCalls.isEmpty());
        assertEquals(Download.Status.DOWNLOADING, running.getStatus());
    }

    @Test
    void manualHoldAddedWhileOfflinePreventsAutomaticResume() {
        Download running = download("held", Download.Status.DOWNLOADING);
        ManagerStub stub = new ManagerStub(List.of(running));
        stub.pause = download -> {
            download.setStatus(Download.Status.PAUSED);
            return CompletableFuture.completedFuture(null);
        };
        OfflineModeController controller = controller(stub);

        controller.setOffline(true).join();
        running.setManualStartRequired(true);
        controller.setOffline(false).join();

        assertTrue(stub.resumeCalls.isEmpty());
        assertEquals(Download.Status.PAUSED, running.getStatus());
    }

    @Test
    void rapidOnlineTransitionWaitsForOfflinePauseToFinish() {
        Download running = download("rapid", Download.Status.DOWNLOADING);
        ManagerStub stub = new ManagerStub(List.of(running));
        CompletableFuture<Void> pauseGate = new CompletableFuture<>();
        stub.pause = ignored -> pauseGate.thenRun(
                () -> running.setStatus(Download.Status.PAUSED));
        stub.resume = download -> {
            download.setStatus(Download.Status.DOWNLOADING);
            return CompletableFuture.completedFuture(null);
        };
        OfflineModeController controller = controller(stub);

        CompletableFuture<Void> entering = controller.setOffline(true);
        CompletableFuture<Void> leaving = controller.setOffline(false);

        assertFalse(entering.isDone());
        assertFalse(leaving.isDone());
        assertTrue(stub.settings.getBooleanProperty("ui.offline", false));

        pauseGate.complete(null);
        leaving.join();

        assertTrue(entering.isDone());
        assertFalse(stub.settings.getBooleanProperty("ui.offline", true));
        assertEquals(List.of(running), stub.resumeCalls);
        assertEquals(Download.Status.DOWNLOADING, running.getStatus());
    }

    private static OfflineModeController controller(ManagerStub stub) {
        return new OfflineModeController(stub.proxy(), Runnable::run);
    }

    private static Download download(String name, Download.Status status) {
        Download download = new Download(URI.create("https://example.test/" + name));
        download.setName(name);
        download.setStatus(status);
        return download;
    }

    private static final class TestSettings extends GlobalSettings {
        @Override
        public boolean save() {
            return true;
        }
    }

    private static final class ManagerStub implements InvocationHandler {
        private final List<Download> downloads;
        private final TestSettings settings = new TestSettings();
        private final List<Download> pauseCalls = new ArrayList<>();
        private final List<Download> resumeCalls = new ArrayList<>();
        private Function<Download, CompletableFuture<Void>> pause = ignored ->
                CompletableFuture.completedFuture(null);
        private Function<Download, CompletableFuture<Void>> resume = ignored ->
                CompletableFuture.completedFuture(null);

        ManagerStub(List<Download> downloads) {
            this.downloads = List.copyOf(downloads);
        }

        DownloadManager proxy() {
            return (DownloadManager) Proxy.newProxyInstance(
                    DownloadManager.class.getClassLoader(),
                    new Class<?>[]{DownloadManager.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "getGlobalSettings" -> settings;
                case "getAllDownloads" -> downloads;
                case "getDownload" -> downloads.stream()
                        .filter(download -> download.getId().equals(arguments[0]))
                        .findFirst().orElse(null);
                case "pauseDownload" -> {
                    Download download = (Download) arguments[0];
                    pauseCalls.add(download);
                    yield pause.apply(download);
                }
                case "resumeDownload" -> {
                    Download download = (Download) arguments[0];
                    resumeCalls.add(download);
                    yield resume.apply(download);
                }
                case "toString" -> "OfflineModeControllerTest.ManagerStub";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
