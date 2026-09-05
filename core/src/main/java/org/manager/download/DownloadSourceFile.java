package org.manager.download;

import java.util.List;

/** A file's mirrors and current connections. GIDs are live session handles, never persisted. */
public record DownloadSourceFile(String gid, int index, String key, String name,
        List<Source> sources) {
    public DownloadSourceFile {
        sources = List.copyOf(sources);
    }

    public record Source(String uri, String state, long bytesPerSecond, String currentUri) { }
}
