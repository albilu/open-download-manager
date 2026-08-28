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

    @Test
    @DisplayName("A stale generation's cleanup cannot unregister a newer process")
    void staleGenerationCleanupKeepsNewerRegistration() throws Exception {
        ExternalProcessRegistry registry = new ExternalProcessRegistry("test");
        Process oldProcess = hungProcess();
        Process newProcess = hungProcess();

        ExternalProcessRegistry.Registration oldRegistration = registry.register("key", oldProcess);
        ExternalProcessRegistry.Registration newRegistration = registry.register("key", newProcess);

        oldRegistration.unregister();

        assertEquals(newProcess, registry.get("key"),
                "the newer generation must remain registered after the old cleanup");
        assertEquals(1, registry.size());

        newRegistration.unregister();
        assertNull(registry.get("key"), "the current generation's cleanup unregisters");
    }

    @Test
    @DisplayName("A stale generation's registration stays inert after replacement")
    void staleRegistrationRemainsInert() throws Exception {
        ExternalProcessRegistry registry = new ExternalProcessRegistry("test");
        Process oldProcess = hungProcess();
        Process newProcess = hungProcess();

        ExternalProcessRegistry.Registration oldRegistration = registry.register("key", oldProcess);
        registry.register("key", newProcess);

        assertTrue(registry.terminate("key", 5), "the current process must be terminatable");
        assertFalse(alive(newProcess), "the current process must be dead");
        assertTrue(alive(oldProcess), "termination must not touch the replaced process");
        oldRegistration.unregister();
        oldProcess.destroyForcibly();
        oldProcess.waitFor(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Unregister by registration never kills; terminate kills the exact generation")
    void unregisterIsBookkeepingOnly() throws Exception {
        ExternalProcessRegistry registry = new ExternalProcessRegistry("test");
        Process process = hungProcess();

        ExternalProcessRegistry.Registration registration = registry.register("key", process);
        registration.unregister();

        assertNull(registry.get("key"));
        assertTrue(alive(process), "unregister is bookkeeping and must not signal the process");
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    private Process treeProcess() throws Exception {
        Path script = tempDir.resolve("tree-" + System.nanoTime());
        Files.writeString(script, "#!/bin/bash\nsleep 300 &\nwait\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return new ProcessBuilder(script.toString()).start();
    }

    @Test
    @DisplayName("Termination destroys the whole descendant tree, not just the direct process")
    void terminationDestroysDescendantTree() throws Exception {
        ExternalProcessRegistry registry = new ExternalProcessRegistry("test");
        Process parent = treeProcess();

        java.util.List<ProcessHandle> children;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            children = parent.toHandle().descendants().toList();
            if (!children.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertFalse(children.isEmpty(), "test prerequisite: the tree process must have a child");

        registry.register("tree", parent);
        assertTrue(registry.terminate("tree", 5));

        assertFalse(alive(parent), "parent must be dead");
        for (ProcessHandle child : children) {
            assertFalse(effectivelyAlive(child),
                    "descendant must be dead too: " + child);
        }
    }

    /**
     * A descendant killed after its parent died is reparented to init and
     * lingers as an unreaped zombie; isAlive() stays true although the
     * process is gone. Zombie state counts as dead.
     */
    private static boolean effectivelyAlive(ProcessHandle handle) throws Exception {
        if (!handle.isAlive()) {
            return false;
        }
        String stat = Files.readString(Path.of("/proc/" + handle.pid() + "/stat"));
        char state = stat.charAt(stat.lastIndexOf(')') + 2);
        return state != 'Z' && state != 'X';
    }
}
