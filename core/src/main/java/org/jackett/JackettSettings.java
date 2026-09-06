package org.jackett;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.manager.GlobalSettings;
import org.manager.util.OdmPaths;

/** ODM preferences; Jackett retains its own API key and indexer configuration. */
public final class JackettSettings {
    public static final String PORT = "jackett.port";
    public static final String START_WITH_ODM = "jackett.startWithOdm";
    public static final String INDEXERS = "jackett.indexers";

    private JackettSettings() { }

    public static Path installationDirectory() {
        return OdmPaths.dataDirectory().resolve("jackett");
    }

    public static Path configurationDirectory() {
        return OdmPaths.configDirectory().resolve("jackett");
    }

    public static int port(GlobalSettings settings) {
        int port = settings.getIntProperty(PORT, 9117);
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Jackett port must be between 1024 and 65535");
        }
        return port;
    }

    /** An explicitly empty selection must never expand to all indexers. */
    public static Set<String> selectedIndexers(GlobalSettings settings,
            List<JackettClient.Indexer> available) {
        String saved = settings.getProperty(INDEXERS, null);
        Set<String> requested = saved == null ? null
                : new LinkedHashSet<>(Arrays.asList(saved.split(",")));
        Set<String> result = new LinkedHashSet<>();
        for (JackettClient.Indexer indexer : available) {
            if (indexer.configured() && (requested == null || requested.contains(indexer.id()))) {
                result.add(indexer.id());
            }
        }
        return result;
    }
}
