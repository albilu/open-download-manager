package org.odm.gtk4;

import java.nio.file.Files;
import java.nio.file.Path;
import org.gnome.gio.AppInfo;
import org.gnome.gio.BusType;
import org.gnome.gio.DBusCallFlags;
import org.gnome.gio.DBusConnection;
import org.gnome.gio.Gio;
import org.gnome.glib.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Linux desktop file opening and FileManager1 item-reveal integration. */
final class FileManagerSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileManagerSupport.class);
    private static final String FILE_MANAGER_BUS = "org.freedesktop.FileManager1";
    private static final String FILE_MANAGER_PATH = "/org/freedesktop/FileManager1";
    private static final String FILE_MANAGER_INTERFACE = "org.freedesktop.FileManager1";

    private FileManagerSupport() {
    }

    static boolean open(Path file) {
        if (file == null) {
            return false;
        }
        Path normalized = file.toAbsolutePath().normalize();
        try {
            return AppInfo.launchDefaultForUri(normalized.toUri().toString(), null);
        } catch (Throwable failure) {
            LOGGER.warn("Could not open " + normalized, failure);
            return false;
        }
    }

    /**
     * Opens the containing file-manager window and selects {@code item}. If
     * the item does not exist yet, the supplied directory is opened instead.
     */
    static boolean reveal(Path item, Path containingDirectory) {
        Path normalizedItem = item == null ? null : item.toAbsolutePath().normalize();
        if (normalizedItem != null && Files.exists(normalizedItem)) {
            try {
                DBusConnection bus = Gio.busGetSync(BusType.SESSION, null);
                Variant parameters = Variant.tuple(new Variant[]{
                    Variant.strv(new String[]{normalizedItem.toUri().toString()}),
                    Variant.string("")
                });
                bus.callSync(FILE_MANAGER_BUS, FILE_MANAGER_PATH,
                        FILE_MANAGER_INTERFACE, "ShowItems", parameters, null,
                        DBusCallFlags.NONE, 5_000, null);
                return true;
            } catch (Throwable failure) {
                LOGGER.debug("FileManager1 could not reveal " + normalizedItem
                        + "; opening its directory instead", failure);
            }
        }
        Path fallback = containingDirectory;
        if (fallback == null && normalizedItem != null) {
            fallback = Files.isDirectory(normalizedItem)
                    ? normalizedItem : normalizedItem.getParent();
        }
        return open(fallback);
    }

    static Path resolveDetailPath(Path destination, String displayedPath) {
        if (displayedPath == null || displayedPath.isBlank()
                || "—".equals(displayedPath)) {
            return null;
        }
        try {
            Path path = Path.of(displayedPath);
            return (path.isAbsolute() ? path
                    : destination != null ? destination.resolve(path) : path)
                    .toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            return null;
        }
    }
}
