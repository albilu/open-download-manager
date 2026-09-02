package org.antivirus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;

class AntivirusToolManagerTest {

    @TempDir
    Path tempDir;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void everyScannerDiscoversAndValidatesAConfiguredExecutable() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        for (AntivirusToolManager.Scanner scanner : AntivirusToolManager.Scanner.values()) {
            Path executable = tempDir.resolve(scanner.executableName());
            Files.writeString(executable,
                    "#!/bin/sh\necho '" + scanner.label() + " version 1.2.3'\nexit 0\n");
            Files.setPosixFilePermissions(executable,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));

            AntivirusToolManager manager = new AntivirusToolManager(scanner, settings, executor);
            manager.setToolPath(executable.toString());

            assertTrue(manager.checkAvailabilityAsync().join());
            manager.validateTool();
            assertEquals(executable.toString(), manager.getToolPath());
            assertEquals("1.2.3", manager.getVersion());
            assertEquals(scanner.toolId(), manager.getToolId());
        }
    }
}
