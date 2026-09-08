package org.odm.gtk4;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.manager.download.Download;

/**
 * Shared synthetic workloads and result formatting for the UI performance
 * suite. Hermetic: no GTK, no network, no external tools.
 */
final class UiPerf {

    private UiPerf() {
    }

    private static final Download.Status[] CYCLE = {
        Download.Status.DOWNLOADING, Download.Status.QUEUED, Download.Status.PAUSED,
        Download.Status.COMPLETED, Download.Status.SEEDING, Download.Status.ERROR,
        Download.Status.CANCELED, Download.Status.STARTING
    };
    private static final String[] EXTENSIONS = {"mp4", "mp3", "jpg", "exe", "zip", "iso"};

    /**
     * Builds {@code n} downloads with mixed statuses, categories, sizes and
     * progress, mirroring a lived-in history.
     */
    static List<Download> history(int n) {
        List<Download> downloads = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            try {
                String name = (i % 7 == 0 ? "Holiday-Movie-" : "file-") + i + "."
                        + EXTENSIONS[i % EXTENSIONS.length];
                Download download = new Download(new URI("https://example.com/" + name));
                download.setName(name);
                Download.Status status = CYCLE[i % CYCLE.length];
                download.setStatus(status);
                long size = 10_000_000L + i * 1_000L;
                download.setSize(size);
                download.setDownloaded(status == Download.Status.COMPLETED ? size : size / 2);
                if (status == Download.Status.DOWNLOADING || status == Download.Status.SEEDING) {
                    download.setSpeed(1_048_576);
                    download.setUploadSpeed(131_072);
                }
                if (status == Download.Status.QUEUED) {
                    download.setQueuePosition(i);
                }
                downloads.add(download);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return downloads;
    }

    /**
     * Prints one benchmark result line to stdout (picked up from surefire
     * output) and returns throughput in operations per second.
     */
    static double report(String suite, String kase, long units, long nanos, String extra) {
        double seconds = nanos / 1_000_000_000.0;
        double opsPerSec = seconds > 0 ? units / seconds : Double.POSITIVE_INFINITY;
        double avgMicros = units > 0 ? (nanos / 1000.0) / units : 0;
        System.out.println(String.format(
                "PERF %s.%s | n=%d | total=%.1fms | avg=%.2fus/op | throughput=%.0f ops/s%s",
                suite, kase, units, nanos / 1_000_000.0, avgMicros, opsPerSec,
                extra == null || extra.isEmpty() ? "" : " | " + extra));
        return opsPerSec;
    }
}
