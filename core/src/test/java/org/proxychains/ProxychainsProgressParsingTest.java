package org.proxychains;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Proxychains aria2 progress parsing")
class ProxychainsProgressParsingTest {

    @Test
    @DisplayName("extracts UL while a torrent is downloading")
    void extractsDownloadingUploadSpeed() {
        String line = "[#abc123 1.0MiB/2.0MiB(50%) CN:4 SD:2 "
                + "DL:512KiB UL:256KiB ETA:2s]";

        assertEquals(256 * 1024f, ProxychainsClient.parseUploadSpeed(line));
    }

    @Test
    @DisplayName("extracts UL from seeding-only summaries")
    void extractsSeedingUploadSpeed() {
        String line = "[#abc123 SEED(1.5) CN:3 SD:2 UL:1.5MiB(2.0)]";

        assertEquals(1.5f * 1024 * 1024, ProxychainsClient.parseUploadSpeed(line));
    }

    @Test
    @DisplayName("distinguishes a missing UL field from a reported zero rate")
    void distinguishesMissingAndZeroUploadSpeed() {
        assertTrue(Float.isNaN(ProxychainsClient.parseUploadSpeed(
                "[#abc123 1MiB/2MiB(50%) CN:1 DL:512KiB ETA:2s]")));
        assertEquals(0f, ProxychainsClient.parseUploadSpeed(
                "[#abc123 SEED(0.0) CN:0 SD:0 UL:0B(0)]"));
    }
}
