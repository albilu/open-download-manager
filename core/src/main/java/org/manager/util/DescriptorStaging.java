package org.manager.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.download.Download;

/**
 * Descriptor staging for watched torrent and Metalink files.
 *
 * <p>
 * Folder-monitor listeners enqueue download work asynchronously, while the
 * monitor immediately moves or deletes the watched source file. Staging
 * solves the race: before listeners are notified, the descriptor bytes are
 * copied beneath an exclusive {@code descriptor-staging} directory in ODM's
 * data directory. The same root holds unique copies of manually selected
 * descriptors when the user enables the XDG Trash policy. Containment beneath
 * this root is the ownership marker: only staged paths are automatically
 * deleted (by the aria2 handler, after successful ingestion), so no
 * download-model or persistence-schema field is required. Non-staged source
 * files always remain user-owned.
 *
 * <p>
 * The staged name is deterministic per source file ({@code <16-hex source
 * key>-<original name>}), so one staged entry exists per source: repeated
 * rounds after a failed announcement replace the retained entry instead of
 * accumulating copies. Startup reconciliation derives the original name
 * back from the key prefix.
 */
public final class DescriptorStaging {

    private static final Logger LOGGER = LoggerFactory.getLogger(DescriptorStaging.class);

    /** Name of the descriptor staging directory beneath the ODM data directory. */
    public static final String STAGING_DIR_NAME = "descriptor-staging";
    /** Subdirectory for descriptors selected explicitly in a dialog. */
    public static final String MANUAL_DIR_NAME = "manual";

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
     * Staging root for user-selected descriptors whose originals are moved to
     * Trash. Keeping these copies in a subdirectory excludes them from folder
     * monitor reconciliation while retaining the shared ownership marker used
     * by aria2 cleanup.
     *
     * @return {@code <odm data dir>/descriptor-staging/manual}
     */
    public static Path manualStagingRoot() {
        return stagingRoot().resolve(MANUAL_DIR_NAME);
    }

    /**
     * Creates a unique ODM-owned copy for one manual descriptor import.
     * Unlike watched-file staging, this must not reuse a deterministic path:
     * the same restored source may back several independently queued items.
     *
     * @param source the user-selected descriptor
     * @return unique staged copy beneath {@link #manualStagingRoot()}
     * @throws IOException if the copy cannot be made durable
     */
    public static Path stageManualFile(Path source) throws IOException {
        Path root = manualStagingRoot();
        Files.createDirectories(root);
        Path staged = root.resolve(java.util.UUID.randomUUID() + "-" + source.getFileName());
        try (OutputStream out = Files.newOutputStream(staged,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Files.copy(source, out);
        } catch (IOException copyFailure) {
            deleteQuietly(staged);
            throw copyFailure;
        }
        return staged;
    }

    /**
     * Copies a descriptor into the staging root under a name deterministic
     * for the source ({@code <source key>-<original name>}). One staged
     * entry exists per source: when a previous failed round already left an
     * entry, its bytes are replaced (atomically when possible) so repeated
     * failures cannot accumulate copies. The copy is finished (stream
     * closed) before this method returns, making the staged bytes durable
     * for later asynchronous consumers.
     *
     * @param source the detected descriptor file to stage
     * @param stagingRoot the exclusive staging directory
     * @return the staged copy's path
     * @throws IOException if the staging directory cannot be created or the
     *             copy fails; the original is left untouched
     */
    public static Path stageFile(Path source, Path stagingRoot) throws IOException {
        Files.createDirectories(stagingRoot);
        String stagedName = sourceKey(source) + "-" + source.getFileName();
        Path staged = stagingRoot.resolve(stagedName);
        if (Files.exists(staged)) {
            Path temp = stagingRoot.resolve(stagedName + ".replacing-" + java.util.UUID.randomUUID());
            try (OutputStream out = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                Files.copy(source, out);
            } catch (IOException copyFailure) {
                deleteQuietly(temp);
                throw copyFailure;
            }
            try {
                Files.move(temp, staged, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temp, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            return staged;
        }
        try (OutputStream out = Files.newOutputStream(staged,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Files.copy(source, out);
        } catch (IOException copyFailure) {
            // A partial staged file is not a durable descriptor; remove it
            // best-effort so consumers never see truncated input
            deleteQuietly(staged);
            FileAlreadyExistsException raced
                    = copyFailure instanceof FileAlreadyExistsException already ? already : null;
            if (raced != null) {
                // A concurrent round won the CREATE_NEW race for the same
                // deterministic name: its entry is the durable copy
                LOGGER.debug("Concurrent staging round already produced " + staged);
                return staged;
            }
            throw copyFailure;
        }
        return staged;
    }

    /**
     * Derives the watched source file name from a staged entry's name, or
     * null when the entry is not a reconciliation candidate (foreign name,
     * transient replacement file, or non-descriptor).
     *
     * @param stagedFile the staged entry
     * @return the original file name, or null
     */
    public static String originalNameOf(Path stagedFile) {
        if (stagedFile == null) {
            return null;
        }
        String name = stagedFile.getFileName().toString();
        if (name.length() < 18 || name.charAt(16) != '-') {
            return null;
        }
        String key = name.substring(0, 16);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        String original = name.substring(17);
        if (Download.Protocol.fromFileName(original) != null) {
            return original;
        }
        return null;
    }

    /**
     * The 16-hex staging key prefix a source file maps to. Reconciliation
     * uses it to scope shared staging entries back to the watched folder
     * that staged them: an entry whose key does not match any source of
     * the reconciled folder belongs to another folder and must not be
     * re-announced or deleted on its behalf.
     *
     * @param source the watched source file
     * @return the key prefix of {@link #stageFile}'s staged name
     */
    public static String sourceKeyPrefix(Path source) {
        return sourceKey(source);
    }

    private static String sourceKey(Path source) {
        String absolute = source.toAbsolutePath().normalize().toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(absolute.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated for the JVM; unreachable
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException cleanupFailure) {
            LOGGER.debug("Failed to remove partial staged file " + file, cleanupFailure);
        }
    }

    /**
     * Marks a staged descriptor as successfully dispatched to the listeners:
     * the asynchronous download queue now owns the staged bytes. Startup
     * reconciliation must not re-announce dispatched entries (that would
     * create duplicate downloads); an entry without the marker is an orphan
     * of a crashed round.
     *
     * @param staged the staged copy whose dispatch succeeded
     */
    public static void markDispatched(Path staged) {
        try {
            Files.writeString(dispatchMarker(staged), "dispatched");
        } catch (IOException e) {
            LOGGER.warn("Failed to mark staged descriptor as dispatched: "
                    + staged + " (startup reconciliation may re-announce it)", e);
        }
    }

    /**
     * Whether the staged descriptor was already dispatched to the listeners.
     *
     * @param staged the staged copy
     * @return true when a dispatch marker exists
     */
    public static boolean isDispatched(Path staged) {
        return staged != null && Files.exists(dispatchMarker(staged));
    }

    private static Path dispatchMarker(Path staged) {
        return staged.resolveSibling(staged.getFileName() + ".dispatched");
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
     * cleanup failure must not fail the download. A manually selected
     * descriptor's managed copy can be removed here; its non-staged original
     * is always outside this ownership boundary.
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
            Files.deleteIfExists(dispatchMarker(file));
            return true;
        } catch (IOException e) {
            LOGGER.warn("Failed to delete staged descriptor: " + file, e);
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
