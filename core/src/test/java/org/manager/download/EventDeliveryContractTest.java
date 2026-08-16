package org.manager.download;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the DownloadManager event-dispatch seam: all
 * DownloadListener notifications must be dispatched on the dedicated
 * single-threaded "odm-events" executor, in submission order, with listener
 * exceptions isolated.
 */
class EventDeliveryContractTest {

    private static DownloadListener recordingListener(List<String> order, Set<String> threads,
            CountDownLatch latch) {
        return new DownloadListener() {
            private void record(String event) {
                threads.add(Thread.currentThread().getName());
                order.add(event);
                latch.countDown();
            }

            @Override
            public void onDownloadStart(Download download) {
                record("start");
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes,
                    long totalBytes, float speed) {
                record("progress");
            }

            @Override
            public void onDownloadPause(Download download) {
                record("pause");
            }

            @Override
            public void onDownloadResume(Download download) {
                record("resume");
            }

            @Override
            public void onDownloadComplete(Download download) {
                record("complete");
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                record("error");
            }

            @Override
            public void onDownloadCanceled(Download download) {
                record("cancel");
            }
        };
    }

    @Test
    @DisplayName("Manager dispatches listener events on the dedicated odm-events thread, in order")
    void dispatchOnDedicatedOrderedThread() throws Exception {
        DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();

        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Set<String> threads = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(3);
        DownloadListener listener = recordingListener(order, threads, latch);
        manager.addDownloadListener(listener);
        try {
            Download download = new Download(new URI("http://example.test/file.bin"));
            manager.fireEvent(l -> l.onDownloadStart(download));
            manager.fireEvent(l -> l.onDownloadProgress(download, 0.5f, 1, 2, 1));
            manager.fireEvent(l -> l.onDownloadComplete(download));

            assertTrue(latch.await(10, TimeUnit.SECONDS), "events should be delivered");
            assertEquals(List.of("start", "progress", "complete"), order);
            assertEquals(1, threads.size(), "all events must arrive on one thread");
            assertTrue(threads.iterator().next().startsWith("odm-events"),
                    "dispatch thread must be the odm-events executor thread");
        } finally {
            manager.removeDownloadListener(listener);
        }
    }

    @Test
    @DisplayName("A throwing listener does not prevent other listeners from receiving events")
    void listenerExceptionIsIsolated() throws Exception {
        DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();

        DownloadListener faulty = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                throw new RuntimeException("faulty listener");
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes,
                    long totalBytes, float speed) {
                throw new RuntimeException("faulty listener");
            }

            @Override
            public void onDownloadPause(Download download) {
            }

            @Override
            public void onDownloadResume(Download download) {
            }

            @Override
            public void onDownloadComplete(Download download) {
                throw new RuntimeException("faulty listener");
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
            }

            @Override
            public void onDownloadCanceled(Download download) {
            }
        };

        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Set<String> threads = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(3);
        DownloadListener good = recordingListener(order, threads, latch);

        manager.addDownloadListener(faulty);
        manager.addDownloadListener(good);
        try {
            Download download = new Download(new URI("http://example.test/file2.bin"));
            manager.fireEvent(l -> l.onDownloadStart(download));
            manager.fireEvent(l -> l.onDownloadProgress(download, 1f, 2, 2, 2));
            manager.fireEvent(l -> l.onDownloadComplete(download));

            assertTrue(latch.await(10, TimeUnit.SECONDS),
                    "good listener must still receive all events despite the faulty one");
            assertEquals(List.of("start", "progress", "complete"), order);
        } finally {
            manager.removeDownloadListener(faulty);
            manager.removeDownloadListener(good);
        }
    }
}
