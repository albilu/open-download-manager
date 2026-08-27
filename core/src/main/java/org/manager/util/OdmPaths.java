package org.manager.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Central resolution of ODM's XDG locations. Shared so independently
 * constructed components (download-manager state store, folder-monitor
 * descriptor staging, aria2 handler cleanup) agree on the same directories.
 */
public final class OdmPaths {

    private OdmPaths() {
    }

    /**
     * Resolves the XDG data directory for ODM state files, honoring
     * XDG_DATA_HOME and defaulting to ~/.local/share/odm.
     *
     * @return the directory in which ODM persists application data
     */
    public static Path dataDirectory() {
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        Path base = (xdgDataHome != null && !xdgDataHome.isBlank())
                ? Paths.get(xdgDataHome)
                : Paths.get(System.getProperty("user.home"), ".local", "share");
        return base.resolve("odm");
    }
}
