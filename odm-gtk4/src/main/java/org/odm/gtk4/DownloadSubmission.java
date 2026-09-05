package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadSettingsFactory;
import org.manager.util.DescriptorStaging;
import org.manager.util.XdgTrash;

/** Unregistered dialog drafts become history entries only on queue submission. */
final class DownloadSubmission {
    private DownloadSubmission() { }

    static Download draft(DownloadManager manager, URI uri, Path destination, Download.Type type) {
        URI source = org.manager.clipboard.UrlDetector.requireValidDownloadUri(uri);
        Download draft;
        if ("file".equalsIgnoreCase(source.getScheme())) {
            Path file = Path.of(source);
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new IllegalArgumentException("Cannot read descriptor: " + file);
            }
            draft = Download.Protocol.fromUri(source) == Download.Protocol.TORRENT
                    ? Download.fromTorrent(file, destination) : Download.fromMetaLink(file, destination);
        } else {
            draft = new Download(source);
        }
        if (type != null) {
            draft.setType(type);
        }
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
