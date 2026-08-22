package org.manager.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Termination semantics of the shared process registry: SIGTERM-first with
 * a bounded grace window (tools save state files on SIGTERM), SIGKILL
 * escalation for processes that ignore it, idempotent terminate, and clean
 * registry bookkeeping. These semantics were previously hand-copied — and
 * diverged — across all four tool clients.
 */
@DisplayName("ExternalProcessRegistry termination semantics")
class ExternalProcessRegistryTest {

    @TempDir
    Path tempDir;

    private Process hungProcess() throws Exception {
        Path script = tempDir.resolve("hang-" + System.nanoTime());
        Files.writeString(script, "#!/bin/bash\nexec sleep 300\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return new ProcessBuilder(script.toString()).start();
    }

    private static boolean alive(Process process) {
        return process != null && process.isAlive();
    }

    @Test
    @DisplayName("SIGTERM kills an ordinary process within the grace window")
    void sigtermSuffices() throws Exception {
        ExternalProcessRegistry registry = new ExternalProcessRegistry("test");
        Process process = hungProcess();
        registry.register("a", process);
        assertEquals(1, registry.size());

        long start = System.nanoTime();
        assertTrue(registry.terminate("a", 5), "live process must be terminated");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(alive(process), "process must be dead after terminate");
        assertTrue(elapsedMs < 5000, "SIGTERM path must not burn the whole grace window");
        assertNull(registry.get("a"), "registration removed");
        assertTrue(registry.isEmpty());
    }

    @Test
    @DisplayName("SIGTERM-ignoring process is killed by escalation")
    void sigkillEscalation() throws Exception {
        ExternalProcessRegistry registry = new ExternalProcessRegistry("test");
        // trap '' TERM ignores SIGTERM; only SIGKILL works
        Path script = tempDir.resolve("ignore-term");
        Files.writeString(script, "#!/bin/bash\ntrap '' TERM\nexec sleep 300\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        Process process = new ProcessBuilder(script.toString()).start();

        registry.register("stubborn", process);
        assertTrue(registry.terminate("stubborn", 2));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (alive(process) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(alive(process), "SIGKILL escalation must kill a SIGTERM-ignoring process");
    }

    @Test
    @DisplayName("Terminate is idempotent; unknown keys and dead processes are no-ops")
    void idempotentAndTolerant() throws Exception {
        ExternalProcessRegistry registry = new ExternalProcessRegistry("test");
        assertFalse(registry.terminate("never-registered", 1), "unknown key is a no-op");

        Process process = hungProcess();
        registry.register("once", process);
        assertTrue(registry.terminate("once", 5));
        assertFalse(registry.terminate("once", 5), "second terminate must not re-kill");

        Process dead = hungProcess();
        dead.destroyForcibly();
        dead.waitFor(5, TimeUnit.SECONDS);
        registry.register("dead", dead);
        assertTrue(registry.terminate("dead", 1), "dead registration is consumed");
        assertFalse(alive(dead));
    }

    @Test
    @DisplayName("terminateAll drains the registry")
    void terminateAllDrains() throws Exception {
        ExternalProcessRegistry registry = new ExternalProcessRegistry("test");
        Process first = hungProcess();
        Process second = hungProcess();
        registry.register("one", first);
        registry.register("two", second);

        registry.terminateAll(5);

        assertFalse(alive(first));
        assertFalse(alive(second));
        assertTrue(registry.isEmpty());
        assertNotNull(tempDir);
    }
}
