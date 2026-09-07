package org.subliminal;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.tools.ExternalProcessRegistry;
import org.manager.tools.ToolPaths;

/** Process-owning client for the Subliminal command-line tool. */
public class SubliminalClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubliminalClient.class);
    private static final int MAX_CAPTURED_OUTPUT_CHARS = 1_000_000;
    private static final int OUTPUT_READER_TIMEOUT_SECONDS = 5;

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

    /**
     * Result of one Subliminal process execution. Output contains the merged
     * stdout and stderr stream exactly as decoded from UTF-8, subject to the
     * bounded capture limit.
     */
    public record DownloadResult(
            boolean successful,
            Integer exitCode,
            String output,
            String detail) {

        public DownloadResult {
            output = output == null ? "" : output;
            detail = detail == null ? "" : detail;
        }
    }

    /** Downloads requested subtitles without overwriting existing files. */
    public boolean download(Path mediaFile, SubliminalSettings settings, String operationId) {
        return downloadWithResult(mediaFile, settings, operationId).successful();
    }

    /**
     * Downloads requested subtitles and returns the process output and exit
     * details for diagnostics.
     */
    public DownloadResult downloadWithResult(
            Path mediaFile, SubliminalSettings settings, String operationId) {
        java.util.Objects.requireNonNull(mediaFile, "mediaFile");
        java.util.Objects.requireNonNull(settings, "settings");
        java.util.Objects.requireNonNull(operationId, "operationId");
        if (shutdown.get()) {
            return failed(null, "", "Subliminal client is shut down");
        }
        ExternalProcessRegistry.LaunchReservation launch = activeProcesses.reserve(operationId);
        if (shutdown.get()) {
            activeProcesses.terminate(operationId, 5);
            return failed(null, "", "Subliminal client is shut down");
        }
        ExternalProcessRegistry.Registration registration = null;
        CompletableFuture<String> capturedOutput = null;
        List<String> command = buildDownloadCommand(mediaFile, settings);
        try (var route = org.manager.tools.ProxiedCommand.prepare(settings.getProxyAddress())) {
            command = route.wrap(command);
            ProcessBuilder builder = org.manager.tools.NetworkProcessPolicy.prepare(new ProcessBuilder(command))
                    .redirectErrorStream(true);
            registration = launch.start(builder);
            Process process = registration.process();
            capturedOutput = captureOutput(process);
            boolean finished = process.waitFor(
                    settings.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                LOGGER.warn("Subliminal timed out for " + mediaFile);
                activeProcesses.terminate(operationId, 5);
                return failed(exitCodeOf(process), awaitOutput(capturedOutput),
                        "Subliminal timed out after "
                        + settings.getTimeout().toSeconds() + " seconds");
            }
            String output = awaitOutput(capturedOutput);
            if (launch.isCancelled()) {
                return failed(exitCodeOf(process), output,
                        "Subliminal operation was canceled");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                LOGGER.warn("Subliminal failed with exit code "
                        + exitCode + " for " + mediaFile);
                return failed(exitCode, output,
                        "Subliminal exited with code " + exitCode);
            }
            return new DownloadResult(true, exitCode, output,
                    "Subliminal completed successfully");
        } catch (CancellationException e) {
            return failed(null, awaitOutput(capturedOutput),
                    "Subliminal operation was canceled");
        } catch (IOException e) {
            LOGGER.warn("Could not start Subliminal", e);
            return failed(null, awaitOutput(capturedOutput),
                    "Could not start Subliminal: " + errorMessage(e));
        } catch (InterruptedException e) {
            activeProcesses.terminate(operationId, 5);
            String output = awaitOutput(capturedOutput);
            Thread.currentThread().interrupt();
            return failed(null, output, "Subliminal operation was interrupted");
        } finally {
            if (registration != null) {
                registration.unregister();
            } else {
                launch.unregister();
            }
        }
    }

    private static DownloadResult failed(Integer exitCode, String output, String detail) {
        return new DownloadResult(false, exitCode, output, detail);
    }

    private static Integer exitCodeOf(Process process) {
        return process.isAlive() ? null : process.exitValue();
    }

    /** Drains merged stdout/stderr while retaining a bounded transcript. */
    private static CompletableFuture<String> captureOutput(Process process) {
        CompletableFuture<String> captured = new CompletableFuture<>();
        Thread.ofVirtual().name("odm-subliminal-output").start(() -> {
            try (InputStreamReader reader = new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder retained = new StringBuilder();
                boolean truncated = false;
                char[] buffer = new char[8192];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    int remaining = MAX_CAPTURED_OUTPUT_CHARS - retained.length();
                    if (remaining > 0) {
                        retained.append(buffer, 0, Math.min(count, remaining));
                    }
                    truncated |= count > remaining;
                }
                if (truncated) {
                    retained.append("\n\n[Subliminal output truncated by ODM]");
                }
                captured.complete(retained.toString());
            } catch (IOException e) {
                captured.completeExceptionally(e);
            }
        });
        return captured;
    }

    private static String awaitOutput(CompletableFuture<String> output) {
        if (output == null) {
            return "";
        }
        try {
            return output.get(OUTPUT_READER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[Interrupted while collecting Subliminal output]";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return "[Could not read Subliminal output: "
                    + errorMessage(cause == null ? e : cause) + "]";
        } catch (TimeoutException e) {
            return "[Subliminal output reader did not finish]";
        }
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
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
