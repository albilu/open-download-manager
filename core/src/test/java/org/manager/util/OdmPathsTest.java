package org.manager.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("OdmPaths XDG directory resolution")
class OdmPathsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("XDG_DATA_HOME is honored and 'odm' is appended")
    void honorsXdgDataHome() throws Exception {
        String resolved = SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", tempDir.toString())
                .execute(() -> OdmPaths.dataDirectory().toString());
        assertEquals(tempDir.resolve("odm").toString(), resolved);
    }

    @Test
    @DisplayName("a blank variable is treated like an unset one")
    void blankVariableFallsBack() throws Exception {
        String home = System.getProperty("user.home");
        String resolved = SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", "   ")
                .execute(() -> OdmPaths.dataDirectory().toString());
        assertEquals(Path.of(home, ".local", "share", "odm").toString(), resolved);
        assertTrue(resolved.endsWith("odm"));
    }

    @Test
    @DisplayName("XDG_STATE_HOME is honored and 'odm' is appended")
    void honorsXdgStateHome() throws Exception {
        String resolved = SystemLambda.withEnvironmentVariable("XDG_STATE_HOME", tempDir.toString())
                .execute(() -> OdmPaths.stateDirectory().toString());
        assertEquals(tempDir.resolve("odm").toString(), resolved);
    }

    @Test
    @DisplayName("a blank state variable uses the XDG state fallback")
    void blankStateVariableFallsBack() throws Exception {
        String home = System.getProperty("user.home");
        String resolved = SystemLambda.withEnvironmentVariable("XDG_STATE_HOME", "   ")
                .execute(() -> OdmPaths.stateDirectory().toString());
        assertEquals(Path.of(home, ".local", "state", "odm").toString(), resolved);
    }
}
