package org.odm.gtk4;

import java.lang.foreign.Arena;
import org.freedesktop.cairo.Context;
import org.freedesktop.cairo.TextExtents;
import org.gnome.gdk.RGBA;
import org.gnome.gtk.DrawingArea;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.manager.download.Download;

/** Draws speed history inside the selected download's percentage fill. GTK thread only. */
final class DownloadProgressGraph {

    private final DrawingArea area;
    private final Label progressLabel;
    private final Label speedLabel;
    private final Label averageLabel;
    private final Label axisLabel;
    private final Label axisStart;
    private final Label axisEnd;
    private DownloadSpeedHistory.Snapshot history = DownloadSpeedHistory.Snapshot.EMPTY;
    private double fraction;
    private long totalBytes;
    private String emptyMessage = "Select a download";

    DownloadProgressGraph(GtkBuilder builder) {
        CairoSupport.ensureInitialized();
        area = Widgets.require(builder, "info_progress_bar", DrawingArea.class);
        progressLabel = Widgets.require(builder, "info_progress_value", Label.class);
        speedLabel = Widgets.require(builder, "info_speed_value", Label.class);
        averageLabel = Widgets.require(builder, "info_average_speed_value", Label.class);
        axisLabel = Widgets.require(builder, "progress_axis_label", Label.class);
        axisStart = Widgets.require(builder, "progress_axis_start", Label.class);
        axisEnd = Widgets.require(builder, "progress_axis_end", Label.class);
        area.setDrawFunc(this::draw);
        update(null, DownloadSpeedHistory.Snapshot.EMPTY);
    }

    void update(Download download, DownloadSpeedHistory.Snapshot snapshot) {
        history = download == null ? DownloadSpeedHistory.Snapshot.EMPTY : snapshot;
        totalBytes = download == null ? 0 : download.getSize();
        boolean complete = download != null && (download.getStatus() == Download.Status.COMPLETED
                || download.getStatus() == Download.Status.SEEDING);
        fraction = complete ? 1 : download == null ? 0
                : ProgressPresentation.fraction(download.getProgress());
        String progress = download == null ? "—" : totalBytes > 0 || complete
                ? ProgressPresentation.percentage(fraction * 100) : "Size unknown";
        double currentSpeed = download != null && download.getStatus() == Download.Status.DOWNLOADING
                && Float.isFinite(download.getSpeed()) ? Math.max(0, download.getSpeed()) : 0;
        String average = history.samples().isEmpty() ? "—"
                : DownloadFormats.rate((long) history.averageBytesPerSecond());
        progressLabel.setLabel(progress);
        speedLabel.setLabel("Speed: " + (download == null ? "—" : DownloadFormats.rate((long) currentSpeed)));
        averageLabel.setLabel("Average: " + average);
        boolean timeAxis = download != null && totalBytes <= 0;
        axisLabel.setLabel(timeAxis ? "Active transfer time" : "Download progress");
        axisStart.setLabel(timeAxis ? "0s" : "0%");
        long elapsed = history.samples().size() < 2 ? 0
                : history.samples().getLast().elapsedMillis() - history.samples().getFirst().elapsedMillis();
        axisEnd.setLabel(timeAxis ? elapsed / 1000 + "s" : "100%");
        emptyMessage = download == null ? "Select a download"
                : complete ? "No speed history available" : "Waiting for speed data";
        String description = download == null ? "Select a download to view its speed history"
                : "Download progress: " + progress + ". " + speedLabel.getLabel()
                        + ". Average speed: " + average
                        + ". Solid line: download speed. Dashed line: average speed.";
        area.setTooltipText(description);
        AccessibilitySupport.label(area, description);
        area.queueDraw();
    }

    void dispose() {
        area.setDrawFunc(null);
    }

