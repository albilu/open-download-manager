package org.aria2;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("aria2 global torrent policy mapping")
class Aria2GlobalOptionsTest {

    @Test
    void defaultStopsImmediatelyAndPreservesNativeConnectivityDefaults() {
        GlobalSettings global = new GlobalSettings();
        Aria2Settings aria2 = new Aria2Settings();

        Aria2GlobalOptions.applyDownloadOptions(global, aria2);

        assertEquals(Aria2GlobalOptions.SeedingPolicy.DISABLED,
                Aria2GlobalOptions.seedingPolicy(global));
        assertEquals("0", aria2.getOption("seed-time"));
        assertFalse(aria2.toRpcOptions().containsKey("enable-peer-exchange"));
        assertFalse(aria2.toRpcOptions().containsKey("bt-enable-lpd"));
        assertFalse(aria2.toRpcOptions().containsKey("bt-require-crypto"));
        assertTrue(Aria2GlobalOptions.daemonLaunchArguments(global, false).isEmpty());
    }

    @Test
    void everySeedingPolicyEmitsTheRequiredAria2Combination() {
        assertSeedOptions(Aria2GlobalOptions.SeedingPolicy.DISABLED,
                Map.of("seed-time", "0"));
        assertSeedOptions(Aria2GlobalOptions.SeedingPolicy.RATIO,
                Map.of("seed-ratio", "2.5"));
        assertSeedOptions(Aria2GlobalOptions.SeedingPolicy.TIME,
                Map.of("seed-ratio", "0.0", "seed-time", "90"));
        assertSeedOptions(Aria2GlobalOptions.SeedingPolicy.RATIO_OR_TIME,
                Map.of("seed-ratio", "2.5", "seed-time", "90"));
        assertSeedOptions(Aria2GlobalOptions.SeedingPolicy.UNLIMITED,
                Map.of("seed-ratio", "0.0"));
    }

    @Test
    void legacySeedingToggleUpgradesToTimeOnlyPolicy() {
        GlobalSettings global = new GlobalSettings();
        global.setProperty("aria2.enableSeeding", "true");
        global.setProperty(Aria2GlobalOptions.SEED_TIME_KEY, "45");
        Aria2Settings aria2 = new Aria2Settings();

        Aria2GlobalOptions.applyDownloadOptions(global, aria2);

        assertEquals(Aria2GlobalOptions.SeedingPolicy.TIME,
                Aria2GlobalOptions.seedingPolicy(global));
        assertEquals("0.0", aria2.getOption("seed-ratio"));
        assertEquals("45", aria2.getOption("seed-time"));
    }

    @Test
    void connectivityAndEncryptionOverridesReachTheirCorrectScopes() {
        GlobalSettings global = new GlobalSettings();
        global.setProperty(Aria2GlobalOptions.LISTEN_PORTS_KEY, " 51413 , 52000-52010 ");
        global.setProperty(Aria2GlobalOptions.IPV6_DHT_KEY, "enabled");
        global.setProperty(Aria2GlobalOptions.PEER_EXCHANGE_KEY, "disabled");
        global.setProperty(Aria2GlobalOptions.LOCAL_PEER_DISCOVERY_KEY, "enabled");
        global.setProperty(Aria2GlobalOptions.ENCRYPTION_POLICY_KEY, "require-payload");
        Aria2Settings aria2 = new Aria2Settings();

        Aria2GlobalOptions.applyDownloadOptions(global, aria2);
        List<String> daemon = Aria2GlobalOptions.daemonLaunchArguments(global, false);

        assertTrue(daemon.contains("--listen-port=51413,52000-52010"));
        assertTrue(daemon.contains("--dht-listen-port=51413,52000-52010"));
        assertTrue(daemon.contains("--enable-dht6=true"));
        assertEquals("false", aria2.getOption("enable-peer-exchange"));
        assertEquals("true", aria2.getOption("bt-enable-lpd"));
        assertEquals("true", aria2.getOption("bt-require-crypto"));
        assertEquals("arc4", aria2.getOption("bt-min-crypto-level"));
    }

    @Test
    void strictProxyRoutingWinsOverDiscoveryPreferences() {
        GlobalSettings global = new GlobalSettings();
        global.setProperty(Aria2GlobalOptions.IPV6_DHT_KEY, "enabled");
        global.setProperty(Aria2GlobalOptions.PEER_EXCHANGE_KEY, "enabled");
        global.setProperty(Aria2GlobalOptions.LOCAL_PEER_DISCOVERY_KEY, "enabled");

        List<String> arguments = Aria2GlobalOptions.daemonLaunchArguments(global, true);

        assertTrue(arguments.contains("--enable-dht=false"));
        assertTrue(arguments.contains("--enable-dht6=false"));
        assertTrue(arguments.contains("--enable-peer-exchange=false"));
        assertTrue(arguments.contains("--bt-enable-lpd=false"));
        assertFalse(arguments.contains("--enable-dht6=true"));
    }

    @Test
    void listenPortSyntaxIsValidatedBeforeItCanReachAria2() {
        assertEquals("6881-6999,51413",
                Aria2GlobalOptions.normalizeListenPorts(" 6881 - 6999, 51413 "));
        assertThrows(IllegalArgumentException.class,
                () -> Aria2GlobalOptions.normalizeListenPorts("6999-6881"));
        assertThrows(IllegalArgumentException.class,
                () -> Aria2GlobalOptions.normalizeListenPorts("80"));
        assertThrows(IllegalArgumentException.class,
                () -> Aria2GlobalOptions.normalizeListenPorts("6881;--rpc-listen-all=true"));
    }

    private static void assertSeedOptions(Aria2GlobalOptions.SeedingPolicy policy,
            Map<String, String> expected) {
        Aria2Settings aria2 = new Aria2Settings()
                .setSeedingPolicy(policy, 2.5, 90);
        Map<String, Object> actual = aria2.toRpcOptions();

        assertEquals(expected.get("seed-ratio"), actual.get("seed-ratio"), policy.toString());
        assertEquals(expected.get("seed-time"), actual.get("seed-time"), policy.toString());
        assertEquals(expected.containsKey("seed-ratio"), actual.containsKey("seed-ratio"));
        assertEquals(expected.containsKey("seed-time"), actual.containsKey("seed-time"));
    }
}
