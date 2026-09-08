package org.tor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.OfflineModeController;

@Timeout(15)
class TorCircuitMonitorTest {
    private static final TorLeakChecker.LeakCheckResult SECURE =
            new TorLeakChecker.LeakCheckResult(true, "Tor circuit verified", "192.0.2.1");
    private static final TorLeakChecker.LeakCheckResult FAILED =
            new TorLeakChecker.LeakCheckResult(false, "Unable to verify the Tor circuit", null);

    @Test
    void schedulesWhileRunningAndAppliesIntervalChangesWithoutImmediateRequests() throws Exception {
        try (Fixture f = new Fixture(false, true)) {
            assertEquals(30, f.settings.getTorCheckIntervalMinutes());
            assertNull(f.clock.tick);
            assertNull(f.monitor.checkNow().get());
            f.running(true);
            assertEquals(30, f.clock.minutes);
            verifyNoInteractions(f.checker);
            var oldTimer = f.clock.timer;
            f.settings.setTorCheckIntervalMinutes(7);
            f.monitor.refresh();
            assertEquals(7, f.clock.minutes);
            verify(oldTimer).cancel(false);
            verifyNoInteractions(f.checker);
            f.clock.tick.run();
            assertTrue(f.started.getLast().get().secure());
            verify(f.checker).performLeakCheck();
            f.running(false);
            verify(f.clock.timer).cancel(false);
            f.clock.tick.run();
            assertEquals(1, f.started.size());
        }
    }

