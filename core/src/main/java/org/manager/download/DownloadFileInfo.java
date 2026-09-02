package org.manager.download;

import java.util.Objects;

/**
 * Immutable file metadata discovered before a torrent, magnet, or Metalink is
 * admitted to the download queue.
 *
 * @param index engine-compatible one-based file index
 * @param path descriptor-relative file path
 * @param length file length in bytes, or {@code 0} when unknown
 */
public record DownloadFileInfo(int index, String path, long length) {

    public DownloadFileInfo {
        if (index < 1) {
            throw new IllegalArgumentException("File index must be positive");
        }
        path = Objects.requireNonNull(path, "path").strip();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("File path cannot be blank");
        }
        if (length < 0) {
            throw new IllegalArgumentException("File length cannot be negative");
        }
    }
}
