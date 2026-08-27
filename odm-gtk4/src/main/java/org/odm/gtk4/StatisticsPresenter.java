package org.odm.gtk4;

import java.util.List;

import org.manager.download.Download;

/**
 * Aggregation behind the statistics dialog: counts by status class and
 * total size totals. Plain logic, no GTK — the dialog only formats it.
 */
final class StatisticsPresenter {

    record Stats(int total, int active, int queued, int finished, int errors,
            long totalSize, long doneSize) {
    }

    private StatisticsPresenter() {
    }

    static Stats aggregate(List<Download> all) {
        long totalSize = 0;
        long doneSize = 0;
        int active = 0;
        int queued = 0;
        int finished = 0;
        int errors = 0;
        for (Download d : all) {
            totalSize += d.getSize();
            doneSize += d.getDownloaded();
            switch (d.getStatus()) {
                case DOWNLOADING, CONNECTING -> active++;
                case QUEUED, PAUSED -> queued++;
                case COMPLETED -> finished++;
                case ERROR, CANCELED -> errors++;
                default -> { }
            }
        }
        return new Stats(all.size(), active, queued, finished, errors, totalSize, doneSize);
    }
}
