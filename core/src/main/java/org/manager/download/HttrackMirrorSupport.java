package org.manager.download;

import java.nio.file.Files;
import java.nio.file.Path;
import org.httrack.HttrackSettings;

/** Shared path and capability rules for persisted HTTrack mirrors. */
public final class HttrackMirrorSupport {

    public enum DiagnosticLog {
        ACTIVITY("hts-log.txt"),
        ERRORS("hts-err.txt");

        private final String fileName;

        DiagnosticLog(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }

    private HttrackMirrorSupport() {
    }

    /** Returns the recorded mirror root, with a legacy destination/name fallback. */
    public static Path mirrorDirectory(Download download) {
        if (download == null || download.getType() != Download.Type.WEBSITE_SCRAPING) {
            return null;
        }
        Path output = download.getPrimaryOutputPath();
        if (output != null) {
            return output.toAbsolutePath().normalize();
        }
        if (download.getDestination() == null || download.getName() == null
                || download.getName().isBlank()) {
            return null;
        }
        org.manager.util.PathSafety.requireSafeFileName(download.getName());
        return download.getDestination().resolve(download.getName())
                .toAbsolutePath().normalize();
    }

    public static boolean canUpdate(Download download) {
        Path mirror = mirrorDirectory(download);
        return download != null
                && download.getStatus() == Download.Status.COMPLETED
                && download.getSettings() instanceof HttrackSettings
                && mirror != null
                && Files.isDirectory(mirror.resolve("hts-cache"));
    }

    /** Applies the explicit update policy after validating the persisted mirror. */
    public static HttrackSettings prepareUpdate(Download download, boolean purgeOldFiles) {
        if (!canUpdate(download)) {
            throw new IllegalStateException(
                    "Only a completed HTTrack mirror with an existing hts-cache can be updated");
        }
        HttrackSettings settings = (HttrackSettings) download.getSettings();
        settings.setRunMode(HttrackSettings.RunMode.UPDATE)
                .setPurgeOldFiles(purgeOldFiles);
        download.setErrorMessage(null);
        return settings;
    }

    public static Path diagnosticPath(Download download, DiagnosticLog log) {
        Path mirror = mirrorDirectory(download);
        return mirror == null || log == null ? null : mirror.resolve(log.fileName());
    }

    public static boolean hasDiagnostic(Download download, DiagnosticLog log) {
        Path path = diagnosticPath(download, log);
        return path != null && Files.isRegularFile(path);
    }
}
