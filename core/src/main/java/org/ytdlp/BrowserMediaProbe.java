package org.ytdlp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.manager.tools.ExternalProcessRegistry;

/** On-demand headless probing; no browser is started at application/dialog startup. */
public class BrowserMediaProbe implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExternalProcessRegistry processes = new ExternalProcessRegistry("media-probe");
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.Set<CompletableFuture<?>> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final int observationMillis;
    private final Thread shutdownHook = new Thread(this::close, "media-probe-shutdown");

    public BrowserMediaProbe() { this(20_000); }
    BrowserMediaProbe(int observationMillis) {
        this.observationMillis = observationMillis;
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public CompletableFuture<List<MediaCandidate>> probe(URI source, YtDlpSettings settings,
            String browserCookies) {
        if (closed.get()) { return CompletableFuture.failedFuture(new IllegalStateException("Media probe is closed")); }
        String key = UUID.randomUUID().toString();
        var launch = processes.reserve(key);
        CompletableFuture<List<MediaCandidate>> result = new CompletableFuture<>();
        pending.add(result);
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                CompletableFuture.runAsync(() -> stop(key));
            }
        });
        try {
            executor.execute(() -> {
                ExternalProcessRegistry.Registration registration = null;
                try {
                    if (closed.get() || result.isCancelled()) { throw new java.util.concurrent.CancellationException(); }
                    String cookies = browserCookies;
                    if (cookies == null && settings.getCookieFile() != null) {
                        Path cookieFile = Path.of(settings.getCookieFile());
                        if (Files.size(cookieFile) > 1024 * 1024) { throw new IOException("Cookie file is too large"); }
                        cookies = Files.readString(cookieFile);
                    }
                    var input = new MediaProbeWorker.Input(source.toString(),
                            org.manager.tools.NetworkProcessPolicy.selectedProxy(settings),
                            settings.getUserAgent(), settings.getReferer(), settings.getCookieHeader(),
                            cookies, observationMillis);
                    var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-Xmx256m", "-cp", System.getProperty("java.class.path"), MediaProbeWorker.class.getName());
                    org.manager.tools.NetworkProcessPolicy.prepare(builder);
                    // A probe must not download browsers (or inherit an arbitrary auto-install route).
                    builder.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
                    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                    registration = launch.start(builder);
                    Process process = registration.process();
                    byte[] message = mapper.writeValueAsBytes(input);
                    var control = new java.io.DataOutputStream(process.getOutputStream());
                    control.writeInt(message.length);
                    control.write(message);
                    control.flush();
                    // Keep stdin open: EOF requests cooperative browser cleanup on cancellation.
                    var read = CompletableFuture.supplyAsync(() -> {
                        try {
                            byte[] output = process.getInputStream().readNBytes(4 * 1024 * 1024 + 1);
                            if (output.length > 4 * 1024 * 1024) {
                                stop(key);
                                throw new IOException("Media probe output is too large");
                            }
                            return new String(output, StandardCharsets.UTF_8);
                        } catch (IOException failure) { throw new CompletionException(failure); }
                    }, executor);
                    if (!process.waitFor(40, TimeUnit.SECONDS)) {
                        throw new IOException("Media probing timed out");
                    }
                    String output = read.get(5, TimeUnit.SECONDS).lines()
                            .filter(line -> line.startsWith(MediaProbeWorker.OUTPUT_PREFIX))
                            .findFirst().orElseThrow(() -> new IOException("Could not start the media probe"));
                    var response = mapper.readValue(output.substring(MediaProbeWorker.OUTPUT_PREFIX.length()),
                            MediaProbeWorker.Output.class);
                    if (response.error() != null) { throw new IOException(response.error()); }
                    result.complete(response.candidates());
                } catch (Exception failure) {
                    result.completeExceptionally(failure);
                } finally {
                    if (registration != null) { stop(key); }
                    else { launch.unregister(); }
                    pending.remove(result);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            launch.unregister();
            pending.remove(result);
            result.completeExceptionally(stopped);
        }
        return result;
    }

    int activeProcesses() { return processes.size(); }

    private void stop(String key) {
        Process process = processes.get(key);
        if (process != null && process.isAlive()) {
            try {
                process.getOutputStream().close();
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (IOException ignored) {
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        processes.terminate(key, 1);
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        pending.forEach(result -> result.cancel(true));
        executor.shutdown();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally {
            processes.terminateAll(1);
            executor.shutdownNow();
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException shuttingDown) { }
        }
    }
}
