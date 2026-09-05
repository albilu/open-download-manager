package org.odm.gtk4;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.manager.download.Download;
import org.manager.download.DownloadNetworkCapabilities;
import org.manager.download.DownloadOperations;
import org.manager.download.DownloadSettings;
import org.manager.download.ExternalToolSettings;

/**
 * GTK-free batch seam for applying one properties-dialog snapshot to a
 * selection of downloads.
 */
final class PropertySettingsBatch {

    private PropertySettingsBatch() {
    }

    record Values(int maxConnections, int downloadLimitKb, int uploadLimitKb,
            int maxRetries, int retryDelaySeconds, String referer,
            String userAgent, String cookie, boolean torActive,
            int proxyTypeIndex, String proxyHost, int proxyPort,
            String proxyUsername, String proxyPassword,
            Set<ExternalToolSettings.Capability> changedCapabilities,
            boolean proxyChanged) {

        Values {
            changedCapabilities = changedCapabilities == null
                    ? Set.of() : Set.copyOf(changedCapabilities);
        }

        Values(int maxConnections, int downloadLimitKb, int uploadLimitKb,
                int maxRetries, int retryDelaySeconds, String referer,
                String userAgent, String cookie, boolean torActive,
                int proxyTypeIndex, String proxyHost, int proxyPort,
                String proxyUsername, String proxyPassword) {
            this(maxConnections, downloadLimitKb, uploadLimitKb, maxRetries,
                    retryDelaySeconds, referer, userAgent, cookie, torActive,
                    proxyTypeIndex, proxyHost, proxyPort, proxyUsername, proxyPassword,
                    EnumSet.of(ExternalToolSettings.Capability.CONNECTIONS,
                            ExternalToolSettings.Capability.DOWNLOAD_LIMIT,
                            ExternalToolSettings.Capability.UPLOAD_LIMIT,
                            ExternalToolSettings.Capability.MAX_RETRIES,
                            ExternalToolSettings.Capability.RETRY_DELAY,
                            ExternalToolSettings.Capability.REFERER,
                            ExternalToolSettings.Capability.USER_AGENT,
                            ExternalToolSettings.Capability.COOKIE), true);
        }

        static Values defaults() {
            return new Values(0, 0, 0, 0, 0, "", "", "",
                    false, 0, "", 0, "", "", Set.of(), false);
        }
    }

    static EnumSet<ExternalToolSettings.Capability> commonCapabilities(
            List<Download> downloads) {
        EnumSet<ExternalToolSettings.Capability> common =
                EnumSet.allOf(ExternalToolSettings.Capability.class);
        List<Download> targets = distinctDownloads(downloads);
        if (targets.isEmpty()) {
            common.clear();
            return common;
        }
        for (Download download : targets) {
            EnumSet<ExternalToolSettings.Capability> available =
                    DownloadNetworkCapabilities.forDownload(download);
            common.retainAll(available);
        }
        return common;
    }

    static CompletableFuture<Void> apply(List<Download> downloads,
            DownloadOperations operations, Values values) {
        java.util.Objects.requireNonNull(operations, "operations");
        java.util.Objects.requireNonNull(values, "values");
        List<Download> targets = distinctDownloads(downloads);
        if (targets.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        EnumSet<ExternalToolSettings.Capability> capabilities =
                commonCapabilities(targets);
        capabilities.retainAll(values.changedCapabilities());
        if (capabilities.isEmpty() && !values.proxyChanged()) {
            return CompletableFuture.completedFuture(null);
        }

        List<Snapshot> snapshots = targets.stream()
                .map(Snapshot::capture).toList();
        try {
            for (Download download : targets) {
                DialogOptions.applyCommon(download.getSettings(), capabilities,
                        values.maxConnections(), values.downloadLimitKb(),
                        values.uploadLimitKb(), values.maxRetries(),
                        values.retryDelaySeconds(), values.referer(),
                        values.userAgent(), values.cookie());
                if (values.proxyChanged()) {
                    DialogOptions.applyProxy(download, values.torActive(),
                            values.proxyTypeIndex(), values.proxyHost(),
                            values.proxyPort(), values.proxyUsername(),
                            values.proxyPassword());
                }
            }
        } catch (RuntimeException failure) {
            // Validate and mutate the entire in-memory batch before notifying
            // any engine, so a bad value cannot leave an earlier target live
            // with only half of the requested selection applied.
            restore(snapshots);
            return CompletableFuture.failedFuture(failure);
        }

        List<CompletableFuture<Void>> updates = new ArrayList<>();
        for (Download download : targets) {
            try {
                CompletableFuture<Void> update = operations.changeSettings(download);
                updates.add(update == null ? CompletableFuture.completedFuture(null) : update);
            } catch (RuntimeException failure) {
                updates.add(CompletableFuture.failedFuture(failure));
                break;
            }
        }
        return CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new))
                .<CompletableFuture<Void>>handle((ignored, failure) -> {
                    if (failure == null) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    Throwable original = rootCause(failure);
                    restore(snapshots);
                    List<CompletableFuture<Void>> rollbacks = snapshots.stream()
                            .map(snapshot -> {
                                try {
                                    CompletableFuture<Void> rollback =
                                            operations.changeSettings(snapshot.download());
                                    return rollback == null
                                            ? CompletableFuture.<Void>completedFuture(null)
                                            : rollback;
                                } catch (RuntimeException rollbackFailure) {
                                    return CompletableFuture.<Void>failedFuture(rollbackFailure);
                                }
                            }).toList();
                    return CompletableFuture.allOf(
                                    rollbacks.toArray(CompletableFuture[]::new))
                            .<Void>handle((rolledBack, rollbackFailure) -> {
                                if (rollbackFailure != null) {
                                    original.addSuppressed(rootCause(rollbackFailure));
                                }
                                throw new CompletionException(original);
                            });
                }).thenCompose(result -> result);
    }

    private record Snapshot(Download download, DownloadSettings settings,
            Download.Type type) {
        static Snapshot capture(Download download) {
            return new Snapshot(download, download.getSettings().copy(), download.getType());
        }
    }

    private static void restore(List<Snapshot> snapshots) {
        for (Snapshot snapshot : snapshots) {
            snapshot.download().setSettings(snapshot.settings());
            snapshot.download().setType(snapshot.type());
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static List<Download> distinctDownloads(List<Download> downloads) {
        if (downloads == null || downloads.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Download> byId = new LinkedHashMap<>();
        for (Download download : downloads) {
            if (download != null) {
                byId.putIfAbsent(download.getId(), download);
            }
        }
        return List.copyOf(byId.values());
    }
}
