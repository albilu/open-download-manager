package org.manager.download;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.manager.util.PathSafety;

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
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String extension = dot > 0 ? base.substring(dot) : "";
        for (int attempt = 1;; attempt++) {
            if (attempt > 999_999) throw new IllegalStateException("Too many colliding output names for " + base);
            // Underscore (not DescriptorStaging's dash): File Control uniquify convention.
            String candidate = stem + "_" + attempt + extension;
            PathSafety.requireSafeFileName(candidate);
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
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
    public static boolean applyTo(Download download, Collection<Download> others,
            boolean overrideClearsDisk) {
        Objects.requireNonNull(download, "download");
        synchronized (CLAIM_LOCK) {
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
                if (liveClaimed.contains(candidate)) {
                    return true;
                }
                if (overrideClearsDisk) {
                    return false;
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
}
