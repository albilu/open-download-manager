package org.aria2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.GlobalSettings;

@DisplayName("aria2 tool probes follow the external configuration policy")
class Aria2ToolManagerConfigurationTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void probesIgnoreExternalConfigurationByDefault() {
        Aria2ToolManager manager = new Aria2ToolManager(
                new GlobalSettings(), executor);

        assertArrayEquals(new String[]{"aria2c", "--no-conf", "--version"},
                manager.getVersionCommand("aria2c"));
    }

    @Test
    void probesHonorExternalConfigurationWhenEnabled() {
        GlobalSettings settings = new GlobalSettings();
        settings.setHonorExternalAria2Configuration(true);
        Aria2ToolManager manager = new Aria2ToolManager(settings, executor);

        assertArrayEquals(new String[]{"aria2c", "--version"},
                manager.getVersionCommand("aria2c"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detectsSftpFromTheCompiledFeaturesWhileRespectingConfiguration(boolean honorConfig) throws Exception {
        Path executable = fakeAria2("HTTP, HTTPS, SFTP", 0);
        GlobalSettings settings = new GlobalSettings();
        settings.setHonorExternalAria2Configuration(honorConfig);
        Aria2ToolManager manager = new Aria2ToolManager(settings, executor);
        manager.setToolPath(executable.toString());

        assertEquals(true, manager.getSupportedFeatures().get("sftp"));
        assertEquals(true, manager.checkProtocolSupport("SFTP"));
        assertEquals(java.util.List.of(
                honorConfig ? "--version" : "--no-conf --version",
                honorConfig ? "--help" : "--no-conf --help"),
                Files.readAllLines(Path.of(executable + ".args")));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void doesNotClaimSftpFromHelpTextOrAFailedVersionProbe(int exitCode) throws Exception {
        Path executable = fakeAria2(exitCode == 0 ? "HTTP, HTTPS" : "HTTP, HTTPS, SFTP", exitCode);
        Aria2ToolManager manager = new Aria2ToolManager(new GlobalSettings(), executor);
        manager.setToolPath(executable.toString());

        assertEquals(false, manager.getSupportedFeatures().get("sftp"));
        assertEquals(false, manager.checkProtocolSupport("sftp"));
    }

    private Path fakeAria2(String enabledFeatures, int versionExitCode) throws Exception {
        Path executable = tempDir.resolve("aria2c");
        Files.writeString(executable, """
                #!/bin/sh
                echo "$*" >> "$0.args"
                if [ "$1" = "--no-conf" ]; then shift; fi
                if [ "$1" = "--version" ]; then
                  echo 'aria2 version 1.37.0'
                  echo 'Enabled Features: %s'
                  exit %d
                fi
                echo 'HTTP FTP SFTP BitTorrent --enable-rpc --continue'
                """.formatted(enabledFeatures, versionExitCode));
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"));
        return executable;
    }
}
