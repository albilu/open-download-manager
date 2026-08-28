package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.tools.ToolManagerFactory;

/**
 * Tool-path changes applied through setGlobalSettings must take effect
 * without a restart: the tool managers cache path/availability, so the
 * manager must invalidate those caches whenever new settings are applied.
 */
@DisplayName("Tool paths applied via setGlobalSettings are live")
class ManagerSettingsToolPathLiveTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("changing the curl path is reflected by the tool manager immediately")
    void toolPathChangeIsLive() throws Exception {
        Path xdgData = tempDir.resolve("xdg-data");
        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", xdgData.toString()).execute(() -> {
            DownloadManager manager = DownloadManagerFactory.createDefaultManager();
            ToolManagerFactory toolFactory = ApplicationContext.getToolManagerFactory();

            String originalPath = toolFactory.getToolPath("curl");
            assertTrue(originalPath != null,
                    "curl should be discoverable in the test environment");

            GlobalSettings updated = manager.getGlobalSettings().copy();
            updated.setCurlPath("/bin/true");
            manager.setGlobalSettings(updated);

            assertEquals("/bin/true", toolFactory.getToolPath("curl"),
                    "the tool manager must re-resolve from the new configured path");
            assertTrue(toolFactory.isToolAvailable("curl"),
                    "a valid executable at the new path must be reported available");

            manager.shutdown().get(60, java.util.concurrent.TimeUnit.SECONDS);
        });
    }
}
