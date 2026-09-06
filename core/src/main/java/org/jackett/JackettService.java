package org.jackett;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import org.manager.GlobalSettings;
import org.manager.tools.ExternalProcessRegistry;
import org.manager.util.OdmPaths;

/** Owns only the foreground Jackett process launched by this ODM instance. */
public final class JackettService implements AutoCloseable {
    public enum State { NOT_INSTALLED, STOPPED, STARTING, RUNNING, STOPPING, INSTALLING, FAILED }
    public record Status(State state, String message) { }
    private final java.util.function.Supplier<GlobalSettings> settings;
    private final Path installation;
    private final Path configuration;
    private final Path logFile;
    private final Duration startupTimeout;
    private final Object lifecycle = new Object();
    private final AtomicLong generation = new AtomicLong();
    private final ExternalProcessRegistry processes = new ExternalProcessRegistry("Jackett");
    private volatile Status status;
    private volatile Process process;
    private volatile JackettClient client;
    private volatile boolean closed;
    private volatile Thread worker;
    private FileChannel lockChannel;
    private FileLock lock;

    public JackettService(GlobalSettings settings) {
        this(() -> settings);
    }

    public JackettService(java.util.function.Supplier<GlobalSettings> settings) {
        this(settings, JackettSettings.installationDirectory(), JackettSettings.configurationDirectory(),
                OdmPaths.stateDirectory().resolve("jackett.log"), Duration.ofSeconds(40));
    }

    public JackettService(GlobalSettings settings, Path installation, Path configuration,
            Path logFile, Duration startupTimeout) {
        this(() -> settings, installation, configuration, logFile, startupTimeout);
    }

    public JackettService(java.util.function.Supplier<GlobalSettings> settings, Path installation, Path configuration,
            Path logFile, Duration startupTimeout) {
        this.settings = settings;
        this.installation = installation;
        this.configuration = configuration;
        this.logFile = logFile;
        this.startupTimeout = startupTimeout;
        this.status = new Status(isInstalled() ? State.STOPPED : State.NOT_INSTALLED,
                isInstalled() ? "Stopped" : "Jackett is not installed");
    }

    public Path executable() { return installation.resolve("Jackett/jackett"); }
    public Path configurationFile() { return configuration.resolve("ServerConfig.json"); }
    public Path logFile() { return logFile; }
    public boolean isInstalled() { return Files.isExecutable(executable()); }

    public Status status() {
        Status current = status;
        Process owned = process;
        return current.state() == State.RUNNING && (owned == null || !owned.isAlive())
                ? new Status(State.FAILED, "Jackett exited; see " + logFile) : current;
    }

    public JackettClient client() throws IOException {
        JackettClient current = client;
        if (current == null || status().state() != State.RUNNING) {
            throw new IOException("Start Jackett in Settings → Search Engine");
        }
        return current;
    }

