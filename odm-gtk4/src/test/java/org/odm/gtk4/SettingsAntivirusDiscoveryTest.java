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
    void dropdownChoicesContainAutomaticValidatedScannersAndCustom() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        ToolManagerFactory factory = new ToolManagerFactory(settings, tempDir);
        try {
            AntivirusToolManager clamav = factory.getAntivirusManager("clamav");
            Path valid = tempDir.resolve("clamscan");
            Files.writeString(valid, "#!/bin/sh\necho 'ClamAV 1.4.2'\nexit 0\n");
            Files.setPosixFilePermissions(valid,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            clamav.setToolPath(valid.toString());

            assertEquals(java.util.List.of(AntivirusToolManager.Scanner.CLAMAV),
                    java.util.List.of(AntivirusToolManager.Scanner.values()));

            var choices = SettingsDialog.discoverAvailableAntiviruses(factory).join();
            assertEquals(java.util.List.of("auto", "clamav", "custom"),
                    choices.stream().map(SettingsDialog.AntivirusChoice::key).toList());
        } finally {
            factory.cleanup();
        }
    }
}
