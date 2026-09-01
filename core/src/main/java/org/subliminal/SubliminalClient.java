package org.subliminal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.tools.ExternalProcessRegistry;
import org.manager.tools.ToolPaths;

/** Process-owning client for the Subliminal command-line tool. */
public class SubliminalClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubliminalClient.class);

    private final String subliminalPath;
    private final ExternalProcessRegistry activeProcesses =
            new ExternalProcessRegistry("subliminal");
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public SubliminalClient() {
        this(ToolPaths.subliminal());
    }

    public SubliminalClient(String subliminalPath) {
        this.subliminalPath = subliminalPath == null || subliminalPath.isBlank()
                ? ToolPaths.subliminal() : subliminalPath;
    }

    /** Downloads requested subtitles without overwriting existing files. */
    public boolean download(Path mediaFile, SubliminalSettings settings, String operationId) {
        java.util.Objects.requireNonNull(mediaFile, "mediaFile");
        java.util.Objects.requireNonNull(settings, "settings");
        java.util.Objects.requireNonNull(operationId, "operationId");
        if (shutdown.get()) {
            return false;
        }
        ExternalProcessRegistry.LaunchReservation launch = activeProcesses.reserve(operationId);
        if (shutdown.get()) {
            activeProcesses.terminate(operationId, 5);
            return false;
        }
        ExternalProcessRegistry.Registration registration = null;
        List<String> command = buildDownloadCommand(mediaFile, settings);
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            registration = launch.start(builder);
            Process process = registration.process();
            boolean finished = process.waitFor(
                    settings.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                LOGGER.warn("Subliminal timed out for " + mediaFile);
                activeProcesses.terminate(operationId, 5);
                return false;
            }
            if (launch.isCancelled()) {
                return false;
            }
            if (process.exitValue() != 0) {
                LOGGER.warn("Subliminal failed with exit code "
                        + process.exitValue() + " for " + mediaFile);
                return false;
            }
            return true;
        } catch (CancellationException e) {
            return false;
        } catch (IOException e) {
            LOGGER.warn("Could not start Subliminal", e);
            return false;
        } catch (InterruptedException e) {
            activeProcesses.terminate(operationId, 5);
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (registration != null) {
                registration.unregister();
            } else {
                launch.unregister();
            }
        }
    }

    List<String> buildDownloadCommand(Path mediaFile, SubliminalSettings settings) {
        List<String> command = new ArrayList<>();
        command.add(subliminalPath);
        command.add("download");
        for (String language : settings.getLanguages()) {
            command.add("-l");
            command.add(language);
        }
        // Intentionally omit --force: Subliminal should preserve existing subtitles.
        command.add(mediaFile.toString());
        return command;
    }

    public boolean cancelDownload(String operationId) {
        return activeProcesses.terminate(operationId, 5);
    }

    public void shutdown() {
        shutdown.set(true);
        activeProcesses.terminateAll(5);
    }
}
