package org.manager.download;

/**
 * Registration point for {@link DownloadListener}s receiving the ordered
 * core event stream.
 */
public interface DownloadListenerRegistry {

    /**
     * Adds a listener for download events.
     *
     * @param listener The listener to add
     */
    void addDownloadListener(DownloadListener listener);

    /**
     * Removes a download listener.
     *
     * @param listener The listener to remove
     */
    void removeDownloadListener(DownloadListener listener);
}