    @Test
    void failureEnablesOfflinePausesEveryActiveTypeAndSuccessDoesNotResume() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            f.response.set(CompletableFuture.completedFuture(FAILED));
            var result = f.automaticCheck().get();
            assertFalse(result.secure());
            assertTrue(result.offlineEnabled());
            assertTrue(f.settings.getBooleanProperty("ui.offline", false));
            for (Download d : f.downloads.subList(0, 4)) {
                assertEquals(Download.Status.PAUSED, d.getStatus());
                assertEquals(Download.PauseReason.OFFLINE, d.getPauseReason());
                verify(f.manager).pauseDownload(d, Download.PauseReason.OFFLINE);
            }
            verify(f.manager, never()).pauseDownload(eq(f.downloads.get(4)), any());
            verify(f.manager, never()).pauseDownload(eq(f.downloads.get(5)), any());
            f.response.set(CompletableFuture.completedFuture(SECURE));
            assertEquals("FR", f.monitor.checkNow().get().countryCode());
            assertTrue(f.settings.getBooleanProperty("ui.offline", false));
            verify(f.manager, never()).resumeDownload(any());
            verify(f.manager, never()).reconsiderQueuedDownloads();
        }
    }

    @Test
    void simultaneousManualAndScheduledChecksShareTheSameRequest() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            var response = new CompletableFuture<TorLeakChecker.LeakCheckResult>();
            f.response.set(response);
            var first = f.monitor.checkNow();
            assertSame(first, f.monitor.checkNow());
            f.clock.tick.run();
            assertEquals(1, f.started.size());
            response.complete(FAILED);
            assertFalse(first.get().secure());
            assertFalse(first.get().offlineEnabled(), "a timer must not promote a manual request");
            verify(f.checker).performLeakCheck();
            assertFalse(f.settings.getBooleanProperty("ui.offline", false));
        }
    }

    @Test
    void stoppingTorDiscardsPendingFailureAndRestartAcceptsFreshChecks() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            var response = new CompletableFuture<TorLeakChecker.LeakCheckResult>();
            f.response.set(response);
            var pending = f.automaticCheck();
            assertTrue(f.requestStarted.await(3, TimeUnit.SECONDS));
            f.monitor.suspend();
            assertNull(pending.get());
            response.complete(FAILED);
            assertFalse(f.settings.getBooleanProperty("ui.offline", false));
            assertNull(f.monitor.checkNow().get());
            f.running(false);
            f.response.set(CompletableFuture.completedFuture(SECURE));
            f.monitor.resume();
            f.running(true);
            var result = f.monitor.checkNow().get();
            assertTrue(f.monitor.isCurrent(result));
            f.running(false);
            assertFalse(f.monitor.isCurrent(result), "a queued UI callback must reject an old verdict");
            assertFalse(f.settings.getBooleanProperty("ui.offline", false));
            verify(f.manager, never()).pauseDownload(any(), any());
        }
    }

    @Test
    void automaticExceptionsFailClosedButUnavailableCountryIsStillASecureVerdict() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            f.country.set(null);
            assertTrue(f.monitor.checkNow().get().secure());
            assertFalse(f.settings.getBooleanProperty("ui.offline", false));
            f.response.set(CompletableFuture.failedFuture(new IllegalStateException("curl unavailable")));
            assertFalse(f.automaticCheck().get().secure());
            assertTrue(f.settings.getBooleanProperty("ui.offline", false));
        }
    }

    @Test
    void closingReleasesListenerTimerAndWorkerWithoutApplyingLateFailure() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            var response = new CompletableFuture<TorLeakChecker.LeakCheckResult>();
            f.response.set(response);
            var pending = f.monitor.checkNow();
            assertTrue(f.requestStarted.await(3, TimeUnit.SECONDS));
            f.monitor.close();
            assertNull(pending.get());
            response.complete(FAILED);
            assertTrue(f.listeners.isEmpty());
            assertTrue(f.clock.awaitTermination(3, TimeUnit.SECONDS));
            assertNull(f.monitor.checkNow().get());
            assertFalse(f.settings.getBooleanProperty("ui.offline", false));
            verify(f.checker, atLeastOnce()).shutdown();
        }
    }

    @Test
    void closingDoesNotHoldMonitorLockWhileWaitingForServiceListenerRemoval() {
        try (Fixture f = new Fixture(true)) {
            doAnswer(call -> {
                // Models a Tor event already holding the service's listener lock.
                CompletableFuture.runAsync(f.monitor::refresh).get(2, TimeUnit.SECONDS);
                f.listeners.remove(call.getArgument(0));
                return null;
            }).when(f.service).removeListener(any());
            assertDoesNotThrow(f.monitor::close);
            assertTrue(f.listeners.isEmpty());
        }
    }

    @Test
    void monitorIsDisabledByDefaultEvenWhenTorIsRunning() throws Exception {
        assertFalse(new GlobalSettings().isTorCircuitMonitorEnabled());
        try (Fixture f = new Fixture(true)) {
            assertNull(f.clock.tick);
            verifyNoInteractions(f.checker);
            assertTrue(f.monitor.checkNow().get().secure(), "manual checks remain available");
            assertNull(f.clock.tick);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualFailuresNeverEnableOffline(boolean monitorEnabled) throws Exception {
        try (Fixture f = new Fixture(true, monitorEnabled)) {
            f.response.set(CompletableFuture.completedFuture(FAILED));
            var result = f.monitor.checkNow().get();
            assertFalse(result.secure());
            assertFalse(result.offlineEnabled());
            f.response.set(CompletableFuture.failedFuture(new IllegalStateException("check unavailable")));
            assertFalse(f.monitor.checkNow().get().offlineEnabled());
            assertFalse(f.settings.getBooleanProperty("ui.offline", false));
            verify(f.manager, never()).pauseDownload(any(), any());
            verify(f.manager, never()).saveState();
        }
    }

    @Test
    void automaticFailureEnablesOfflineEvenWithNoDownloads() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            when(f.manager.getAllDownloads()).thenReturn(List.of());
            f.response.set(CompletableFuture.completedFuture(FAILED));
            var result = f.automaticCheck().get();
            assertTrue(result.offlineEnabled());
            assertTrue(f.settings.getBooleanProperty("ui.offline", false));
            verify(f.manager, never()).pauseDownload(any(), any());
            verify(f.manager).saveState();
        }
    }

    @Test
    void manualRequestJoiningAutomaticCheckRetainsMonitorFailurePolicy() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            var response = new CompletableFuture<TorLeakChecker.LeakCheckResult>();
            f.response.set(response);
            var automatic = f.automaticCheck();
            assertSame(automatic, f.monitor.checkNow());
            response.complete(FAILED);
            assertTrue(automatic.get().offlineEnabled());
            assertTrue(f.settings.getBooleanProperty("ui.offline", false));
        }
    }

    @Test
    void disablingMonitorCancelsAutomaticCheckAndAllowsManualVerification() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            var response = new CompletableFuture<TorLeakChecker.LeakCheckResult>();
            f.response.set(response);
            var pending = f.automaticCheck();
            assertTrue(f.requestStarted.await(3, TimeUnit.SECONDS));
            f.settings.setTorCircuitMonitorEnabled(false);
            f.monitor.refresh();
            verify(f.clock.timer).cancel(false);
            assertNull(pending.get());
            response.complete(FAILED);
            f.clock.tick.run();
            assertEquals(1, f.started.size());
            f.response.set(CompletableFuture.completedFuture(FAILED));
            assertFalse(f.monitor.checkNow().get().offlineEnabled());
            assertFalse(f.settings.getBooleanProperty("ui.offline", false));
            verify(f.manager, never()).pauseDownload(any(), any());
        }
    }

    @Test
    void disablingMonitorDoesNotCancelPendingManualVerification() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            var response = new CompletableFuture<TorLeakChecker.LeakCheckResult>();
            f.response.set(response);
            var pending = f.monitor.checkNow();
            assertTrue(f.requestStarted.await(3, TimeUnit.SECONDS));
            f.settings.setTorCircuitMonitorEnabled(false);
            f.monitor.refresh();
            assertFalse(pending.isDone());
            response.complete(SECURE);
            assertTrue(pending.get().secure());
        }
    }

    private static class Clock extends ScheduledThreadPoolExecutor {
        Runnable tick;
        long minutes;
        ScheduledFuture<?> timer;

        Clock() { super(1); }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                long period, TimeUnit unit) {
            assertEquals(initialDelay, period);
            minutes = unit.toMinutes(period);
            tick = command;
            return timer = mock(ScheduledFuture.class);
        }
    }

    private static class Fixture implements AutoCloseable {
        final GlobalSettings settings = new GlobalSettings() {
            @Override public boolean save() { return true; }
        };
        final DownloadManager manager = mock(DownloadManager.class);
        final TorService service = mock(TorService.class);
        final TorLeakChecker checker = mock(TorLeakChecker.class);
        final AtomicBoolean running = new AtomicBoolean();
        final List<TorService.TorServiceListener> listeners = new CopyOnWriteArrayList<>();
        final AtomicReference<CompletableFuture<TorLeakChecker.LeakCheckResult>> response =
                new AtomicReference<>(CompletableFuture.completedFuture(SECURE));
        final java.util.concurrent.CountDownLatch requestStarted = new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<String> country = new AtomicReference<>("FR");
        final List<CompletableFuture<TorCircuitMonitor.Result>> started = new CopyOnWriteArrayList<>();
        final Clock clock = new Clock();
        final List<Download> downloads = List.of(
                download(Download.Status.STARTING), download(Download.Status.CONNECTING),
                download(Download.Status.DOWNLOADING), download(Download.Status.SEEDING),
                download(Download.Status.PAUSED), download(Download.Status.QUEUED));
        final TorCircuitMonitor monitor;

        Fixture(boolean running) {
            this(running, false);
        }

        Fixture(boolean running, boolean enabled) {
            this.running.set(running);
            settings.setTorCircuitMonitorEnabled(enabled);
            when(service.isRunning()).thenAnswer(ignored -> this.running.get());
            doAnswer(call -> { listeners.add(call.getArgument(0)); return null; })
                    .when(service).addListener(any());
            doAnswer(call -> { listeners.remove(call.getArgument(0)); return null; })
                    .when(service).removeListener(any());
            when(checker.performLeakCheck()).thenAnswer(ignored -> {
                requestStarted.countDown();
                return response.get();
            });
            when(manager.getGlobalSettings()).thenReturn(settings);
            when(manager.getAllDownloads()).thenReturn(downloads);
            when(manager.saveState()).thenReturn(CompletableFuture.completedFuture(null));
            when(manager.pauseDownload(any(), any())).thenAnswer(call -> {
                assertTrue(settings.getBooleanProperty("ui.offline", false),
                        "new transfers must already be blocked when pausing starts");
                Download d = call.getArgument(0);
                d.setPauseReason(call.getArgument(1));
                d.setStatus(Download.Status.PAUSED);
                return CompletableFuture.completedFuture(null);
            });
            monitor = new TorCircuitMonitor(service, settings, new OfflineModeController(manager, Runnable::run),
                    started::add, clock, () -> checker, ip -> country.get());
        }

        void running(boolean value) {
            running.set(value);
            listeners.forEach(listener -> listener.onServiceEvent(value
                    ? TorService.TorServiceEvent.STARTED : TorService.TorServiceEvent.STOPPED));
        }

        CompletableFuture<TorCircuitMonitor.Result> automaticCheck() {
            clock.tick.run();
            return started.getLast();
        }

        @Override public void close() { monitor.close(); }

        private static Download download(Download.Status status) {
            Download d = new Download(URI.create("https://example.com/" + status));
            d.setStatus(status);
            return d;
        }
    }
}
