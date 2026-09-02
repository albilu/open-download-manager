package org.odm.gtk4;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.manager.download.Download;

/**
 * Shared read-only formatting of download values: human sizes, ETA,
 * elapsed time, and the tabular date format. Plain logic, no GTK.
 */
final class DownloadFormats {

    /** Date format used in the list and info panel (original UI format). */
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private DownloadFormats() {
    }

    /** Human-readable byte size (B / KB / MB / GB). */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1048576.0);
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** ETA of a downloading transfer, or an em-dash when not computable. */
    static String eta(Download download) {
        float speed = download.getSpeed();
        long remaining = download.getSize() - download.getDownloaded();
        if (speed <= 0 || remaining <= 0 || download.getStatus() != Download.Status.DOWNLOADING) {
            return "—";
        }
        long seconds = (long) (remaining / speed);
        Duration d = Duration.ofSeconds(seconds);
        return hms(d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }

    /** Active transfer time; queued and paused wall-clock time is excluded. */
    static String elapsed(Download download) {
        if (download.getStartedAt() == null && download.getActiveElapsedMillis() == 0) {
            return "—";
        }
        Duration d = Duration.ofMillis(download.getActiveElapsedMillis());
        return hms(d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }

    private static String hms(long h, long m, long s) {
        return h > 0 ? String.format("%dh %dm", h, m) : m > 0 ? String.format("%dm %ds", m, s)
                : String.format("%ds", s);
    }
}
