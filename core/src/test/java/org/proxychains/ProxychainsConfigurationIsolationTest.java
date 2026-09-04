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

    private Download download() {
        Download download = new Download(URI.create("https://example.test/payload.bin"));
        download.setName("payload.bin");
        download.setDestination(tempDir);
        return download;
    }
}
