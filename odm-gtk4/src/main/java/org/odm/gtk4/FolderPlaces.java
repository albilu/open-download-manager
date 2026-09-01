package org.odm.gtk4;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gio.Mount;
import org.gnome.gio.VolumeMonitor;
import org.gnome.glib.GLib;
import org.gnome.glib.UserDirectory;

/** Discovers the local places shown by a GTK3-style folder chooser. */
final class FolderPlaces {

    private static final Logger LOGGER = LoggerFactory.getLogger(FolderPlaces.class);

    record Place(String label, String iconName, Path path) {

        Place {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Folder-place label must not be blank");
            }
            Objects.requireNonNull(iconName, "iconName");
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        }
    }

    private FolderPlaces() {
    }

    /**
     * Returns the stable GTK places first and mounted local volumes after the
     * filesystem root, matching the ordering of GTK3's folder chooser button.
     */
    static List<Place> discover() {
        List<Place> candidates = new ArrayList<>();
        Path home = homeDirectory();
        if (home != null) {
            Path filename = home.getFileName();
            candidates.add(new Place(filename == null ? "Home" : filename.toString(),
                    "user-home-symbolic", home));
        }

        addSpecial(candidates, "Desktop", "user-desktop-symbolic",
                UserDirectory.DIRECTORY_DESKTOP);
        candidates.add(new Place("File System", "drive-harddisk-symbolic", Path.of("/")));
        addMountedVolumes(candidates);
        addSpecial(candidates, "Documents", "folder-documents-symbolic",
                UserDirectory.DIRECTORY_DOCUMENTS);
        addSpecial(candidates, "Music", "folder-music-symbolic",
                UserDirectory.DIRECTORY_MUSIC);
        addSpecial(candidates, "Pictures", "folder-pictures-symbolic",
                UserDirectory.DIRECTORY_PICTURES);
        addSpecial(candidates, "Videos", "folder-videos-symbolic",
                UserDirectory.DIRECTORY_VIDEOS);
        addSpecial(candidates, "Downloads", "folder-download-symbolic",
                UserDirectory.DIRECTORY_DOWNLOAD);

        return filterAndDeduplicate(candidates, Files::isDirectory);
    }

    static List<Place> filterAndDeduplicate(Collection<Place> candidates,
            Predicate<Path> include) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(include, "include");
        LinkedHashMap<Path, Place> unique = new LinkedHashMap<>();
        for (Place place : candidates) {
            if (place != null && include.test(place.path())) {
                unique.putIfAbsent(place.path(), place);
            }
        }
        return List.copyOf(unique.values());
    }

    private static Path homeDirectory() {
        try {
            var home = GLib.getHomeDir();
            if (home != null) {
                return Path.of(home.toString());
            }
        } catch (RuntimeException e) {
            LOGGER.debug("GLib home-directory lookup failed", e);
        }
        String userHome = System.getProperty("user.home", "");
        return userHome.isBlank() ? null : Path.of(userHome);
    }

    private static void addSpecial(List<Place> places, String label, String iconName,
            UserDirectory directory) {
        try {
            var specialDirectory = GLib.getUserSpecialDir(directory);
            if (specialDirectory != null) {
                places.add(new Place(label, iconName, Path.of(specialDirectory.toString())));
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Could not resolve the " + label + " directory", e);
        }
    }

    private static void addMountedVolumes(List<Place> places) {
        try {
            for (Mount mount : VolumeMonitor.get().getMounts()) {
                org.gnome.gio.File root = mount.getRoot();
                if (root != null && root.getPath() != null) {
                    String name = mount.getName();
                    places.add(new Place(name == null || name.isBlank() ? "Mounted Volume" : name,
                            "drive-removable-media-symbolic",
                            Path.of(root.getPath().toString())));
                }
            }
        } catch (Throwable e) {
            // Volume monitoring can be unavailable in restricted containers or
            // sessions without a desktop bus; standard local places still work.
            LOGGER.debug("Mounted-volume discovery unavailable", e);
        }
    }
}
