package org.manager.util;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Moves files to the freedesktop.org home trash.
 *
 * <p>The trash root is {@code ${XDG_DATA_HOME:-~/.local/share}/Trash}. Each
 * payload is paired with a collision-safe {@code .trashinfo} record, so files
 * remain restorable by Linux desktop file managers.</p>
 */
public final class XdgTrash {

    private static final DateTimeFormatter DELETION_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private XdgTrash() {
    }

    /**
     * Resolves the freedesktop home-trash root.
     *
     * @return {@code $XDG_DATA_HOME/Trash}, or {@code ~/.local/share/Trash}
     */
    public static Path trashRoot() {
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        Path dataHome = xdgDataHome != null && !xdgDataHome.isBlank()
                ? Paths.get(xdgDataHome)
                : Paths.get(System.getProperty("user.home"), ".local", "share");
        return dataHome.resolve("Trash");
    }

    /**
     * Moves a regular file to the XDG home trash without replacing an
     * existing payload or metadata record.
     *
     * @param source file to trash
     * @return the payload's final path beneath {@code Trash/files}
     * @throws IOException if the source cannot be moved safely
     */
    public static Path moveToTrash(Path source) throws IOException {
        return moveToTrash(source, trashRoot());
    }

    static Path moveToTrash(Path source, Path root) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("Source cannot be null");
        }
        Path absoluteSource = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absoluteSource)) {
            throw new IOException("Trash source is not a regular file: " + absoluteSource);
        }

        Path filesDirectory = root.resolve("files");
        Path infoDirectory = root.resolve("info");
        Files.createDirectories(filesDirectory);
        Files.createDirectories(infoDirectory);

        String fileName = absoluteSource.getFileName().toString();
        for (int attempt = 0; ; attempt++) {
            Path target = DescriptorStaging.collisionSafeTarget(filesDirectory, fileName, attempt);
            Path info = infoDirectory.resolve(target.getFileName() + ".trashinfo");
            try {
                Files.writeString(info, trashInfo(absoluteSource),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }

            try {
                moveWithoutReplacing(absoluteSource, target);
                return target;
            } catch (FileAlreadyExistsException collision) {
                Files.deleteIfExists(info);
            } catch (IOException failure) {
                Files.deleteIfExists(info);
                throw failure;
            }
        }
    }

    private static void moveWithoutReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target);
        } catch (FileAlreadyExistsException collision) {
            throw collision;
        } catch (IOException moveFailure) {
            // Home trash can live on another filesystem. Copy/delete is the
            // safe fallback: a failed delete rolls the copied payload back so
            // the original remains authoritative.
            try {
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                try {
                    Files.delete(source);
                } catch (IOException deleteFailure) {
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException cleanupFailure) {
                        deleteFailure.addSuppressed(cleanupFailure);
                    }
                    deleteFailure.addSuppressed(moveFailure);
                    throw deleteFailure;
                }
            } catch (FileAlreadyExistsException collision) {
                throw collision;
            } catch (IOException copyFailure) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupFailure) {
                    copyFailure.addSuppressed(cleanupFailure);
                }
                copyFailure.addSuppressed(moveFailure);
                throw copyFailure;
            }
        }
    }

    private static String trashInfo(Path source) {
        String encodedPath = source.toUri().getRawPath();
        return "[Trash Info]\nPath=" + encodedPath
                + "\nDeletionDate=" + LocalDateTime.now().format(DELETION_TIME)
                + "\n";
    }
}
