package org.odm.gtk4;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.manager.download.Download;

/** Session speed samples for every transfer, independent of the selected row. */
final class DownloadSpeedHistory {

    static final int MAX_SAMPLES = 512;

    record Sample(long elapsedMillis, long downloadedBytes, double bytesPerSecond) { }

    record Snapshot(List<Sample> samples, double averageBytesPerSecond) {
        static final Snapshot EMPTY = new Snapshot(List.of(), 0);

        Snapshot {
            samples = List.copyOf(samples);
        }
    }

    // A deleted/unloaded download must not be kept alive by its graph history.
    private final Map<Download, Series> histories = new WeakHashMap<>();

    /** Called by the engine event listener; never accesses GTK widgets. */
    synchronized void record(Download download, long downloadedBytes, double speed) {
        if (download.getStatus() != Download.Status.DOWNLOADING
                && download.getStatus() != Download.Status.STARTING
                && download.getStatus() != Download.Status.CONNECTING && speed <= 0) {
            // Pausing, seeding and completion must not dilute the average.
            // Keep positive-speed events already queued before completion:
            // the mutable Download may have reached its terminal state first.
            return;
        }
        if (downloadedBytes < 0 || !Double.isFinite(speed)) {
            return;
        }
        record(download, download.getActiveElapsedMillis(), downloadedBytes, speed);
    }

    synchronized void record(Download download, long elapsedMillis,
            long downloadedBytes, double speed) {
        Series series = histories.computeIfAbsent(download, ignored -> new Series());
        series.append(new Sample(Math.max(0, elapsedMillis), Math.max(0, downloadedBytes),
                Double.isFinite(speed) ? Math.max(0, speed) : 0));
    }

    synchronized Snapshot snapshot(Download download) {
        Series series = histories.get(download);
        return series == null ? Snapshot.EMPTY : series.snapshot();
    }

    private static final class Series {
        private final List<Sample> samples = new ArrayList<>();
        private long durationMillis;
        private double speedMillis;
        private Sample latest;

        void append(Sample sample) {
            if (latest != null && (sample.downloadedBytes() < latest.downloadedBytes()
                    || sample.elapsedMillis() < latest.elapsedMillis())) {
                // A restart/recheck can move the byte counter back. Do not join
                // the new attempt to a graph for bytes that are no longer present.
                samples.clear();
                durationMillis = 0;
                speedMillis = 0;
                latest = null;
            }
            if (latest != null) {
                long interval = sample.elapsedMillis() - latest.elapsedMillis();
                durationMillis += interval;
                speedMillis += interval * (latest.bytesPerSecond() + sample.bytesPerSecond()) / 2;
                if (interval == 0) {
                    samples.set(samples.size() - 1, sample);
                    latest = sample;
                    return;
                }
            }
            samples.add(sample);
            latest = sample;
            if (samples.size() > MAX_SAMPLES) {
                compact();
            }
        }

        private void compact() {
            // Keep the beginning and current endpoint, merging adjacent interior
            // samples instead of dropping the beginning of long transfers.
            List<Sample> compacted = new ArrayList<>(MAX_SAMPLES / 2 + 2);
            compacted.add(samples.getFirst());
            for (int i = 1; i < samples.size() - 1; i += 2) {
                Sample first = samples.get(i);
                if (i + 1 == samples.size() - 1) {
                    compacted.add(first);
                    break;
                }
                Sample second = samples.get(i + 1);
                long firstDuration = first.elapsedMillis() - samples.get(i - 1).elapsedMillis();
                long secondDuration = second.elapsedMillis() - first.elapsedMillis();
                double mean = (first.bytesPerSecond() * firstDuration
                        + second.bytesPerSecond() * secondDuration) / (firstDuration + secondDuration);
                compacted.add(new Sample(second.elapsedMillis(), second.downloadedBytes(), mean));
            }
            compacted.add(samples.getLast());
            samples.clear();
            samples.addAll(compacted);
        }

        Snapshot snapshot() {
            return new Snapshot(samples, durationMillis > 0
                    ? speedMillis / durationMillis : latest.bytesPerSecond());
        }
    }
}
