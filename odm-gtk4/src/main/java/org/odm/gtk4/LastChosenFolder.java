package org.odm.gtk4;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.manager.util.OdmPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Remembers the last explicit folder choice independently of download defaults. */
final class LastChosenFolder {
    private static final Logger LOGGER = LoggerFactory.getLogger(LastChosenFolder.class);
    private final Path stateFile;

    LastChosenFolder() {
        this(OdmPaths.stateDirectory().resolve("last-chosen-folder"));
    }

    LastChosenFolder(Path stateFile) {
        this.stateFile = stateFile.toAbsolutePath().normalize();
    }

    Path load() {
        if (!Files.isRegularFile(stateFile)) {
            return null;
        }
        try {
            String value = Files.readString(stateFile);
            if (value.isEmpty()) {
                return null;
            }
            Path folder = Path.of(value);
            return folder.isAbsolute() && Files.isDirectory(folder) ? folder.normalize() : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not read the last chosen folder", e);
            return null;
        }
    }

    void remember(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            return;
        }
        try {
            Files.createDirectories(stateFile.getParent());
            Path temporary = Files.createTempFile(stateFile.getParent(), ".last-chosen-folder-", ".tmp");
            try {
                Files.writeString(temporary, folder.toAbsolutePath().normalize().toString());
                try {
                    Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException e) {
            // Remembering a shortcut must never prevent the actual folder selection.
            LOGGER.debug("Could not remember the last chosen folder", e);
        }
    }
}