    private void draw(DrawingArea widget, Context cr, int width, int height) {
        if (width < 2 || height < 2) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            RGBA foreground = new RGBA(arena);
            RGBA accent = new RGBA(arena);
            widget.getStyleContext().getColor(foreground);
            if (!widget.getStyleContext().lookupColor("accent_color", accent)
                    && !widget.getStyleContext().lookupColor("theme_selected_bg_color", accent)) {
                accent.parse("#3584e4");
            }
            render(cr, width, height, foreground, accent, arena);
        }
    }

    private void render(Context cr, int width, int height, RGBA foreground, RGBA accent, Arena arena) {
        double left = 1, right = width - 1, top = 24, bottom = Math.max(top + 1, height - 12);
        double plotWidth = right - left;
        double completedX = left + plotWidth * fraction;
        double peak = history.samples().stream().mapToDouble(DownloadSpeedHistory.Sample::bytesPerSecond)
                .max().orElse(0);
        double ceiling = Math.max(1, Math.max(peak, history.averageBytesPerSecond()) * 1.1);
        cr.save();
        roundedRectangle(cr, 0.5, 0.5, width - 1, height - 1, 6);
        cr.clip();
        color(cr, foreground, 0.035);
        cr.paint();
        color(cr, accent, 0.13);
        cr.rectangle(left, 0, plotWidth * fraction, height).fill();
        color(cr, foreground, 0.10);
        cr.setLineWidth(1);
        for (int i = 0; i <= 4; i++) {
            double y = top + (bottom - top) * i / 4;
            cr.moveTo(left, y).lineTo(right, y);
        }
        cr.stroke();

        if (!history.samples().isEmpty()) {
            var samples = history.samples();
            double firstX = sampleX(samples.getFirst(), left, plotWidth);
            double lastX = firstX;
            cr.newPath();
            cr.moveTo(firstX, bottom);
            for (var sample : samples) {
                lastX = sampleX(sample, left, plotWidth);
                cr.lineTo(lastX, bottom - (bottom - top) * sample.bytesPerSecond() / ceiling);
            }
            cr.lineTo(lastX, bottom).closePath();
            color(cr, accent, 0.22);
            cr.fill();
            cr.newPath();
            for (int i = 0; i < samples.size(); i++) {
                var sample = samples.get(i);
                double x = sampleX(sample, left, plotWidth);
                double y = bottom - (bottom - top) * sample.bytesPerSecond() / ceiling;
                if (i == 0) {
                    cr.moveTo(x, y);
                } else {
                    cr.lineTo(x, y);
                }
            }
            color(cr, accent, 1);
            cr.setLineWidth(1.8).stroke();
            if (samples.size() == 1) {
                cr.arc(firstX, bottom - (bottom - top) * samples.getFirst().bytesPerSecond() / ceiling,
                        2.5, 0, Math.PI * 2).fill();
            }
            double averageY = bottom - (bottom - top) * history.averageBytesPerSecond() / ceiling;
            color(cr, foreground, 0.65);
            cr.setLineWidth(1).setDash(new double[]{5, 4}, 0);
            cr.moveTo(left, averageY).lineTo(right, averageY).stroke();
            cr.setDash(new double[0], 0);
            color(cr, foreground, 0.85);
            cr.setFontSize(11);
            cr.moveTo(8, 15).showText(DownloadFormats.rate((long) ceiling));
        } else {
            color(cr, foreground, 0.65);
            cr.setFontSize(12);
            TextExtents extents = TextExtents.create(arena);
            cr.textExtents(emptyMessage, extents);
            cr.moveTo(Math.max(8, (width - extents.width()) / 2), height / 2.0 + 4).showText(emptyMessage);
        }
        if (fraction > 0 && fraction < 1) {
            color(cr, accent, 0.7);
            cr.setLineWidth(1);
            cr.moveTo(completedX, 0).lineTo(completedX, height).stroke();
        }
        cr.restore();
        cr.save();
        roundedRectangle(cr, 0.5, 0.5, width - 1, height - 1, 6);
        color(cr, foreground, 0.22);
        cr.setLineWidth(1).stroke();
        cr.restore();
    }

    private double sampleX(DownloadSpeedHistory.Sample sample, double left, double width) {
        double position;
        if (totalBytes > 0) {
            position = (double) sample.downloadedBytes() / totalBytes;
        } else {
            long start = history.samples().getFirst().elapsedMillis();
            long duration = history.samples().getLast().elapsedMillis() - start;
            position = duration > 0 ? (double) (sample.elapsedMillis() - start) / duration : 0;
        }
        return left + width * Math.max(0, Math.min(1, position));
    }

    private static void color(Context cr, RGBA color, double alpha) {
        cr.setSourceRGBA(color.readRed(), color.readGreen(), color.readBlue(), alpha);
    }

    private static void roundedRectangle(Context cr, double x, double y, double width, double height, double radius) {
        double r = Math.min(radius, Math.min(width, height) / 2);
        cr.newSubPath();
        cr.arc(x + width - r, y + r, r, -Math.PI / 2, 0);
        cr.arc(x + width - r, y + height - r, r, 0, Math.PI / 2);
        cr.arc(x + r, y + height - r, r, Math.PI / 2, Math.PI);
        cr.arc(x + r, y + r, r, Math.PI, Math.PI * 1.5);
        cr.closePath();
    }
}
