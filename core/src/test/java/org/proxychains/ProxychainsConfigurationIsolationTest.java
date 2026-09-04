package org.proxychains;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.aria2.Aria2GlobalOptions;
import org.aria2.Aria2Settings;

@DisplayName("proxychains-routed aria2 configuration isolation")
class ProxychainsConfigurationIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void routedAria2IgnoresExternalConfigurationByDefault() {
        ProxychainsClient client = new ProxychainsClient("/bin/true", null);
        try {
            List<String> command = client.buildProxychainsCommand(
                    download(), tempDir.resolve("payload.bin"), null, Map.of());

            assertTrue(command.contains("--no-conf"));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void routedAria2HonorsExternalConfigurationWhenEnabled() {
        ProxychainsClient client = new ProxychainsClient("/bin/true", null, true);
        try {
            List<String> command = client.buildProxychainsCommand(
                    download(), tempDir.resolve("payload.bin"), null, Map.of());

            assertFalse(command.contains("--no-conf"));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void routedAria2ForwardsPoliciesButForcesLeakyDiscoveryOff() {
        ProxychainsClient client = new ProxychainsClient("/bin/true", null);
        try {
            client.setTorrentListenPorts("51413-51420");
            Download download = new Download(URI.create("sftp://files.example.test/payload.bin"));
            download.setName("payload.bin");
            download.setDestination(tempDir);
            Aria2Settings aria2 = new Aria2Settings()
                    .setSeedingPolicy(Aria2GlobalOptions.SeedingPolicy.RATIO, 1.5, 60)
                    .setPeerExchange(Aria2GlobalOptions.ToggleOverride.ENABLED)
                    .setLocalPeerDiscovery(Aria2GlobalOptions.ToggleOverride.ENABLED)
                    .setEncryptionPolicy(
                            Aria2GlobalOptions.EncryptionPolicy.REQUIRE_ENCRYPTED_PAYLOAD)
                    .setSshHostKeyDigest(
                            "sha-1=0123456789abcdef0123456789abcdef01234567");
            download.setSettings(aria2);

            List<String> command = client.buildProxychainsCommand(
                    download, tempDir.resolve("payload.bin"), null, Map.of());

            assertTrue(command.contains("--seed-ratio=1.5"));
            assertTrue(command.contains("--ssh-host-key-md="
                    + "sha-1=0123456789abcdef0123456789abcdef01234567"));
            assertTrue(command.contains("--bt-require-crypto=true"));
            assertTrue(command.contains("--bt-min-crypto-level=arc4"));
            assertTrue(command.contains("--listen-port=51413-51420"));
            assertTrue(command.contains("--dht-listen-port=51413-51420"));
            assertTrue(command.lastIndexOf("--enable-peer-exchange=false")
                    > command.indexOf("--enable-peer-exchange=true"));
            assertTrue(command.lastIndexOf("--bt-enable-lpd=false")
                    > command.indexOf("--bt-enable-lpd=true"));
            assertTrue(command.contains("--enable-dht=false"));
            assertTrue(command.contains("--enable-dht6=false"));
        } finally {
            client.shutdown();
        }
    }

    private Download download() {
        Download download = new Download(URI.create("https://example.test/payload.bin"));
        download.setName("payload.bin");
        download.setDestination(tempDir);
        return download;
    }
}
