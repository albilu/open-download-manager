package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadOperations;
import org.manager.download.DownloadSettingsFactory;
import org.manager.util.DescriptorStaging;
import org.manager.util.XdgTrash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.url.DownloadUrlPolicy;

/** Unregistered dialog drafts become history entries only on queue submission. */
final class DownloadSubmission {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadSubmission.class);

    private DownloadSubmission() { }

    /** Candidate previews use the same full-input policy as submission. */
    static List<String> validUrls(List<String> candidates, int maximumUrls) {
        return candidates.stream().limit(maximumUrls)
                .map(DownloadUrlPolicy::parse).flatMap(java.util.Optional::stream)
                .map(source -> source.uri().toString()).toList();
    }

    /** Shared admission for list, sequence and HTML imports; count accepted queue submissions. */
    static int queueUrls(DownloadOperations operations, List<String> urls, Path destination,
            Consumer<Download> configure, int maximumUrls) {
        List<CompletableFuture<Boolean>> admissions = new ArrayList<>();
        for (String url : urls.stream().limit(maximumUrls).toList()) {
            try {
                DownloadUrlPolicy.ValidatedSource source = DownloadUrlPolicy.require(url);
                Download download = operations.createDownload(source.uri(), destination);
                configure.accept(download);
                admissions.add(operations.queueDownload(download).handle((ignored, error) -> {
                    if (error != null) {
                        LOGGER.debug("Import queue admission failed", error);
                    }
                    return error == null;
                }));
            } catch (Exception failure) {
                LOGGER.debug("Skipped an invalid import entry", failure);
            }
        }
        CompletableFuture.allOf(admissions.toArray(CompletableFuture[]::new)).join();
        return (int) admissions.stream().filter(CompletableFuture::join).count();
    }

    static Download draft(DownloadManager manager, URI uri, Path destination, Download.Type type) {
        DownloadUrlPolicy.ValidatedSource source = DownloadUrlPolicy.require(uri);
        Download draft;
        if ("file".equalsIgnoreCase(source.uri().getScheme())) {
            Path file = Path.of(source.uri());
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new IllegalArgumentException("Cannot read descriptor: " + file);
            }
            draft = source.protocol() == Download.Protocol.TORRENT
                    ? Download.fromTorrent(file, destination) : Download.fromMetaLink(file, destination);
        } else {
            draft = Download.fromSource(source);
        }
        if (type != null) {
            draft.setType(type);
        }
        draft.validateSourcesForTransfer();
        draft.setDestination(destination != null ? destination
                : manager.getGlobalSettings().getDefaultDownloadDirectory());
        draft.initSettings(new DownloadSettingsFactory(manager.getGlobalSettings()));
        return draft;
    }

    /** Returns an optional source-disposition warning after successful acceptance. */
    static CompletableFuture<String> submit(DownloadManager manager, Download draft,
            CompletableFuture<Void> readiness, AtomicBoolean closed, Path originalToTrash) {
        CompletableFuture<Void> accepted = readiness.thenComposeAsync(ignored -> {
            synchronized (closed) {
                if (closed.get()) {
                    return CompletableFuture.failedFuture(
                            new java.util.concurrent.CancellationException("Dialog closed"));
                }
                try {
                    if (originalToTrash != null) {
                        draft.setUri(DescriptorStaging.stageManualFile(originalToTrash).toUri());
                    }
                    return manager.queueDownload(draft);
                } catch (Exception failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
        });
        return accepted.handle((ignored, failure) -> {
            if (failure == null) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            CompletableFuture<Void> cleanup = manager.getDownload(draft.getId()) == null
                    ? CompletableFuture.completedFuture(null)
                    : manager.cancelDownload(draft, false);
            return cleanup.<Void>handle((removed, cleanupFailure) -> {
                if (cleanupFailure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    if (originalToTrash != null && !draft.getUri().equals(originalToTrash.toUri())) {
                        DescriptorStaging.deleteIfStaged(Path.of(draft.getUri()));
                    }
                    for (var action : java.util.List.copyOf(manager.getAfterCompletionActions(draft))) {
                        manager.removeAfterCompletionAction(draft, action);
                    }
                }
                throw new CompletionException(failure);
            });
        }).thenCompose(cleanup -> cleanup).thenApplyAsync(ignored -> {
            if (originalToTrash != null) {
                try {
                    XdgTrash.moveToTrash(originalToTrash);
                } catch (Exception failure) {
                    // The queue owns the staged copy now. Keep the accepted
                    // transfer and tell the user the original was retained.
                    return "Download added. Could not move the original descriptor to Trash: "
                            + failure.getMessage();
                }
            }
            return null;
        });
    }
}
