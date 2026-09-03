package org.manager.util;

import java.io.IOException;
import java.nio.file.Files;
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

    /**
     * Resolves the user's XDG Downloads directory. Desktop environments store
     * the localized/user-selected value in
     * {@code $XDG_CONFIG_HOME/user-dirs.dirs}; when that file is absent or
     * invalid, the conventional {@code ~/Downloads} fallback is used.
     *
     * @return the directory ODM should use for new downloads by default
     */
    public static Path downloadDirectory() {
        Path home = Paths.get(System.getProperty("user.home"));
        String xdgConfigHome = System.getenv("XDG_CONFIG_HOME");
        Path configHome = xdgConfigHome != null && !xdgConfigHome.isBlank()
                ? Paths.get(xdgConfigHome)
                : home.resolve(".config");
        return downloadDirectory(home, configHome.resolve("user-dirs.dirs"));
    }

    static Path downloadDirectory(Path home, Path userDirsFile) {
        Path fallback = home.resolve("Downloads");
        if (!Files.isRegularFile(userDirsFile)) {
            return fallback;
        }
        try {
            for (String line : Files.readAllLines(userDirsFile)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("XDG_DOWNLOAD_DIR=")) {
                    continue;
                }
                String value = trimmed.substring("XDG_DOWNLOAD_DIR=".length()).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                value = value.replace("\\\"", "\"").replace("\\\\", "\\");
                if (value.equals("$HOME") || value.equals("${HOME}")) {
                    return home.normalize();
                }
                if (value.startsWith("$HOME/")) {
                    return home.resolve(value.substring("$HOME/".length())).normalize();
                }
                if (value.startsWith("${HOME}/")) {
                    return home.resolve(value.substring("${HOME}/".length())).normalize();
                }
                Path configured = Paths.get(value);
                return configured.isAbsolute() ? configured.normalize() : fallback;
            }
        } catch (IOException | RuntimeException ignored) {
            // A malformed desktop configuration must not prevent startup.
        }
        return fallback;
    }
}
