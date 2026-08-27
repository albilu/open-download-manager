package org.odm.gtk4;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.gnome.glib.MainLoop;
import org.gnome.gtk.Gtk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.download.DownloadManager;
import org.manager.schedule.ScheduleManager;
import org.tor.TorService;

/**
 * Single-flight contract for the activation gate: rapid repeated activation
 * while initialization is in flight must reuse one pipeline and one window,
 * waiting activations must present that same window, failure must clean up
 * partially created resources and permit a later retry, and shutdown must
 * prevent a late background stage from publishing a window. The gate is
 * driven with fake core pipelines against a real GLib main loop (requires a
 * display, as the Docker test env provides via Xvfb); published windows are
 * real MainWindows over stubbed core collaborators (the WindowSmokeTest
 * pattern), so the GTK wiring stays real.
 */
@DisplayName("StartupGate single-flight activation")
class StartupGateTest {

    private static MainLoop loop;
    private static ExecutorService loopThread;

    private final AtomicInteger pipelineCalls = new AtomicInteger();
    private final List<CompletableFuture<StartupGate.CoreRefs>> pipelines = new CopyOnWriteArrayList<>();
    private final List<CountingMainWindow> windows = new CopyOnWriteArrayList<>();
    private final List<StartShutdownDialog> openDialogs = new CopyOnWriteArrayList<>();
    private final AtomicInteger cleanupCalls = new AtomicInteger();
    private final AtomicInteger failureNotices = new AtomicInteger();
    private StartupGate gate;

