package org.manager.download;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import org.manager.util.DescriptorStaging;
import org.manager.util.XdgTrash;

/** Creates local torrent/Metalink downloads and applies source disposition. */
public final class DescriptorImport {

    private DescriptorImport() {
    }

    /**
     * Creates a local descriptor download. When {@code trashOriginal} is set,
     * the download is created against an ODM-owned staged copy first and only
     * then is the original moved to the Linux/XDG Trash. A failed create or
     * trash operation rolls the newly created item and staged copy back.
     *
     * @param downloads download operations used to create and roll back work
     * @param source selected {@code .torrent}, {@code .metalink}, or
     *            {@code .meta4} file
     * @param destination payload destination
     * @param trashOriginal whether to move the selected source to XDG Trash
     * @return the created download
     */
    public static Download create(DownloadOperations downloads, Path source, Path destination,
            boolean trashOriginal) {
        Objects.requireNonNull(downloads, "downloads");
        Objects.requireNonNull(source, "source");

        Download.Protocol protocol = Download.Protocol.fromPath(source);
        if (protocol != Download.Protocol.TORRENT
                && protocol != Download.Protocol.METALINK) {
            throw new IllegalArgumentException("Unsupported descriptor file: " + source);
        }

        if (!trashOriginal) {
            return createDownload(downloads, source, destination, protocol);
        }

        Path staged;
        try {
            staged = DescriptorStaging.stageManualFile(source);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Could not stage the descriptor before moving it to Trash: " + e.getMessage(), e);
        }

        Download download = null;
        try {
            download = createDownload(downloads, staged, destination, protocol);
            // A staged UUID is an implementation detail, not a user-facing name.
            download.setName(source.getFileName().toString());
            XdgTrash.moveToTrash(source);
            return download;
        } catch (IOException | RuntimeException failure) {
            rollback(downloads, download, staged, failure);
            if (failure instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalArgumentException(
                    "Could not move the descriptor to Trash: " + failure.getMessage(), failure);
        }
    }

    private static Download createDownload(DownloadOperations downloads, Path source,
            Path destination, Download.Protocol protocol) {
        if (protocol == Download.Protocol.METALINK) {
            return downloads.createMetaLinkDownload(source.toUri(), destination);
        }
        return downloads.createTorrentDownload(source, destination);
    }

    private static void rollback(DownloadOperations downloads, Download download,
            Path staged, Throwable failure) {
        if (download != null) {
            try {
                downloads.cancelDownload(download, false).join();
            } catch (RuntimeException rollbackFailure) {
                Throwable cause = rollbackFailure instanceof CompletionException
                        && rollbackFailure.getCause() != null
                                ? rollbackFailure.getCause() : rollbackFailure;
                failure.addSuppressed(cause);
            }
        }
        DescriptorStaging.deleteIfStaged(staged);
    }
}
