package org.manager.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.curl.CurlClient;
import org.httrack.HttrackClient;
import org.proxychains.ProxychainsClient;
import org.ytdlp.YtDlpClient;

/**
 * Startup-path process checks must be bounded: a hung or wedged tool binary
 * (stale NFS mount, waiting on stdin, PATH pointing at a broken wrapper)
 * must fail fast instead of blocking client construction or availability
 * probes forever — these run during application startup.
 */
@DisplayName("Tool probes tolerate hung binaries")
class HungToolProbeTest {

    @TempDir
    Path tempDir;

    private Path hungBinary(String name) throws Exception {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/bash\nexec sleep 300\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private static <T> T withinSeconds(int seconds, java.util.function.Supplier<T> call)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<T> future = pool.submit(call::get);
            return future.get(seconds, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CurlClient constructor fails fast on a hung curl binary")
    void curlConstructorTimesOut() throws Exception {
        Path hung = hungBinary("hung-curl");
        long start = System.nanoTime();
        RuntimeException failure = withinSeconds(20, () -> {
            try {
                new CurlClient(hung.toString());
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertTrue(failure != null, "a hung curl binary must fail validation, not hang");
        assertTrue(elapsedSeconds < 20,
                "validation must fail within the bounded probe timeout, took " + elapsedSeconds + "s");
    }

    @Test
    @DisplayName("ProxychainsClient constructor fails fast on a hung proxychains binary")
    void proxychainsConstructorTimesOut() throws Exception {
        Path hung = hungBinary("hung-proxychains");
        long start = System.nanoTime();
        RuntimeException failure = withinSeconds(20, () -> {
            try {
                new ProxychainsClient(hung.toString(), null);
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertTrue(failure != null, "a hung proxychains binary must fail validation, not hang");
        assertTrue(elapsedSeconds < 20,
                "validation must fail within the bounded probe timeout, took " + elapsedSeconds + "s");
    }

    @Test
    @DisplayName("yt-dlp availability probe returns false on a hung binary")
    void ytdlpAvailabilityIsBounded() throws Exception {
        Path hung = hungBinary("hung-ytdlp");
        long start = System.nanoTime();
        Boolean available = withinSeconds(20, () -> new YtDlpClient(hung.toString()).isAvailable());
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertFalse(available, "a hung yt-dlp binary is not available");
        assertTrue(elapsedSeconds < 20,
                "availability probe must be bounded, took " + elapsedSeconds + "s");
    }

    @Test
    @DisplayName("httrack availability probe returns false on a hung binary")
    void httrackAvailabilityIsBounded() throws Exception {
        Path hung = hungBinary("hung-httrack");
        long start = System.nanoTime();
        Boolean available = withinSeconds(20,
                () -> new HttrackClient(hung.toString()).isHttrackAvailable().join());
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertFalse(available, "a hung httrack binary is not available");
        assertTrue(elapsedSeconds < 20,
                "availability probe must be bounded, took " + elapsedSeconds + "s");
    }
}