    @BeforeAll
    static void initGtk() {
        Gtk.init();
        loop = new MainLoop(null, false);
        loopThread = Executors.newSingleThreadExecutor(r -> {
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

    /** Dialog factory used unless a test overrides it before rebuilding the gate. */
    private volatile java.util.function.Supplier<StartShutdownDialog> dialogFactory =
            () -> null; // replaced in setUpGate
    /** Failure cleanup used unless a test overrides it before rebuilding the gate. */
    private volatile StartupGate.FailureCleanup cleanup = (refs, error) -> cleanupCalls.incrementAndGet();

    /** Builds a gate from the current fixture state; tests may override first. */
    private StartupGate buildGate() {
        return new StartupGate(
                () -> {
                    StartShutdownDialog dialog = dialogFactory.get();
                    openDialogs.add(dialog);
                    return dialog;
                },
                progress -> {
                    pipelineCalls.incrementAndGet();
                    CompletableFuture<StartupGate.CoreRefs> core = new CompletableFuture<>();
                    pipelines.add(core);
                    return core;
                },
                (progress, refs) -> {
                    CountingMainWindow window = new CountingMainWindow(
                            refs.manager(), refs.torService(), refs.scheduleManager());
                    windows.add(window);
                    window.present();
                    progress.close();
                    openDialogs.remove(progress);
                    return window;
                },
                cleanup,
                (progress, error) -> failureNotices.incrementAndGet());
    }

    @BeforeEach
    void setUpGate() {
        dialogFactory = () -> {
            StartShutdownDialog dialog = new StartShutdownDialog((org.gnome.gtk.Window) null);
            return dialog;
        };
        gate = buildGate();
    }

    @AfterEach
    void cleanupWidgets() throws Exception {
        onLoop(() -> {
            windows.forEach(window -> {
                try {
                    window.dispose();
                } catch (Throwable ignored) {
                    // already destroyed
                }
            });
            openDialogs.forEach(dialog -> {
                try {
                    dialog.close();
                } catch (Throwable ignored) {
                    // already destroyed
                }
            });
        });
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

    /** Waits until the condition holds, failing with the given message. */
    private static void awaitTrue(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timeout: " + message);
            }
            Thread.sleep(50);
        }
    }

    private static StartupGate.CoreRefs newRefs() {
        DownloadManager stub = newStubManager();
        return new StartupGate.CoreRefs(stub, new TorService("tor"), new ScheduleManager(stub));
    }

    private static DownloadManager newStubManager() {
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

    /** A real MainWindow counting present() calls (core collaborators stubbed). */
    private static final class CountingMainWindow extends MainWindow {
        final AtomicInteger presents = new AtomicInteger();

        CountingMainWindow(DownloadManager manager, TorService torService, ScheduleManager scheduleManager) {
            super(null, manager, torService, scheduleManager);
        }

        @Override
        public void present() {
            presents.incrementAndGet();
            super.present();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("rapid repeated activation starts one pipeline and creates one window")
    void rapidActivationStartsSinglePipeline() throws Exception {
        onLoop(() -> {
            gate.activate();
            gate.activate();
            gate.activate();
        });
        assertEquals(1, pipelineCalls.get(),
                "repeated activation before initialization completes must not start a second pipeline");

        pipelines.get(0).complete(newRefs());
        awaitTrue(() -> windows.size() == 1, "the single pipeline must publish one window");

        assertEquals(1, pipelineCalls.get());
        assertEquals(1, windows.size(), "exactly one window must be created");
    }

    @Test
    @Timeout(60)
    @DisplayName("waiting activations present the same window; post-completion activation re-presents it")
    void waitingActivationsPresentSameWindow() throws Exception {
        onLoop(() -> {
            gate.activate(); // owner: starts the pipeline
            gate.activate(); // waiter: initialization still in progress
            gate.activate(); // waiter
        });
        assertEquals(1, pipelineCalls.get(),
                "activations while initialization is in progress must reuse the running pipeline");

        pipelines.get(0).complete(newRefs());

        awaitTrue(() -> windows.size() == 1, "the pipeline must publish one window");
        CountingMainWindow window = windows.get(0);
        awaitTrue(() -> window.presents.get() >= 3,
                "owner and waiting activations must all present the same window");
        assertEquals(1, windows.size(), "all activations must share one window instance");

        onLoop(gate::activate); // activation after completion: present existing
        awaitTrue(() -> window.presents.get() == 4,
                "activation after completion must present the existing window");
        assertEquals(1, pipelineCalls.get(), "activation after completion must not start a new pipeline");
        assertEquals(1, windows.size());
    }

    @Test
    @Timeout(60)
    @DisplayName("failure cleans partial resources and permits one later retry")
    void failureCleansUpAndPermitsRetry() throws Exception {
        onLoop(gate::activate);
        assertEquals(1, pipelineCalls.get());

        pipelines.get(0).completeExceptionally(new RuntimeException("core exploded"));

        awaitTrue(() -> cleanupCalls.get() == 1, "failure must clean up partially created services");
        awaitTrue(() -> failureNotices.get() == 1, "failure must be surfaced to the user");
        assertEquals(0, windows.size(), "failed startup must not create a window");

        onLoop(gate::activate); // the later retry
        awaitTrue(() -> pipelineCalls.get() == 2, "a later activation must be able to retry startup");

        pipelines.get(1).complete(newRefs());
        awaitTrue(() -> windows.size() == 1, "the retry must publish one window");

        assertEquals(1, cleanupCalls.get(), "successful retry must not run another cleanup");
        assertEquals(1, failureNotices.get());
        assertEquals(1, windows.size());
    }

    @Test
    @Timeout(60)
    @DisplayName("shutdown during startup prevents window publication and cleans the core")
    void shutdownDuringStartupDiscardsWindow() throws Exception {
        onLoop(gate::activate);
        onLoop(gate::beginShutdown);

        pipelines.get(0).complete(newRefs()); // background stage finishes after shutdown began

        awaitTrue(() -> cleanupCalls.get() == 1,
                "the started core must be cleaned up instead of published");
        assertEquals(0, windows.size(), "no window may be published after shutdown begins");
    }

    @Test
    @Timeout(60)
    @DisplayName("dialog construction failure settles the slot instead of zombie-ing it")
    void dialogFailureSettlesSlotAndPermitsRetry() throws Exception {
        java.util.concurrent.atomic.AtomicInteger factoryCalls = new AtomicInteger();
        dialogFactory = () -> {
            if (factoryCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("start-shutdown.ui regression");
            }
            return new StartShutdownDialog((org.gnome.gtk.Window) null);
        };
        gate = buildGate();

        onLoop(() -> {
            try {
                gate.activate(); // today's bug: escapes and leaves an unsettled future
            } catch (Throwable toleratedForRed) {
                // the gate must settle, not throw past activate()
            }
        });

        awaitTrue(() -> cleanupCalls.get() == 1,
                "dialog failure must still run the failure cleanup");
        awaitTrue(() -> failureNotices.get() == 1,
                "dialog failure must still schedule the exit strategy (null progress)");
        assertEquals(0, windows.size());

        onLoop(gate::activate); // retry with a working dialog
        awaitTrue(() -> pipelineCalls.get() == 1,
                "the slot must be cleared so a later activation is not stuck on a dead future");
        pipelines.get(0).complete(newRefs());
        awaitTrue(() -> windows.size() == 1, "the retry must publish one window");
    }

    @Test
    @Timeout(60)
    @DisplayName("a retry pipeline waits for the still-running failure cleanup")
    void retryWaitsForPendingCleanup() throws Exception {
        java.util.concurrent.CountDownLatch releaseCleanup = new java.util.concurrent.CountDownLatch(1);
        cleanup = (refs, error) -> {
            cleanupCalls.incrementAndGet();
            try {
                releaseCleanup.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        gate = buildGate();

        onLoop(gate::activate);
        pipelines.get(0).completeExceptionally(new RuntimeException("boom"));
        awaitTrue(() -> cleanupCalls.get() == 1, "failure cleanup must be running (blocked)");

        onLoop(gate::activate); // retry: pipeline must not race the cleanup
        Thread.sleep(400); // settle; without serialization the count jumps immediately
        assertEquals(1, pipelineCalls.get(),
                "the retry core pipeline must wait for the pending failure cleanup");

        releaseCleanup.countDown();
        awaitTrue(() -> pipelineCalls.get() == 2,
                "the retry core pipeline must start once the cleanup completes");
    }

    @Test
    @Timeout(60)
    @DisplayName("activation after shutdown is refused while no window exists")
    void activateAfterShutdownIsRefused() throws Exception {
        onLoop(gate::beginShutdown);
        onLoop(gate::activate);
        assertEquals(0, pipelineCalls.get(), "no pipeline may start after shutdown began");
        assertEquals(0, windows.size());
    }

    @Test
    @Timeout(60)
    @DisplayName("a waiting activation stands down when the startup fails")
    void waitingActivationStandsDownOnFailure() throws Exception {
        onLoop(() -> {
            gate.activate(); // owner
            gate.activate(); // waiter: must observe the failure and do nothing
        });
        pipelines.get(0).completeExceptionally(new RuntimeException("boom"));

        awaitTrue(() -> cleanupCalls.get() == 1, "the owner must run the failure cleanup");
        awaitTrue(() -> failureNotices.get() == 1,
                "exactly one failure notice: waiting activations stand down silently");
        assertEquals(0, windows.size(), "a failed startup must publish nothing");
        assertEquals(1, pipelineCalls.get());
    }
}
