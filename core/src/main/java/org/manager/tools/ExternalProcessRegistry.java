package org.manager.tools;

import java.util.List;
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
 * like httrack save their index/state files on SIGTERM), then SIGKILL —
 * applied to the whole descendant tree, because tools spawn children
 * (yt-dlp's ffmpeg/aria2c) that would otherwise survive cancellation.
 *
 * <p>Registrations are generation-safe: {@link #register} returns a handle
 * bound to the exact process it registered. A worker that finished an older
 * run under the same key (resume/restart raced it) can therefore never
 * unregister or destroy the newer generation — its stale handle is a no-op.
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
     * Generation handle for one registered process. Unregistering through
     * the handle removes the mapping only while the exact process it
     * registered is still the one held under the key.
     */
    public static final class Registration {

        private final ExternalProcessRegistry registry;
        private final String key;
        private final Process process;

        private Registration(ExternalProcessRegistry registry, String key, Process process) {
            this.registry = registry;
            this.key = key;
            this.process = process;
        }

        /** The registered process. */
        public Process process() {
            return process;
        }

        /**
         * Removes this registration unless a newer generation has already
         * replaced it. Bookkeeping only: the process is never signalled.
         *
         * @return true when this exact registration was removed
         */
        public boolean unregister() {
            return registry.removeIfCurrent(key, process);
        }
    }

    /**
     * Registers a process under a cancellation key.
     *
     * @param key client-defined cancellation key (download id, job id, ...)
     * @param process the launched process
     * @return generation handle whose {@link Registration#unregister()} is
     *         a no-op once this process has been replaced under the key
     */
    public Registration register(String key, Process process) {
        processes.put(key, process);
        return new Registration(this, key, process);
    }

    /** Looks up a live registration. */
    public Process get(String key) {
        return processes.get(key);
    }

    /** Removes a registration (e.g. after normal completion). */
    public Process remove(String key) {
        return processes.remove(key);
    }

    /**
     * Removes the registration only while the given process still holds the
     * key: a stale worker's cleanup cannot unregister a newer generation.
     *
     * @param key the cancellation key
     * @param expected the process whose registration is being retired
     * @return true when the mapping existed and was removed
     */
    public boolean removeIfCurrent(String key, Process expected) {
        return processes.remove(key, expected);
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
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        try {
            destroyTree(process, descendants, false);
            if (!awaitTreeTerminated(process, descendants, graceSeconds)) {
                LOGGER.warning(owner + ": process '" + key + "' ignored SIGTERM for " + graceSeconds
                        + "s; killing tree");
                destroyTree(process, descendants, true);
                if (!awaitTreeTerminated(process, descendants, graceSeconds)) {
                    LOGGER.severe(owner + ": process '" + key + "' (or a descendant) survived SIGKILL");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // An interrupted terminator must still not leave the tree alive
            destroyTree(process, descendants, true);
        }
    }

    private static void destroyTree(Process process, List<ProcessHandle> descendants, boolean force) {
        for (ProcessHandle descendant : descendants) {
            if (effectivelyAlive(descendant)) {
                if (force) {
                    descendant.destroyForcibly();
                } else {
                    descendant.destroy();
                }
            }
        }
        if (force) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }
    }

    private static boolean awaitTreeTerminated(Process process, List<ProcessHandle> descendants,
            long graceSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(graceSeconds);
        while (System.nanoTime() < deadline) {
            if (treeTerminated(process, descendants)) {
                return true;
            }
            Thread.sleep(20);
        }
        return treeTerminated(process, descendants);
    }

    private static boolean treeTerminated(Process process, List<ProcessHandle> descendants) {
        if (process.isAlive()) {
            return false;
        }
        for (ProcessHandle descendant : descendants) {
            if (effectivelyAlive(descendant)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Liveness that accounts for orphaned zombies: a tree node killed after
     * its parent died is reparented to init, and nothing in this JVM reaps
     * it — {@code isAlive()} then stays true forever although the process
     * is gone. A zombie or dead proc state means terminated.
     */
    private static boolean effectivelyAlive(ProcessHandle handle) {
        if (!handle.isAlive()) {
            return false;
        }
        try {
            String stat = java.nio.file.Files.readString(
                    java.nio.file.Path.of("/proc/" + handle.pid() + "/stat"));
            int stateStart = stat.lastIndexOf(')') + 2;
            char state = stat.charAt(stateStart);
            return state != 'Z' && state != 'X';
        } catch (Exception unreadable) {
            return true;
        }
    }
}
