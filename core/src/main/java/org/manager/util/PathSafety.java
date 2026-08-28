package org.manager.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Deletion safety helpers shared by the download engines: plain-file-name
 * validation for model-supplied names and real-path containment for file
 * deletion. Lexical checks alone cannot confine deletion — a symlinked
 * subdirectory resolves outside the destination while still passing a
 * normalized {@code startsWith} check — so every deletion candidate must be
 * re-validated against the resolved location immediately before removal.
 */
public final class PathSafety {

    private PathSafety() {
    }

    /**
     * Whether a download name is a plain file name: no path separators, no
     * self/parent directory references. Null is tolerated (the download
     * model treats a missing name as unset).
     *
     * @param name the candidate download name
     * @return true when the name cannot alter a resolved path
     */
    public static boolean isSafeFileName(String name) {
        if (name == null) {
            return true;
        }
        if (name.equals(".") || name.equals("..")) {
            return false;
        }
        return name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    /**
     * @param name the candidate download name
     * @throws IllegalArgumentException when the name contains path
     *         separators or directory references
     */
    public static void requireSafeFileName(String name) {
        if (!isSafeFileName(name)) {
            throw new IllegalArgumentException("Unsafe download name: " + name);
        }
    }

    /**
     * Real-path containment: the candidate's resolved location must stay
     * beneath the base's resolved location. The parent chain is resolved
     * first so a not-yet-existing candidate is judged by its nearest
     * existing ancestor (a symlinked directory component resolves outside
     * and fails), and a symlink final component is resolved as well — a
     * link that escapes is rejected even though deleting the link itself
     * would be harmless.
     *
     * @param candidate the path considered for deletion
     * @param base the confining directory
     * @return true when the candidate cannot escape the base
     */
    public static boolean isConfined(Path candidate, Path base) {
        try {
            Path realBase = base.toAbsolutePath().normalize().toRealPath();
            Path absolute = candidate.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent == null || !parent.toRealPath().startsWith(realBase)) {
                return false;
            }
            if (Files.isSymbolicLink(absolute)) {
                return absolute.toRealPath().startsWith(realBase);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Deletes a single file only when it is confined to the base directory.
     * Containment is re-checked immediately before the deletion to narrow
     * swap races, and the deletion itself never follows a final symlink.
     *
     * @param candidate the file to delete
     * @param base the confining directory
     * @return true when the file existed and was deleted
     */
    public static boolean deleteIfExistsConfined(Path candidate, Path base) {
        if (!isConfined(candidate, base)) {
            return false;
        }
        try {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            // NIO delete never follows a final symlink; the NOFOLLOW
            // existence check above keeps that intent explicit
            Files.deleteIfExists(candidate);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