    /** Call off the GTK thread. stop()/close() invalidate a pending startup immediately. */
    public void start() throws IOException {
        long ticket = generation.get();
        synchronized (lifecycle) {
            checkCurrent(ticket);
            if (status().state() == State.RUNNING) { return; }
            if (!isInstalled()) { throw new IOException("Install Jackett in Settings first"); }
            worker = Thread.currentThread();
            try {
                stopOwned();
                acquireLock();
                int port = JackettSettings.port(settings.get());
                try (ServerSocket probe = new ServerSocket()) {
                    probe.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
                } catch (IOException occupied) {
                    throw new IOException("Jackett's local port is in use by another application");
                }
                Files.createDirectories(configuration);
                Files.setPosixFilePermissions(configuration, PosixFilePermissions.fromString("rwx------"));
                Files.createDirectories(logFile.getParent());
                // Seed an isolated config to prevent Jackett migrating an unrelated ~/.config/Jackett.
                if (!Files.exists(configurationFile())) {
                    Files.writeString(configurationFile(), "{\"AllowExternal\":false,\"UpdateDisabled\":true}",
                            StandardOpenOption.CREATE_NEW);
                }
                Files.setPosixFilePermissions(configurationFile(), PosixFilePermissions.fromString("rw-------"));
                checkCurrent(ticket);
                status = new Status(State.STARTING, "Starting Jackett…");
                ProcessBuilder builder = new ProcessBuilder(executable().toAbsolutePath().toString(),
                        "--DataFolder", configuration.toAbsolutePath().toString(), "--ListenPrivate",
                        "--Port", Integer.toString(port), "--NoUpdates", "--NoRestart")
                        .directory(executable().getParent().toFile()).redirectErrorStream(true)
                        .redirectOutput(logFile.toFile());
                process = builder.start();
                processes.register("server", process);
                JackettClient candidate = new JackettClient(port, configurationFile());
                long deadline = System.nanoTime() + startupTimeout.toNanos();
                while (System.nanoTime() < deadline) {
                    checkCurrent(ticket);
                    if (!process.isAlive()) { throw new IOException("Jackett exited during startup; see " + logFile); }
                    if (candidate.isReady()) {
                        String running = "Running";
                        try { running = candidate.version() + " — Running"; }
                        catch (IOException unavailable) { /* Readiness still establishes a usable server. */ }
                        checkCurrent(ticket);
                        client = candidate;
                        status = new Status(State.RUNNING, running);
                        return;
                    }
                    try { Thread.sleep(200); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Jackett startup cancelled");
                    }
                }
                throw new IOException("Jackett did not become ready; see " + logFile);
            } catch (IOException | RuntimeException failure) {
                stopOwned();
                status = new Status(State.FAILED, failure.getMessage());
                throw failure;
            } finally { worker = null; }
        }
    }

    public void install() throws IOException {
        install(null);
    }

    public void install(Path archive) throws IOException {
        synchronized (lifecycle) {
            checkCurrent(generation.get());
            if (process != null && process.isAlive()) { throw new IOException("Stop Jackett before installing an update"); }
            worker = Thread.currentThread();
            try {
                stopOwned();
                acquireLock();
                status = new Status(State.INSTALLING, "Installing Jackett…");
                JackettInstaller installer = new JackettInstaller();
                String version;
                if (archive == null) { version = installer.installLatest(installation); }
                else { installer.installArchive(archive, installation, "local archive"); version = "local archive"; }
                status = new Status(State.STOPPED, "Installed " + version + "; ready to start");
            } catch (IOException | RuntimeException failure) {
                status = new Status(State.FAILED, failure.getMessage());
                throw failure;
            } finally { worker = null; releaseLock(); }
        }
    }

    public void stop() {
        generation.incrementAndGet();
        Thread active = worker;
        if (active != null && active != Thread.currentThread()) { active.interrupt(); }
        synchronized (lifecycle) {
            status = new Status(State.STOPPING, "Stopping Jackett…");
            stopOwned();
            status = new Status(isInstalled() ? State.STOPPED : State.NOT_INSTALLED, "Stopped");
        }
    }

    @Override
    public void close() { closed = true; stop(); }

    private void checkCurrent(long ticket) {
        if (closed || generation.get() != ticket || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Jackett operation cancelled");
        }
    }

    private void stopOwned() {
        // A cancelled startup arrives here interrupted. Termination must still wait for
        // process exit before releasing ownership, even when the caller was cancelled.
        boolean interrupted = Thread.interrupted();
        try {
            processes.terminate("server", 3);
            interrupted |= Thread.interrupted();
            Process owned = process;
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (owned != null && owned.isAlive() && System.nanoTime() < deadline) {
                owned.destroyForcibly();
                try { owned.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
            if (owned != null && owned.isAlive()) {
                throw new IllegalStateException("Jackett did not stop; see " + logFile);
            }
            process = null;
            client = null;
            releaseLock();
        } finally {
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private void acquireLock() throws IOException {
        Files.createDirectories(installation);
        lockChannel = FileChannel.open(installation.resolve(".odm.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) { throw new IOException("Another ODM instance is using this Jackett installation"); }
        } catch (IOException | java.nio.channels.OverlappingFileLockException failure) {
            releaseLock();
            throw new IOException("Another ODM instance is using this Jackett installation");
        }
    }

    private void releaseLock() {
        try { if (lock != null) { lock.release(); } }
        catch (IOException ignored) { }
        try { if (lockChannel != null) { lockChannel.close(); } }
        catch (IOException ignored) { }
        lock = null;
        lockChannel = null;
    }
}
