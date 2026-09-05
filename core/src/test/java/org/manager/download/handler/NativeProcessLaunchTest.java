package org.manager.download.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import org.curl.CurlClient;
import org.proxychains.ProxychainsClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;

/** Gates worker submission while a real child process exercises the launch/stop lifecycle. */
class NativeProcessLaunchTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void delayedHandlerStartCannotReplaceAResumedChild(boolean proxychains) throws Exception {
        ApplicationContext.initialize();
        Path executable = directory.resolve("controlled-tool");
        Files.writeString(executable, "#!/bin/sh\ncase \"$1\" in --version|-h) exit 0;; esac\nexec sleep 30\n");
        executable.toFile().setExecutable(true);
        GlobalSettings settings = new GlobalSettings();
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            // Hold only the initial handler task; later pause/resume work can run.
            ExecutorService executor = org.mockito.Mockito.mock(ExecutorService.class,
                    org.mockito.AdditionalAnswers.delegatesTo(pool));
            var holdNext = new java.util.concurrent.atomic.AtomicBoolean();
            var held = new java.util.concurrent.atomic.AtomicReference<Runnable>();
            org.mockito.Mockito.doAnswer(call -> {
                Runnable task = call.getArgument(0);
                if (holdNext.getAndSet(false)) held.set(task);
                else pool.execute(task);
                return null;
            }).when(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
            DownloadHandler handler = proxychains
                    ? new ProxychainsDownloadHandler(settings, new DownloadSettingsFactory(settings),
                            executor, ApplicationContext.getToolManagerFactory())
                    : new CurlDownloadHandler(settings, new DownloadSettingsFactory(settings),
                            executor, ApplicationContext.getToolManagerFactory());
            var field = handler.getClass().getDeclaredField(proxychains ? "proxychainsClient" : "curlClient");
            field.setAccessible(true);
            Object previous = field.get(handler);
            previous.getClass().getMethod("shutdown").invoke(previous);
            field.set(handler, proxychains ? new ProxychainsClient(executable.toString(), null)
                    : new CurlClient(executable.toString()));
            var listener = org.mockito.Mockito.mock(org.manager.download.DownloadListener.class);
            handler.addDownloadListener(listener);
            handler.initialize().join();
            Download download = new Download(URI.create("http://launch.odm.invalid/payload.bin"));
            download.setDestination(directory);
            download.setType(proxychains ? Download.Type.PROXYCHAINS : Download.Type.CURL);
            try {
                holdNext.set(true);
                CompletableFuture<String> initial = handler.startDownload(download);
                assertNotNull(held.get());
                handler.pauseDownload(download).get(5, TimeUnit.SECONDS);
                assertTrue(initial.isCompletedExceptionally());
                handler.resumeDownload(download).get(5, TimeUnit.SECONDS);
                pool.submit(held.get()).get(5, TimeUnit.SECONDS);
                org.mockito.Mockito.verify(listener, org.mockito.Mockito.never()).onDownloadStart(download);
                org.mockito.Mockito.verify(listener).onDownloadResume(download);
                assertEquals(Download.Status.DOWNLOADING, download.getStatus());
                if (proxychains) assertTrue(((ProxychainsDownloadHandler) handler).isActive(download.getId()));
            } finally {
                handler.shutdown().join();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startAndResumeFuturesWaitForTheChildLaunch(boolean proxychains) throws Exception {
        ApplicationContext.initialize();
        Path executable = directory.resolve("controlled-tool");
        Files.writeString(executable, "#!/bin/sh\ncase \"$1\" in --version|-h) exit 0;; esac\nexec sleep 30\n");
        executable.toFile().setExecutable(true);
        GlobalSettings settings = new GlobalSettings();
        try (ExecutorService handlerExecutor = Executors.newCachedThreadPool();
                ExecutorService worker = Executors.newSingleThreadExecutor()) {
            DownloadHandler handler = proxychains
                    ? new ProxychainsDownloadHandler(settings, new DownloadSettingsFactory(settings),
                            handlerExecutor, ApplicationContext.getToolManagerFactory())
                    : new CurlDownloadHandler(settings, new DownloadSettingsFactory(settings),
                            handlerExecutor, ApplicationContext.getToolManagerFactory());
            String clientField = proxychains ? "proxychainsClient" : "curlClient";
            var field = handler.getClass().getDeclaredField(clientField);
            field.setAccessible(true);
            Object previous = field.get(handler);
            previous.getClass().getMethod("shutdown").invoke(previous);
            Object client = proxychains ? new ProxychainsClient(executable.toString(), null)
                    : new CurlClient(executable.toString());
            var workerField = client.getClass().getDeclaredField("executorService");
            workerField.setAccessible(true);
            ((ExecutorService) workerField.get(client)).shutdownNow();
            workerField.set(client, worker);
            field.set(handler, client);
            handler.initialize().join();
            Download download = new Download(URI.create("http://launch.odm.invalid/payload.bin"));
            download.setDestination(directory);
            download.setType(proxychains ? Download.Type.PROXYCHAINS : Download.Type.CURL);
            try {
                for (boolean resume : new boolean[] {false, true}) {
                    CountDownLatch entered = new CountDownLatch(1);
                    CountDownLatch release = new CountDownLatch(1);
                    worker.submit(() -> {
                        entered.countDown();
                        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    });
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    CompletableFuture<?> future = resume ? handler.resumeDownload(download) : handler.startDownload(download);
                    try {
                        await().atMost(Duration.ofSeconds(5)).until(() -> download.getStatus() == Download.Status.CONNECTING);
                        assertFalse(future.isDone(), "the manager must not see a completed launch before ProcessBuilder.start");
                    } finally {
                        release.countDown();
                    }
                    future.get(5, TimeUnit.SECONDS);
                    assertEquals(Download.Status.DOWNLOADING, download.getStatus());
                    handler.pauseDownload(download).get(10, TimeUnit.SECONDS);
                    assertEquals(Download.Status.PAUSED, download.getStatus());
                }
                CountDownLatch release = new CountDownLatch(1);
                worker.submit(() -> {
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                CompletableFuture<Void> pending = handler.resumeDownload(download);
                try {
                    await().atMost(Duration.ofSeconds(5)).until(() -> download.getStatus() == Download.Status.CONNECTING);
                    handler.pauseDownload(download).get(5, TimeUnit.SECONDS);
                    assertTrue(pending.isCompletedExceptionally(), "pause must settle even a worker that has not run");
                } finally {
                    release.countDown();
                }
                worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
                assertEquals(Download.Status.PAUSED, download.getStatus());
                var registryField = client.getClass().getDeclaredField("activeProcesses");
                registryField.setAccessible(true);
                assertTrue(((org.manager.tools.ExternalProcessRegistry) registryField.get(client)).isEmpty());
            } finally {
                handler.shutdown().join();
            }
        }
    }
}
