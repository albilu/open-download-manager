package org.manager.tools;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared registry and lifecycle for external tool processes. The four tool
 * clients each kept a private {@code activeProcesses} map with hand-rolled
 * cancel loops whose semantics had drifted apart (bare destroy, bare
 * destroyForcibly, partial escalation) — exactly where the bugs lived.
 * Termination here is uniform: SIGTERM with a bounded grace window (tools
 * like httrack save their index/state files on SIGTERM), then SIGKILL.
 *
 * <p>Thread-safe: registration, lookup, and termination may race freely;
 * termination is idempotent per key.
 */
public final class ExternalProcessRegistry {

    private static final Logger LOGGER = Logger.getLogger(ExternalProcessRegistry.class.getName());

    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final String ownerName;

    /** @param ownerName client name for log messages */
    public ExternalProcessRegistry(String ownerName) {
        this.ownerName = ownerName;
    }

    /**
     * Registers a process under a cancellation key.
     *
     * @param key client-defined cancellation key (download id, job id, ...)
     * @param process the launched process
     */
    public void register(String key, Process process) {
        processes.put(key, process);
    }

    /** Looks up a live registration. */
    public Process get(String key) {
        return processes.get(key);
    }

    /** Removes a registration (e.g. after normal completion). */
    public Process remove(String key) {
        return processes.remove(key);
    }

    public boolean isEmpty() {
        return processes.isEmpty();
    }

    public int size() {
        return processes.size();
    }

    /** All live keys (snapshot; for shutdown paths). */
    public Iterable<String> keys() {
        return java.util.List.copyOf(processes.keySet());
    }

    /**
     * Terminates the process registered under {@code key} and removes the
     * registration: SIGTERM, wait up to {@code graceSeconds}, then SIGKILL
     * with the same grace. Idempotent: terminating an unknown/finished key
     * is a no-op. Interrupts only shorten the wait — the kill always fires.
     *
     * @param key           the cancellation key
     * @param graceSeconds  SIGTERM/SIGKILL grace window in seconds
     * @return true if a live process was terminated
     */
    public boolean terminate(String key, int graceSeconds) {
        Process process = processes.remove(key);
        if (process == null) {
            return false;
        }
        terminateProcess(ownerName, key, process, graceSeconds);
        return true;
    }

    /** Terminates every registered process (shutdown paths). */
    public void terminateAll(int graceSeconds) {
        for (String key : keys()) {
            terminate(key, graceSeconds);
        }
    }

    private static void terminateProcess(String owner, String key, Process process, int graceSeconds) {
        if (!process.isAlive()) {
            return;
        }
        try {
            process.destroy(); // SIGTERM: graceful cleanup (state/index files)
            if (!process.waitFor(graceSeconds, TimeUnit.SECONDS)) {
                LOGGER.warning(owner + ": process '" + key + "' ignored SIGTERM for " + graceSeconds
                        + "s; killing");
                process.destroyForcibly();
                if (!process.waitFor(graceSeconds, TimeUnit.SECONDS)) {
                    LOGGER.severe(owner + ": process '" + key + "' survived SIGKILL");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // An interrupted terminator must still not leave the process alive
            process.destroyForcibly();
        }
    }
}
