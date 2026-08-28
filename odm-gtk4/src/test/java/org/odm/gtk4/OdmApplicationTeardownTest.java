package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.gnome.glib.MainLoop;
import org.gnome.gtk.Gtk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.download.DownloadManager;
import org.manager.schedule.ScheduleManager;
import org.tor.TorService;

/**
 * Every teardown path (normal close, session-manager fallback, failed
 * startup) must release the services OdmApplication owns on top of the
 * download manager: the DownloadScheduler's terminal executor cleanup and
 * the TorService (executor + temp config). No real tor is launched — the
 * service is only constructed and torn down.
 */
@DisplayName("OdmApplication owned-services teardown")
class OdmApplicationTeardownTest {

    private static MainLoop loop;
    private static java.util.concurrent.ExecutorService loopThread;

    @BeforeAll
    static void initGtk() {
        Gtk.init();
        loop = new MainLoop(null, false);
        loopThread = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-glib-loop");
            t.setDaemon(true);
            return t;
        });
        loopThread.submit(loop::run);
    }

    @AfterAll
    static void tearDown() {
        loop.quit();
        loopThread.shutdownNow();
    }

    private static DownloadManager newStubManager() {
        return (DownloadManager) java.lang.reflect.Proxy.newProxyInstance(
                DownloadManager.class.getClassLoader(),
                new Class<?>[]{DownloadManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getGlobalSettings" -> new org.manager.GlobalSettings();
                    case "getAllDownloads", "getDownloads" -> java.util.List.of();
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

    private static StartupGate.CoreRefs newRefs() {
        DownloadManager stub = newStubManager();
        return new StartupGate.CoreRefs(stub, new TorService("tor"), new ScheduleManager(stub));
    }

    @Test
    @Timeout(60)
    @DisplayName("releaseOwnedServices shuts down the scheduler and the Tor service")
    void releaseOwnedServicesShutsDownSchedulerAndTor() {
        StartupGate.CoreRefs refs = newRefs();
        assertFalse(refs.scheduleManager().getScheduler().isShutdown());
        assertFalse(refs.torService().getShutdownFuture().isDone());

        OdmApplication.releaseOwnedServices(refs);

        assertTrue(refs.scheduleManager().getScheduler().isShutdown(),
                "the download scheduler's executor must be terminated");
        assertTrue(refs.torService().getShutdownFuture().isDone(),
                "the Tor service must be shut down (executor drained, temp config removed)");
    }

    @Test
    @Timeout(60)
    @DisplayName("the shared release helper is single-shot")
    void ownedServicesReleaseIsSingleShot() {
        StartupGate.CoreRefs refs = newRefs();
        OdmApplication.OwnedServicesRelease release = new OdmApplication.OwnedServicesRelease();
        assertFalse(release.isReleased());

        release.capture(refs);
        release.release();
        release.release();

        assertTrue(release.isReleased());
        assertTrue(refs.scheduleManager().getScheduler().isShutdown());
        assertTrue(refs.torService().getShutdownFuture().isDone());
    }

    @Test
    @Timeout(60)
    @DisplayName("a failed startup cleans up the scheduler and the Tor service too")
    void failedStartupCleansUpOwnedServices() {
        StartupGate.CoreRefs refs = newRefs();

        OdmApplication.cleanupFailedStartup(refs, new RuntimeException("window stage failed"));

        assertTrue(refs.scheduleManager().getScheduler().isShutdown(),
                "failed startup must terminate the scheduler");
        assertTrue(refs.torService().getShutdownFuture().isDone(),
                "failed startup must shut down the Tor service");
    }

    @Test
    @Timeout(60)
    @DisplayName("teardown tolerates absent refs (no services were created)")
    void teardownToleratesNullRefs() {
        OdmApplication.releaseOwnedServices(null);
        OdmApplication.cleanupFailedStartup(null, new RuntimeException("core stage failed"));
    }
}
