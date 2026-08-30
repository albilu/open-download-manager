package org.odm.gtk4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Installs or removes ODM's per-user freedesktop autostart entry. */
final class AutostartManager {

    private static final String DESKTOP_ENTRY = """
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=oDM
            Comment=Open Download Manager
            Exec=open-download-manager
            Icon=open-download-manager
            Terminal=false
            X-GNOME-Autostart-enabled=true
            """;

    private AutostartManager() {
    }

    static Path entryPath() {
        String xdgConfigHome = System.getenv("XDG_CONFIG_HOME");
        Path configHome = xdgConfigHome != null && !xdgConfigHome.isBlank()
                ? Path.of(xdgConfigHome)
                : Path.of(System.getProperty("user.home"), ".config");
        return configHome.resolve("autostart").resolve("open-download-manager.desktop");
    }

    static void setEnabled(boolean enabled) throws IOException {
        Path target = entryPath();
        if (!enabled) {
            Files.deleteIfExists(target);
            return;
        }

        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".open-download-manager-", ".desktop");
        try {
            Files.writeString(temporary, DESKTOP_ENTRY, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
