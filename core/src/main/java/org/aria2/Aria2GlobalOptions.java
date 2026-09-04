package org.aria2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.manager.GlobalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canonical mapping between ODM's aria2 preferences and aria2-native options.
 * Download-scoped options are copied into each {@link Aria2Settings} snapshot;
 * daemon-scoped options are returned as launch arguments for the ODM-owned RPC
 * process. Keeping the two scopes separate prevents invalid addUri options and
 * makes settings behavior identical in the core factory and GTK preferences.
 */
public final class Aria2GlobalOptions {

    private static final Logger LOGGER = LoggerFactory.getLogger(Aria2GlobalOptions.class);

    public static final String SEEDING_POLICY_KEY = "aria2.seedingPolicy";
    public static final String SEED_RATIO_KEY = "aria2.seedRatio";
    public static final String SEED_TIME_KEY = "aria2.seedTimeMin";
    public static final String LISTEN_PORTS_KEY = "aria2.listenPorts";
    public static final String IPV6_DHT_KEY = "aria2.ipv6Dht";
    public static final String PEER_EXCHANGE_KEY = "aria2.peerExchange";
    public static final String LOCAL_PEER_DISCOVERY_KEY = "aria2.localPeerDiscovery";
    public static final String ENCRYPTION_POLICY_KEY = "aria2.encryptionPolicy";

    public static final double DEFAULT_SEED_RATIO = 1.0;
    public static final int DEFAULT_SEED_TIME_MINUTES = 60;

    /** Conditions under which a completed torrent stops seeding. */
    public enum SeedingPolicy {
        DISABLED("disabled"),
        RATIO("ratio"),
        TIME("time"),
        RATIO_OR_TIME("ratio-or-time"),
        UNLIMITED("unlimited");

        private final String settingValue;

        SeedingPolicy(String settingValue) {
            this.settingValue = settingValue;
        }

        public String settingValue() {
            return settingValue;
        }

        public static SeedingPolicy fromSetting(String value, SeedingPolicy fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            String normalized = value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
            for (SeedingPolicy policy : values()) {
                if (policy.settingValue.equals(normalized)) {
                    return policy;
                }
            }
            return fallback;
        }
    }

    /** Optional boolean override; ENGINE_DEFAULT deliberately emits no flag. */
    public enum ToggleOverride {
        ENGINE_DEFAULT("default"),
        ENABLED("enabled"),
        DISABLED("disabled");

        private final String settingValue;

        ToggleOverride(String settingValue) {
            this.settingValue = settingValue;
        }

        public String settingValue() {
            return settingValue;
        }

        public static ToggleOverride fromSetting(String value) {
            if (value == null || value.isBlank()) {
                return ENGINE_DEFAULT;
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "enabled", "true" -> ENABLED;
                case "disabled", "false" -> DISABLED;
                default -> ENGINE_DEFAULT;
            };
        }
    }

    /** BitTorrent handshake/payload encryption requirement. */
    public enum EncryptionPolicy {
        ENGINE_DEFAULT("default"),
        REQUIRE_OBFUSCATED_HANDSHAKE("require-handshake"),
        REQUIRE_ENCRYPTED_PAYLOAD("require-payload");

        private final String settingValue;

        EncryptionPolicy(String settingValue) {
            this.settingValue = settingValue;
        }

        public String settingValue() {
            return settingValue;
        }

        public static EncryptionPolicy fromSetting(String value) {
            if (value == null || value.isBlank()) {
                return ENGINE_DEFAULT;
            }
            String normalized = value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
            for (EncryptionPolicy policy : values()) {
                if (policy.settingValue.equals(normalized)) {
                    return policy;
                }
            }
            return ENGINE_DEFAULT;
        }
    }

    private Aria2GlobalOptions() {
    }

    /** Reads the current policy, retaining the former on/off setting on upgrade. */
    public static SeedingPolicy seedingPolicy(GlobalSettings settings) {
        String configured = settings.getProperty(SEEDING_POLICY_KEY, null);
        if (configured == null) {
            return settings.getBooleanProperty("aria2.enableSeeding", false)
                    ? SeedingPolicy.TIME : SeedingPolicy.DISABLED;
        }
        return SeedingPolicy.fromSetting(configured, SeedingPolicy.DISABLED);
    }

    public static double seedRatio(GlobalSettings settings) {
        return positiveDouble(settings.getProperty(SEED_RATIO_KEY, null), DEFAULT_SEED_RATIO);
    }

    public static double seedTimeMinutes(GlobalSettings settings) {
        return positiveDouble(settings.getProperty(SEED_TIME_KEY, null),
                DEFAULT_SEED_TIME_MINUTES);
    }

    public static ToggleOverride ipv6Dht(GlobalSettings settings) {
        return ToggleOverride.fromSetting(settings.getProperty(IPV6_DHT_KEY, null));
    }

