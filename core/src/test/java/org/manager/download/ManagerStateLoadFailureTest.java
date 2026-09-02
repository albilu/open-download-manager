package org.manager.download;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A broken state database must fail loudly: loadState swallowing the
 * failure makes startup believe there is no saved state, silently
 * discarding the user's download history.
 */
class ManagerStateLoadFailureTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("An unreadable state database completes loadState exceptionally")
    void loadStateSurfacesPersistenceFailure() throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", tempDir.toString())
                .and("XDG_STATE_HOME", tempDir.toString()).execute(() -> {
            // 'odm' as a regular file makes the state directory unusable
            Files.createFile(tempDir.resolve("odm"));

            DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> manager.loadState().get(15, TimeUnit.SECONDS),
                    "a failed state load must surface to the caller");
            assertTrue(failure.getCause() instanceof RuntimeException,
                    "the load failure must propagate as the future's completion cause");
        });
    }
}
