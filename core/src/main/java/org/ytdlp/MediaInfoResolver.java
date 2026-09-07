package org.ytdlp;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Fetch-info fallback used explicitly by the New Media dialog only. */
public class MediaInfoResolver implements AutoCloseable {
    public record Result(URI enteredUrl, URI downloadUrl, YtDlpClient.VideoInfo info,
            MediaRequestContext context) { }

    private final YtDlpClient client;
    private final BrowserMediaProbe probe;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Operation> operations = ConcurrentHashMap.newKeySet();
    private final Thread shutdownHook = new Thread(this::close, "media-info-shutdown");
    private boolean closed;

    public MediaInfoResolver(YtDlpClient client) { this(client, new BrowserMediaProbe()); }
    MediaInfoResolver(YtDlpClient client, BrowserMediaProbe probe) {
        this.client = client;
        this.probe = probe;
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public CompletableFuture<Result> fetch(URI source, YtDlpSettings settings,
            CompletableFuture<?> prerequisite, Consumer<String> status) {
        var operation = new Operation();
        operations.add(operation);
        YtDlpSettings snapshot = (YtDlpSettings) settings.copy();
        try {
            executor.execute(() -> {
                try {
                    operation.await(() -> prerequisite);
                    YtDlpClient.VideoInfo info;
                    try {
                        info = operation.await(() -> client.previewMedia(source.toString(), snapshot));
                        operation.result.complete(new Result(source, source, info, null));
                        return;
                    } catch (Exception failure) {
                        if (!extractionFailed(failure)) { throw failure; }
                    }
                    operation.checkCancelled();
                    status.accept("Probing media…");
                    String cookies = snapshot.getCookieFile() == null && snapshot.getBrowserCookieArgument() != null
                            ? operation.await(() -> client.exportBrowserCookies(source.toString(), snapshot)) : null;
                    List<MediaCandidate> candidates = operation.await(() -> probe.probe(source, snapshot, cookies));
                    if (candidates.isEmpty()) { throw new IllegalStateException("No media source found on this page"); }
                    // Equal evidence for unrelated sources is not enough to silently choose a video.
                    status.accept("Fetching media info…");
                    Exception lastFailure = null;
                    for (int i = 0; i < Math.min(3, candidates.size()); i++) {
                        MediaCandidate candidate = candidates.get(i);
                        if (i + 1 < candidates.size() && candidate.confidence() == candidates.get(i + 1).confidence()) {
                            throw new IllegalStateException("Several media sources found; use a specific media URL");
                        }
                        URI media = org.manager.url.DownloadUrlPolicy.require(candidate.url()).requireWeb().uri();
                        YtDlpSettings resolved = (YtDlpSettings) snapshot.copy();
                        candidate.context().applyTo(resolved);
                        try {
                            info = operation.await(() -> client.previewMedia(media.toString(), resolved));
                            operation.result.complete(new Result(source, media, info, candidate.context()));
                            return;
                        } catch (Exception failure) {
                            if (!extractionFailed(failure)) { throw failure; }
                            lastFailure = failure;
                        }
                    }
                    throw new IllegalStateException("Could not fetch info from the discovered media", lastFailure);
                } catch (Exception failure) {
                    operation.result.completeExceptionally(failure);
                } finally {
                    operations.remove(operation);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            operations.remove(operation);
            operation.result.completeExceptionally(stopped);
        }
        return operation.result;
    }

    static boolean extractionFailed(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException || cause instanceof InterruptedException) { return false; }
            if (cause instanceof YtDlpClient.MediaExtractionException) { return true; }
        }
        return false;
    }

    private static final class Operation {
        final CompletableFuture<Result> result = new CompletableFuture<>();
        final AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();

        Operation() {
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) {
                    CompletableFuture.runAsync(() -> {
                        var pending = active.get();
                        if (pending != null) { pending.cancel(true); }
                    });
                }
            });
        }

        void checkCancelled() {
            if (result.isCancelled() || Thread.currentThread().isInterrupted()) { throw new CancellationException(); }
        }

        <T> T await(Supplier<CompletableFuture<T>> work) throws Exception {
            checkCancelled();
            CompletableFuture<T> pending = work.get();
            active.set(pending);
            if (result.isCancelled()) { pending.cancel(true); }
            try { return pending.get(); }
            finally { active.compareAndSet(pending, null); }
        }
    }

    @Override public synchronized void close() {
        if (closed) { return; }
        closed = true;
        operations.forEach(operation -> operation.result.cancel(true));
        executor.shutdownNow();
        try {
            probe.close();
        } finally {
            client.shutdown();
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException shuttingDown) { }
        }
    }
}