    public static ToggleOverride peerExchange(GlobalSettings settings) {
        return ToggleOverride.fromSetting(settings.getProperty(PEER_EXCHANGE_KEY, null));
    }

    public static ToggleOverride localPeerDiscovery(GlobalSettings settings) {
        return ToggleOverride.fromSetting(
                settings.getProperty(LOCAL_PEER_DISCOVERY_KEY, null));
    }

    public static EncryptionPolicy encryptionPolicy(GlobalSettings settings) {
        return EncryptionPolicy.fromSetting(
                settings.getProperty(ENCRYPTION_POLICY_KEY, null));
    }

    /** Applies preferences that aria2 accepts in addUri/addTorrent options. */
    public static void applyDownloadOptions(GlobalSettings source, Aria2Settings target) {
        target.setSeedingPolicy(seedingPolicy(source), seedRatio(source),
                seedTimeMinutes(source));
        target.setPeerExchange(peerExchange(source));
        target.setLocalPeerDiscovery(localPeerDiscovery(source));
        target.setEncryptionPolicy(encryptionPolicy(source));
    }

    /**
     * Builds options that must configure the daemon rather than one GID.
     * Blank/default preferences are omitted, allowing aria2's native defaults
     * (or an explicitly honored external configuration) to remain in force.
     */
    public static List<String> daemonLaunchArguments(GlobalSettings settings,
            boolean strictProxyRouting) {
        List<String> arguments = new ArrayList<>();
        String listenPorts = configuredListenPorts(settings);
        if (!listenPorts.isEmpty()) {
            arguments.add("--listen-port=" + listenPorts);
            // Use the same user-facing range for TCP peers and UDP DHT/trackers.
            arguments.add("--dht-listen-port=" + listenPorts);
        }

        if (strictProxyRouting) {
            // DHT, PEX, and LPD do not obey a TCP proxy and could disclose the
            // local address. Proxy-routed downloads therefore override them.
            arguments.add("--enable-dht=false");
            arguments.add("--enable-dht6=false");
            arguments.add("--enable-peer-exchange=false");
            arguments.add("--bt-enable-lpd=false");
            return List.copyOf(arguments);
        }

        addToggleArgument(arguments, "enable-dht6", ipv6Dht(settings));
        addToggleArgument(arguments, "enable-peer-exchange", peerExchange(settings));
        addToggleArgument(arguments, "bt-enable-lpd", localPeerDiscovery(settings));
        addEncryptionArguments(arguments, encryptionPolicy(settings));
        return List.copyOf(arguments);
    }

    /** Validated configured range, or blank when native defaults should apply. */
    public static String configuredListenPorts(GlobalSettings settings) {
        try {
            return normalizeListenPorts(settings.getProperty(LISTEN_PORTS_KEY, ""));
        } catch (IllegalArgumentException invalid) {
            LOGGER.warn("Ignoring invalid aria2 torrent listen-port setting", invalid);
            return "";
        }
    }

    /**
     * Validates aria2's comma/range syntax and returns a compact canonical
     * value. Empty input means "use the engine default".
     */
    public static String normalizeListenPorts(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = value.replaceAll("\\s+", "");
        if (!compact.matches("[0-9]+(?:-[0-9]+)?(?:,[0-9]+(?:-[0-9]+)?)*")) {
            throw new IllegalArgumentException(
                    "Torrent listen ports must be a port or range such as 6881-6999");
        }
        for (String item : compact.split(",")) {
            String[] bounds = item.split("-", -1);
            int first = parsePort(bounds[0]);
            int last = bounds.length == 2 ? parsePort(bounds[1]) : first;
            if (first > last) {
                throw new IllegalArgumentException(
                        "Torrent listen-port ranges must start with the lower port");
            }
        }
        return compact;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1_024 || port > 65_535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "Torrent listen ports must be between 1024 and 65535");
        }
    }

    private static double positiveDouble(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static void addToggleArgument(List<String> target, String option,
            ToggleOverride override) {
        if (override != ToggleOverride.ENGINE_DEFAULT) {
            target.add("--" + option + "=" + (override == ToggleOverride.ENABLED));
        }
    }

    private static void addEncryptionArguments(List<String> target,
            EncryptionPolicy policy) {
        switch (policy) {
            case ENGINE_DEFAULT -> {
                // Deliberately preserve aria2's own policy.
            }
            case REQUIRE_OBFUSCATED_HANDSHAKE -> {
                target.add("--bt-require-crypto=true");
                target.add("--bt-min-crypto-level=plain");
            }
            case REQUIRE_ENCRYPTED_PAYLOAD -> {
                target.add("--bt-require-crypto=true");
                target.add("--bt-min-crypto-level=arc4");
            }
        }
    }
}
