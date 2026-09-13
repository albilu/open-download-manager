package org.manager.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.manager.util.PathSafety;
import org.ytdlp.YtDlpSettings;

/**
 * Collision-free output naming. Pure logic: name math in
 * {@link #uniquifiedName}, record/filesystem arbitration in
 * {@link #applyTo}.
 */
public final class OutputNameUniquifier {

    private static final Set<Download.Status> LIVE_STATUSES = EnumSet.of(
            Download.Status.CREATED,
            Download.Status.STARTING,
            Download.Status.QUEUED,
            Download.Status.CONNECTING,
            Download.Status.DOWNLOADING,
            Download.Status.SEEDING,
            Download.Status.PAUSED);

    /** Serializes claim snapshots with stamping: two racing starters can never pick the same name. */
    private static final Object CLAIM_LOCK = new Object();

    private OutputNameUniquifier() {
    }

    /**
     * Returns {@code baseName} when free, else {@code stem_1.ext},
     * {@code stem_2.ext}, … Splits at the last dot; leading-dot names such as
     * {@code .profile} count as extensionless.
     */
    public static String uniquifiedName(String baseName, Predicate<String> taken) {
        String base = baseName == null ? null : baseName.strip();
        if (base == null || base.isEmpty()) {
            throw new IllegalArgumentException("Base file name is required");
        }
        PathSafety.requireSafeFileName(base);
        if (!taken.test(base)) {
            return base;
        }
        for (int attempt = 1;; attempt++) {
            if (attempt > 999_999) throw new IllegalStateException("Too many colliding output names for " + base);
            // Underscore (not DescriptorStaging's dash): File Control uniquify convention.
            String candidate = numberedName(base, attempt);
            PathSafety.requireSafeFileName(candidate);
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }

    private static String numberedName(String name, int counter) {
        PathSafety.requireSafeFileName(name);
        if (counter == 0) {
            return name;
        }
        String stem = stem(name);
        return stem + "_" + counter + name.substring(stem.length());
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Stamps a collision-free {@code requestedFileName} (and matching display
     * name) onto a fresh record. Returns true when a suffix was applied.
     * The whole check-and-stamp runs under one static lock. Must never be
     * called while holding a record monitor; the manager hook runs it outside
     * Download's lock, keeping the global order claim lock -> record lock.
     * The claim lock serializes in-process claimants only; a file created
     * externally between check and engine open is still possible (accepted
     * residual risk, documented in spec §3); aria2's native rename remains
     * its backstop.
     */
    public static boolean applyTo(Download download, Collection<Download> others) {
        Objects.requireNonNull(download, "download");
        synchronized (CLAIM_LOCK) {
            if (download.getType() == Download.Type.YOUTUBE && download.getRequestedFileName() == null) {
                return false; // A media page's display name is not its output filename.
            }
            if (download.getDestination() == null) {
                return false;
            }
            Path destination = download.getDestination().toAbsolutePath().normalize();
            String raw = download.getRequestedFileName() != null
                    ? download.getRequestedFileName() : download.getName();
            String base = raw == null ? null : raw.strip();
            if (base == null || base.isEmpty()) {
                return false;
            }
            Set<String> liveClaimed = new HashSet<>();
            Set<String> mediaStems = new HashSet<>();
            if (others != null) {
                for (Download other : others) {
                    if (other == null || Objects.equals(other.getId(), download.getId())) {
                        continue;
                    }
                    if (!LIVE_STATUSES.contains(other.getStatus())) {
                        continue;
                    }
                    Path otherDestination = other.getDestination();
                    if (otherDestination == null
                            || !otherDestination.toAbsolutePath().normalize().equals(destination)) {
                        continue;
                    }
                    String otherBase = other.getRequestedFileName() != null
                            ? other.getRequestedFileName() : other.getName();
                    if (otherBase != null && !otherBase.isBlank()) {
                        liveClaimed.add(otherBase.strip());
                    }
                    if (other.getSettings() instanceof YtDlpSettings media) {
                        liveClaimed.addAll(media.getReservedOutputNames());
                        media.getReservedOutputNames().stream().map(OutputNameUniquifier::stem)
                                .forEach(mediaStems::add);
                    }
                    for (Path reported : other.getOutputPaths()) {
                        Path normalized = reported.toAbsolutePath().normalize();
                        if (normalized.getParent() != null && normalized.getParent().equals(destination)
                                && normalized.getFileName() != null) {
                            liveClaimed.add(normalized.getFileName().toString());
                        }
                    }
                }
            }
            Set<Path> ownOutputs = new HashSet<>();
            for (Path reported : download.getOutputPaths()) {
                ownOutputs.add(reported.toAbsolutePath().normalize());
            }
            Predicate<String> taken = candidate -> {
                // A paused conversion also owns its future extension (for
                // example clip.webm -> clip.mp3) before that file exists.
                if (liveClaimed.contains(candidate) || mediaStems.stream()
                        .anyMatch(baseName -> candidate.equals(baseName) || candidate.startsWith(baseName + "."))) {
                    return true;
                }
                Path candidatePath = destination.resolve(candidate).toAbsolutePath().normalize();
                return !ownOutputs.contains(candidatePath)
                        && Files.exists(candidatePath, LinkOption.NOFOLLOW_LINKS);
            };
            String unique = uniquifiedName(base, taken);
            if (unique.equals(base)) {
                return false;
            }
            download.setRequestedFileName(unique);
            download.setName(unique);
            return true;
        }
    }

    /**
     * Reserves yt-dlp's extracted names together, before any media or fragment
     * is opened. A common suffix keeps playlist templates and native extension
     * selection intact. The whole basename family is reserved because merging,
     * audio extraction and sidecars can use extensions absent from metadata.
     */
    public static void applyToMedia(Download download, Collection<Download> others,
            List<String> resolvedNames) {
        synchronized (CLAIM_LOCK) {
            if (!(download.getSettings() instanceof YtDlpSettings settings)
                    || download.getDestination() == null || resolvedNames.isEmpty()) {
                return;
            }
            resolvedNames.forEach(PathSafety::requireSafeFileName);
            Path destination = download.getDestination().toAbsolutePath().normalize();
            Set<String> taken = new HashSet<>();
            for (Download other : others) {
                if (other == null || Objects.equals(other.getId(), download.getId())
                        || !LIVE_STATUSES.contains(other.getStatus()) || other.getDestination() == null
                        || !destination.equals(other.getDestination().toAbsolutePath().normalize())) {
                    continue;
                }
                if (other.getSettings() instanceof YtDlpSettings media) {
                    taken.addAll(media.getReservedOutputNames());
                }
                String explicit = other.getRequestedFileName();
                if (explicit != null) {
                    taken.add(explicit);
                } else if (other.getType() != Download.Type.YOUTUBE && other.getName() != null) {
                    taken.add(other.getName());
                }
                for (Path output : other.getOutputPaths()) {
                    Path normalized = output.toAbsolutePath().normalize();
                    if (destination.equals(normalized.getParent())) {
                        taken.add(normalized.getFileName().toString());
                    }
                }
            }
            if (Files.isDirectory(destination)) {
                try (var files = Files.list(destination)) {
                    files.forEach(path -> taken.add(path.getFileName().toString()));
                } catch (IOException error) {
                    throw new IllegalStateException("Could not check media output names", error);
                }
            }
            for (int counter = 0; counter <= 999_999; counter++) {
                final int suffix = counter;
                List<String> candidates = resolvedNames.stream()
                        .map(name -> numberedName(name, suffix)).distinct().toList();
                boolean collision = candidates.stream().map(OutputNameUniquifier::stem)
                        .anyMatch(base -> taken.stream().anyMatch(name -> name.equals(base)
                                || name.startsWith(base + ".")));
                if (collision) {
                    continue;
                }
                settings.setReservedOutputNames(candidates);
                if (settings.getOutputTemplate() != null) {
                    String literal = numberedName(settings.getOutputTemplate(), counter);
                    settings.setOutputTemplate(literal);
                    if (download.getRequestedFileName() != null) {
                        download.setRequestedFileName(literal);
                    }
                } else {
                    settings.setOutputNameCounter(counter);
                }
                if (candidates.size() == 1) {
                    download.setName(candidates.getFirst());
                }
                return;
            }
            throw new IllegalStateException("Too many colliding media output names");
        }
    }
}
