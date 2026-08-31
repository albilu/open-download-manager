package org.odm.gtk4;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.manager.download.Download;
import org.manager.download.DownloadOperations;
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
            String proxyUsername, String proxyPassword) {

        static Values defaults() {
            return new Values(0, 0, 0, 0, 0, "", "", "",
                    false, 0, "", 0, "", "");
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
            ExternalToolSettings settings = download.getSettings();
            common.removeIf(capability -> settings == null
                    || !settings.supports(capability));
        }
        return common;
    }

    static CompletableFuture<Void> apply(List<Download> downloads,
            DownloadOperations operations, Values values) {
        java.util.Objects.requireNonNull(operations, "operations");
        java.util.Objects.requireNonNull(values, "values");
        List<CompletableFuture<Void>> updates = new ArrayList<>();
        try {
            List<Download> targets = distinctDownloads(downloads);
            EnumSet<ExternalToolSettings.Capability> capabilities =
                    commonCapabilities(targets);
            for (Download download : targets) {
                DialogOptions.applyCommon(download.getSettings(), capabilities,
                        values.maxConnections(), values.downloadLimitKb(),
                        values.uploadLimitKb(), values.maxRetries(),
                        values.retryDelaySeconds(), values.referer(),
                        values.userAgent(), values.cookie());
                DialogOptions.applyProxy(download, values.torActive(),
                        values.proxyTypeIndex(), values.proxyHost(),
                        values.proxyPort(), values.proxyUsername(),
                        values.proxyPassword());
                CompletableFuture<Void> update = operations.changeSettings(download);
                updates.add(update == null ? CompletableFuture.completedFuture(null) : update);
            }
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new));
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
