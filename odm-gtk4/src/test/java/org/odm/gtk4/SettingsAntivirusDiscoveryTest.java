package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.antivirus.AntivirusToolManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.tools.ToolManagerFactory;

class SettingsAntivirusDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void dropdownChoicesContainOnlyValidatedScannersPlusCustom() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        ToolManagerFactory factory = new ToolManagerFactory(settings, tempDir);
        try {
            AntivirusToolManager clamav = factory.getAntivirusManager("clamav");
            Path valid = tempDir.resolve("clamscan");
            Files.writeString(valid, "#!/bin/sh\necho 'ClamAV 1.4.2'\nexit 0\n");
            Files.setPosixFilePermissions(valid,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            clamav.setToolPath(valid.toString());

            for (String key : java.util.List.of("chkrootkit", "rkhunter")) {
                AntivirusToolManager broken = factory.getAntivirusManager(key);
                Path invalid = tempDir.resolve(key);
                Files.writeString(invalid, "#!/bin/sh\nexit 3\n");
                Files.setPosixFilePermissions(invalid,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                broken.setToolPath(invalid.toString());
            }

            var choices = SettingsDialog.discoverAvailableAntiviruses(factory).join();
            assertEquals(java.util.List.of("clamav", "custom"),
                    choices.stream().map(SettingsDialog.AntivirusChoice::key).toList());
        } finally {
            factory.cleanup();
        }
    }
}
