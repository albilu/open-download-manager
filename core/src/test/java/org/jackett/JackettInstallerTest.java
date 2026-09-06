package org.jackett;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JackettInstallerTest {
    @TempDir Path directory;

    private Path archive(String name, byte type) throws Exception {
        Path archive = Files.createTempFile(directory, "release", ".tar.gz");
        try (var tar = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(archive)))) {
            TarArchiveEntry entry = new TarArchiveEntry(name, type);
            if (type == TarConstants.LF_NORMAL) { entry.setSize(4); }
            else { entry.setLinkName("/tmp/elsewhere"); }
            tar.putArchiveEntry(entry);
            if (type == TarConstants.LF_NORMAL) { tar.write(new byte[]{1, 2, 3, 4}); }
            tar.closeArchiveEntry();
        }
        return archive;
    }

    @Test void stagedInstallationIsExecutableAndReplacementPreservesConfig() throws Exception {
        Path install = directory.resolve("installation");
        JackettInstaller installer = new JackettInstaller();
        Path archive = archive("Jackett/jackett", TarConstants.LF_NORMAL);
        installer.installArchive(archive, install, "v1");
        assertTrue(Files.isExecutable(install.resolve("Jackett/jackett")));
        assertEquals("v1", Files.readString(install.resolve("Jackett/odm-version")));
        Path config = Files.writeString(directory.resolve("ServerConfig.json"), "keep");
        installer.installArchive(archive, install, "v2");
        assertEquals("v2", Files.readString(install.resolve("Jackett/odm-version")));
        assertEquals("keep", Files.readString(config));
        try (var paths = Files.list(install)) { assertEquals(1, paths.count()); }
    }

    @Test void unsafeArchivesNeverReplaceTheInstalledExecutable() throws Exception {
        Path install = directory.resolve("installation");
        JackettInstaller installer = new JackettInstaller();
        installer.installArchive(archive("Jackett/jackett", TarConstants.LF_NORMAL), install, "original");
        for (String name : new String[]{"Jackett/../../outside", "/outside", "../outside", "other/jackett"}) {
            Path bad = archive(name, TarConstants.LF_NORMAL);
            assertThrows(IOException.class, () -> installer.installArchive(bad, install, "bad"));
        }
        for (byte type : new byte[]{TarConstants.LF_SYMLINK, TarConstants.LF_LINK}) {
            Path bad = archive("Jackett/jackett", type);
            assertThrows(IOException.class, () -> installer.installArchive(bad, install, "bad"));
        }
        assertEquals("original", Files.readString(install.resolve("Jackett/odm-version")));
        assertFalse(Files.exists(directory.resolve("outside")));
        assertThrows(IOException.class, () -> installer.installArchive(archive("Jackett/other", TarConstants.LF_NORMAL), install, "bad"));
    }

    @Test void releaseArchitectureAndChecksumAreValidated() throws Exception {
        assertEquals("Jackett.Binaries.LinuxAMDx64.tar.gz", JackettInstaller.assetName("Linux", "amd64", false));
        assertEquals("Jackett.Binaries.LinuxMuslARM64.tar.gz", JackettInstaller.assetName("Linux", "aarch64", true));
        assertEquals("Jackett.Binaries.LinuxARM32.tar.gz", JackettInstaller.assetName("Linux", "armv7l", false));
        assertThrows(IOException.class, () -> JackettInstaller.assetName("Linux", "riscv64", false));
        assertThrows(IOException.class, () -> JackettInstaller.assetName("Windows", "amd64", false));
        Path archive = archive("Jackett/jackett", TarConstants.LF_NORMAL);
        assertThrows(IOException.class, () -> JackettInstaller.verifyDigest(archive, "0".repeat(64)));
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
        JackettInstaller.verifyDigest(archive, digest);
    }
}
