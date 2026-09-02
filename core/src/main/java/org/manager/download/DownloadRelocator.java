package org.manager.download;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Transactional filesystem/model portion of a download relocation. */
final class DownloadRelocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadRelocator.class);

    private record Move(Path source, Path target) {
    }

    /**
     * A completed relocation that can be rolled back if engine
     * reconfiguration fails after the filesystem move.
     */
    static final class Relocation {

        private final Path previousDestination;
        private final Path newDestination;
        private final List<Path> previousOutputs;
        private final List<Move> moves;
        private final boolean noOp;

        private Relocation(Path previousDestination, Path newDestination,
                List<Path> previousOutputs, List<Move> moves, boolean noOp) {
            this.previousDestination = previousDestination;
            this.newDestination = newDestination;
            this.previousOutputs = previousOutputs;
            this.moves = moves;
            this.noOp = noOp;
        }

        Path previousDestination() {
            return previousDestination;
        }

        Path newDestination() {
            return newDestination;
        }

        boolean noOp() {
            return noOp;
        }

        void rollback(Download download) throws IOException {
            IOException rollbackFailure = null;
            for (int i = moves.size() - 1; i >= 0; i--) {
                Move move = moves.get(i);
                try {
                    movePath(move.target(), move.source());
                } catch (IOException e) {
                    if (rollbackFailure == null) {
                        rollbackFailure = new IOException(
                                "Could not roll back download relocation", e);
                    } else {
                        rollbackFailure.addSuppressed(e);
                    }
                }
            }
            download.setDestination(previousDestination);
            download.setOutputPaths(previousOutputs);
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
        }
    }

    private DownloadRelocator() {
    }

    static Relocation relocate(Download download, Path requestedDestination)
            throws IOException {
        Objects.requireNonNull(download, "download");
        Objects.requireNonNull(requestedDestination, "destination");
        Path oldDestination = Objects.requireNonNull(download.getDestination(),
                "download destination").toAbsolutePath().normalize();
        Path newDestination = requestedDestination.toAbsolutePath().normalize();
        Files.createDirectories(newDestination);

        List<Path> oldOutputs = download.getOutputPaths();
        List<Path> remappedOutputs = oldOutputs.stream()
                .map(path -> remap(path, oldDestination, newDestination))
                .toList();
        if (sameLocation(oldDestination, newDestination)) {
            return new Relocation(oldDestination, newDestination, oldOutputs,
                    List.of(), true);
        }

        List<Move> planned = planMoves(download, oldDestination, newDestination);
        for (Move move : planned) {
            if (Files.exists(move.target(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Destination already contains " + move.target());
            }
            if (Files.isDirectory(move.source(), LinkOption.NOFOLLOW_LINKS)
                    && move.target().startsWith(move.source())) {
                throw new IOException("Cannot move a download directory inside itself: "
                        + move.source());
            }
        }

        List<Move> completed = new ArrayList<>();
        try {
            for (Move move : planned) {
                movePath(move.source(), move.target());
                completed.add(move);
            }
        } catch (IOException moveFailure) {
            for (int i = completed.size() - 1; i >= 0; i--) {
                Move move = completed.get(i);
                try {
                    movePath(move.target(), move.source());
                } catch (IOException rollbackFailure) {
                    moveFailure.addSuppressed(rollbackFailure);
                }
            }
            throw moveFailure;
        }

        download.setDestination(newDestination);
        download.setOutputPaths(remappedOutputs);
        return new Relocation(oldDestination, newDestination, oldOutputs,
                List.copyOf(completed), false);
    }

    static Path remap(Path path, Path previousDestination, Path newDestination) {
        if (path == null) {
            return null;
        }
        Path absolute = path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : previousDestination.resolve(path).toAbsolutePath().normalize();
        return absolute.startsWith(previousDestination)
                ? newDestination.resolve(previousDestination.relativize(absolute)).normalize()
                : absolute;
    }

    private static List<Move> planMoves(Download download, Path oldDestination,
            Path newDestination) throws IOException {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        download.getOutputPaths().forEach(path -> addCandidate(candidates, path,
                oldDestination));
        addCandidate(candidates, download.getPrimaryOutputPath(), oldDestination);
        if (download.getName() != null && !download.getName().isBlank()) {
            org.manager.util.PathSafety.requireSafeFileName(download.getName());
            addCandidate(candidates, oldDestination.resolve(download.getName()),
                    oldDestination);
        }

        // Resume/control files are engine-owned companions of the payload.
        // Include exact sidecars and yt-dlp fragments without broad globs.
        List<Path> payloadCandidates = List.copyOf(candidates);
        for (Path payload : payloadCandidates) {
            if (!org.manager.util.PathSafety.isConfined(payload, oldDestination)) {
                continue;
            }
            addCandidate(candidates, Path.of(payload + ".aria2"), oldDestination);
            addCandidate(candidates, Path.of(payload + ".part"), oldDestination);
            addCandidate(candidates, Path.of(payload + ".ytdl"), oldDestination);
            Path parent = payload.getParent();
            Path name = payload.getFileName();
            if (parent != null && name != null && Files.isDirectory(parent)) {
                String fragmentPrefix = name + ".part-Frag";
                try (var entries = Files.list(parent)) {
                    entries.filter(path -> path.getFileName().toString()
                            .startsWith(fragmentPrefix))
                            .forEach(path -> addCandidate(candidates, path,
                                    oldDestination));
                }
            }
        }

        List<Path> existing = candidates.stream()
                .filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> org.manager.util.PathSafety.isConfined(
                        path, oldDestination))
                .sorted(Comparator.comparingInt(Path::getNameCount))
                .toList();
        List<Path> roots = new ArrayList<>();
        for (Path candidate : existing) {
            if (roots.stream().noneMatch(candidate::startsWith)) {
                roots.add(candidate);
            }
        }
        return roots.stream()
                .map(source -> new Move(source,
                        newDestination.resolve(oldDestination.relativize(source))))
                .toList();
    }

    private static void addCandidate(LinkedHashSet<Path> candidates, Path candidate,
            Path oldDestination) {
        if (candidate == null) {
            return;
        }
        Path absolute = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : oldDestination.resolve(candidate).toAbsolutePath().normalize();
        if (absolute.startsWith(oldDestination)) {
            candidates.add(absolute);
        }
    }

    private static boolean sameLocation(Path first, Path second) {
        if (first.equals(second)) {
            return true;
        }
        try {
            return Files.isSameFile(first, second);
        } catch (IOException e) {
            return false;
        }
    }

    private static void movePath(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException e) {
            // Continue with the regular move/cross-filesystem fallback.
        } catch (IOException e) {
            // EXDEV and providers without atomic moves also land here.
        }
        try {
            Files.move(source, target);
            return;
        } catch (IOException directMoveFailure) {
            copyThenDelete(source, target, directMoveFailure);
        }
    }

    private static void copyThenDelete(Path source, Path target,
            IOException directMoveFailure) throws IOException {
        Path tempTarget = target.resolveSibling(target.getFileName()
                + ".odm-relocating-" + UUID.randomUUID());
        try {
            copyRecursively(source, tempTarget);
            try {
                Files.move(tempTarget, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempTarget, target);
            }
        } catch (IOException copyFailure) {
            copyFailure.addSuppressed(directMoveFailure);
            try {
                deleteRecursively(tempTarget);
            } catch (IOException cleanupFailure) {
                copyFailure.addSuppressed(cleanupFailure);
            }
            throw copyFailure;
        }
        try {
            deleteRecursively(source);
        } catch (IOException cleanupFailure) {
            // The complete target is already durable. Preserve both copies
            // rather than deleting the only known-complete one after a
            // partial source cleanup; the model safely continues at target.
            LOGGER.warn("Moved download data to " + target
                    + " but could not fully remove the old copy at " + source,
                    cleanupFailure);
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, copyOptions());
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir,
                    BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attributes) throws IOException {
                Path copy = target.resolve(source.relativize(file));
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy, copyOptions());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static CopyOption[] copyOptions() {
        return new CopyOption[]{StandardCopyOption.COPY_ATTRIBUTES,
            LinkOption.NOFOLLOW_LINKS};
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error)
                    throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
