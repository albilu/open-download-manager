package org.odm.ui.utils;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Utility class for file system operations.
 */
public final class FileUtils {

    private static final Logger LOGGER = Logger.getLogger(FileUtils.class.getName());

    private FileUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Gets available disk space for a path.
     * 
     * @param path Path to check
     * @return Available disk space in bytes, or -1 if error
     */
    public static long getAvailableDiskSpace(Path path) {
        try {
            if (path != null && Files.exists(path)) {
                FileStore fileStore = Files.getFileStore(path);
                return fileStore.getUsableSpace();
            }
        } catch (IOException e) {
            LOGGER.warning("Error getting disk space for " + path + ": " + e.getMessage());
        }
        return -1;
    }

    /**
     * Gets total disk space for a path.
     * 
     * @param path Path to check
     * @return Total disk space in bytes, or -1 if error
     */
    public static long getTotalDiskSpace(Path path) {
        try {
            if (path != null && Files.exists(path)) {
                FileStore fileStore = Files.getFileStore(path);
                return fileStore.getTotalSpace();
            }
        } catch (IOException e) {
            LOGGER.warning("Error getting disk space for " + path + ": " + e.getMessage());
        }
        return -1;
    }
}
