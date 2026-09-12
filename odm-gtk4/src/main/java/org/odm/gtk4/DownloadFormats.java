package org.odm.gtk4;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.manager.download.Download;
import org.manager.util.SizeUnits;

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

    /** Human-readable byte size (B / KB / MB / GB / TB). */
    static String size(long bytes) {
        java.util.List<String> units = SizeUnits.current();
        if (bytes < 1024) {
            return bytes + " " + units.get(0);
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " " + units.get(1);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f %s", bytes / 1048576.0, units.get(2));
        }
        if (bytes < SizeUnits.TEBIBYTE) {
            return String.format("%.2f %s", bytes / (1024.0 * 1024 * 1024), units.get(3));
        }
        return String.format("%.2f %s", bytes / (double) SizeUnits.TEBIBYTE, units.get(4));
    }

    /** HTTrack may know transferred bytes without knowing the final mirror size. */
    static String totalSize(Download download) {
        return unknownWebsiteSize(download) ? "—" : size(download.getSize());
    }

    /** Downloaded / total bytes with one shared unit and locale-aware decimals. */
    static String downloadedSize(Download download) {
        long downloaded = Math.max(0, download.getDownloaded());
        long total = Math.max(0, download.getSize());
        long largest = Math.max(downloaded, total);
        java.util.List<String> units = SizeUnits.current();
        long divisor = 1;
        int unit = 0;
        while (unit < units.size() - 1 && largest / divisor >= 1024) {
            divisor *= 1024;
            unit++;
        }
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setGroupingUsed(false);
        format.setMaximumFractionDigits(2);
        boolean unknownTotal = unknownWebsiteSize(download) || (total == 0 && downloaded > 0);
        return format.format((double) downloaded / divisor) + " / "
                + (unknownTotal ? "—" : format.format((double) total / divisor))
                + " " + units.get(unit);
    }

    static String remainingSize(Download download) {
        return unknownWebsiteSize(download) ? "—"
                : size(Math.max(0, download.getSize() - download.getDownloaded()));
    }

    private static boolean unknownWebsiteSize(Download download) {
        return download.getType() == Download.Type.WEBSITE_SCRAPING && download.getSize() <= 0;
    }

    /** Human-readable transfer rate, including an explicit zero value. */
    static String rate(long bytesPerSecond) {
        return size(Math.max(0, bytesPerSecond)) + "/s";
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
