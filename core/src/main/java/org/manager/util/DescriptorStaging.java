package org.manager.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Descriptor staging for watched torrent and Metalink files.
 *
 * <p>
 * Folder-monitor listeners enqueue download work asynchronously, while the
 * monitor immediately moves or deletes the watched source file. Staging
 * solves the race: before listeners are notified, the descriptor bytes are
 * copied beneath an exclusive {@code descriptor-staging} directory in ODM's
 * data directory. Containment beneath this root is the ownership marker:
 * only staged paths are automatically deleted (by the aria2 handler, after
 * successful ingestion), so no download-model or persistence-schema field
 * is required. Manually selected torrent or Metalink files remain
 * user-owned and are never auto-deleted.
 */
public final class DescriptorStaging {

    private static final Logger LOGGER = Logger.getLogger(DescriptorStaging.class.getName());

    /** Name of the staging directory beneath the ODM data directory. */
    public static final String STAGING_DIR_NAME = "descriptor-staging";

    private DescriptorStaging() {
    }

    /**
     * The default staging root: {@code <odm data dir>/descriptor-staging}.
     *
     * @return the exclusive staging directory path (not created by this call)
     */
    public static Path stagingRoot() {
        return OdmPaths.dataDirectory().resolve(STAGING_DIR_NAME);
    }

    /**
     * Copies a descriptor into the staging root under a collision-resistant
     * name ({@code <uuid>-<original name>}) created with {@code CREATE_NEW},
     * so an existing staged file can never be replaced. The copy is finished
     * (stream closed) before this method returns, making the staged bytes
     * durable for later asynchronous consumers.
     *
     * @param source the detected descriptor file to stage
     * @param stagingRoot the exclusive staging directory
     * @return the staged copy's path
     * @throws IOException if the staging directory cannot be created or the
     *             copy fails; the original is left untouched
     */
    public static Path stageFile(Path source, Path stagingRoot) throws IOException {
        Files.createDirectories(stagingRoot);
        String stagedName = UUID.randomUUID() + "-" + source.getFileName();
        Path staged = stagingRoot.resolve(stagedName);
        try (OutputStream out = Files.newOutputStream(staged,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Files.copy(source, out);
        } catch (IOException copyFailure) {
            // A partial staged file is not a durable descriptor; remove it
            // best-effort so consumers never see truncated input
            try {
                Files.deleteIfExists(staged);
            } catch (IOException cleanupFailure) {
                copyFailure.addSuppressed(cleanupFailure);
            }
            throw copyFailure;
        }
        return staged;
    }

    /**
     * Containment check: whether the given path is a file ODM manages
     * beneath the staging root (the ownership marker).
     *
     * @param file the candidate descriptor path
     * @param stagingRoot the exclusive staging directory
     * @return true only if the normalized path lies strictly beneath the
     *         normalized staging root
     */
    public static boolean isStagedPath(Path file, Path stagingRoot) {
        if (file == null || stagingRoot == null) {
            return false;
        }
        Path normalizedFile = file.toAbsolutePath().normalize();
        Path normalizedRoot = stagingRoot.toAbsolutePath().normalize();
        return !normalizedFile.equals(normalizedRoot) && normalizedFile.startsWith(normalizedRoot);
    }

    /**
     * Deletes a descriptor only when it is ODM-managed (beneath the default
     * staging root). Called after aria2 successfully consumed the bytes;
     * manually selected files are never removed by this method.
     *
     * @param file the descriptor file considered for deletion
     * @return true if a staged file was deleted
     */
    public static boolean deleteIfStaged(Path file) {
        return deleteIfStaged(file, stagingRoot());
    }

    /**
     * Deletes a descriptor only when it lies beneath the given staging root.
     * Failures are logged and swallowed: ingestion already succeeded, so a
     * cleanup failure must not fail the download.
     *
     * @param file the descriptor file considered for deletion
     * @param stagingRoot the exclusive staging directory
     * @return true if a staged file was deleted
     */
    public static boolean deleteIfStaged(Path file, Path stagingRoot) {
        if (!isStagedPath(file, stagingRoot)) {
            return false;
        }
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to delete staged descriptor: " + file, e);
            return false;
        }
    }

    /**
     * Reserves a collision-safe target name in a directory: the returned
     * path did not exist at check time and the caller must still create or
     * move onto it with non-replacing semantics (for example
     * {@link Files#move(Path, Path)} without
     * {@link java.nio.file.StandardCopyOption#REPLACE_EXISTING}), retrying
     * with the next candidate on {@link FileAlreadyExistsException}.
     *
     * @param directory the destination directory
     * @param fileName the desired file name
     * @param attempt the collision retry attempt (0 for the plain name)
     * @return the candidate target path
     */
    public static Path collisionSafeTarget(Path directory, String fileName, int attempt) {
        if (attempt <= 0) {
            return directory.resolve(fileName);
        }
        int dotIndex = fileName.lastIndexOf('.');
        String base = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String extension = dotIndex > 0 ? fileName.substring(dotIndex) : "";
        return directory.resolve(base + "-" + attempt + extension);
    }
}
