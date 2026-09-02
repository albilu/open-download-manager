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

    /**
     * Resolves ODM's XDG state directory, honoring {@code XDG_STATE_HOME}
     * and defaulting to {@code ~/.local/state/odm}.
     *
     * <p>Restart databases, daemon bookkeeping, and logs belong here. User
     * configuration remains under XDG_CONFIG_HOME and durable application
     * data such as staged descriptors remains under XDG_DATA_HOME.</p>
     *
     * @return the directory in which ODM persists runtime state
     */
    public static Path stateDirectory() {
        String xdgStateHome = System.getenv("XDG_STATE_HOME");
        Path base = (xdgStateHome != null && !xdgStateHome.isBlank())
                ? Paths.get(xdgStateHome)
                : Paths.get(System.getProperty("user.home"), ".local", "state");
        return base.resolve("odm");
    }
}
