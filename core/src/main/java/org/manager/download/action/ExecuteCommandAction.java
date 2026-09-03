package org.manager.download.action;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.manager.download.Download;

/**
 * After-completion action that executes a user-defined command with
 * download-specific values substituted into the command template.
 *
 * <p>Supported template variables (substituted before tokenization):</p>
 * <ul>
 *   <li>{@code {file_path}} — absolute path of the downloaded file</li>
 *   <li>{@code {filename}} — file name (with extension)</li>
 *   <li>{@code {dir}} — destination directory</li>
 *   <li>{@code {url}} — the download URI</li>
 *   <li>{@code {id}} — the download id</li>
 *   <li>{@code {gid}} — the engine gid, when applicable</li>
 * </ul>
 *
 * <p>Example: {@code mv {file_path} /tmp} or
 * {@code notify-send "Done" "{filename}"}.</p>
 *
 * <p>The command is tokenized with single/double-quote awareness and executed
 * via {@link ProcessBuilder} argv (no shell), with a timeout and forcible
 * destruction on cancel.</p>
 */
public class ExecuteCommandAction implements AfterCompletionAction {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecuteCommandAction.class);

    /** Default process timeout in seconds; 0 means no timeout. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_CAPTURED_OUTPUT_CHARS = 1_048_576;

    private final String commandTemplate;
    private final int timeoutSeconds;
    private volatile Process process;
    private volatile boolean cancelled;
    private volatile String commandOutput = "";
    private volatile String outcomeMessage = "Action did not complete successfully";

    /**
     * Creates an action that runs the given command template.
     *
     * @param commandTemplate the command with {@code {file_path}}-style
     *                        placeholders
     */
    public ExecuteCommandAction(String commandTemplate) {
        this(commandTemplate, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Creates an action that runs the given command template with a custom
     * process timeout.
     *
     * @param commandTemplate the command with {@code {file_path}}-style
     *                        placeholders
     * @param timeoutSeconds  process timeout in seconds; 0 disables it
     */
    public ExecuteCommandAction(String commandTemplate, int timeoutSeconds) {
        this.commandTemplate = Objects.requireNonNull(commandTemplate, "commandTemplate");
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
    }

    /**
     * Gets the raw command template (for persistence and descriptions).
     *
     * @return the command template
     */
    public String getCommandTemplate() {
        return commandTemplate;
    }

    @Override
    public boolean execute(Download download) {
        commandOutput = "";
        outcomeMessage = "Action did not complete successfully";
        if (commandTemplate.isBlank()) {
            LOGGER.warn("Custom command is empty; nothing to execute");
            outcomeMessage = "Custom command is empty";
            return false;
        }
        Path filePath = resolveFilePath(download);
        if (filePath == null) {
            LOGGER.warn("Cannot run custom command: no file path for download " + download.getName());
            outcomeMessage = "Downloaded file path is unknown";
            return false;
        }

        List<String> command = tokenize(substitute(commandTemplate, download, filePath));
        if (command.isEmpty()) {
            LOGGER.warn("Custom command produced no tokens");
            outcomeMessage = "Custom command produced no executable";
            return false;
        }

        try {
            LOGGER.info("Executing configured after-completion command");
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            CompletableFuture<String> output = captureOutput(process);
            boolean finished = timeoutSeconds > 0
                    ? process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                    : waitForUninterruptibly();
            if (cancelled) {
                commandOutput = awaitOutput(output);
                outcomeMessage = "Custom command was canceled";
                return false;
            }
            if (!finished) {
                LOGGER.warn("Custom command timed out after " + timeoutSeconds + "s");
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                commandOutput = awaitOutput(output);
                outcomeMessage = "Custom command timed out after " + timeoutSeconds + " seconds";
                return false;
            }
            commandOutput = awaitOutput(output);
            int exit = process.exitValue();
            if (exit != 0) {
                LOGGER.warn("Custom command exited with code " + exit);
                outcomeMessage = "Custom command exited with code " + exit;
                return false;
            }
            outcomeMessage = "Command completed successfully";
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to execute custom command", e);
            outcomeMessage = "Could not run custom command: " + e.getMessage();
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Custom command execution interrupted");
            outcomeMessage = "Custom command execution was interrupted";
            return false;
        }
    }

    @Override
    public ActionType getType() {
        return ActionType.EXECUTE_COMMAND;
    }

    @Override
    public String getDescription() {
        return "Execute command: " + commandTemplate;
    }

    @Override
    public String getResultMessage() {
        return outcomeMessage;
    }

    @Override
    public String getFailureMessage() {
        return outcomeMessage;
    }

    @Override
    public String getOutput() {
        return commandOutput;
    }

    @Override
    public Severity getSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public boolean cancel() {
        cancelled = true;
        Process current = process;
        if (current != null && current.isAlive()) {
            current.destroyForcibly();
            return true;
        }
        return true;
    }

    /**
     * Resolves the downloaded file path: destination directory joined with
     * the download name, verified to exist when possible.
     */
    private static Path resolveFilePath(Download download) {
        Path candidate = download.getPrimaryOutputPath();
        if (candidate != null) {
            if (Files.exists(candidate)) {
                return candidate;
            }
            return candidate; // command may still want the expected path
        }
        return null;
    }

    /**
     * Substitutes template variables with download-specific values.
     */
    static String substitute(String template, Download download, Path filePath) {
        String result = template
                .replace("{file_path}", filePath.toString())
                .replace("{filename}", filePath.getFileName().toString())
                .replace("{dir}", filePath.getParent() != null ? filePath.getParent().toString() : "")
                .replace("{url}", download.getUri() != null ? download.getUri().toString() : "")
                .replace("{id}", download.getId() != null ? download.getId() : "")
                .replace("{gid}", download.getGid() != null ? download.getGid() : "");
        return result;
    }

    // Quote-aware tokenizer: splits on whitespace except inside single or
    // double quotes; quotes are removed (shell-like, without escapes)
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");

    /**
     * Tokenizes a command line with single/double-quote awareness.
     *
     * @param line the command line after substitution
     * @return argv-style tokens
     */
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(line);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                tokens.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                tokens.add(matcher.group(2));
            } else {
                tokens.add(matcher.group());
            }
        }
        return tokens;
    }

    private boolean waitForUninterruptibly() throws InterruptedException {
        // No timeout requested: wait directly (still interruptible, which is
        // the desired cancellation path)
        process.waitFor();
        return true;
    }

    /** Drains stdout and merged stderr without allowing an unbounded log in memory. */
    private static CompletableFuture<String> captureOutput(Process commandProcess) {
        CompletableFuture<String> captured = new CompletableFuture<>();
        Thread.ofVirtual().name("odm-command-output").start(() -> {
            try (InputStreamReader reader = new InputStreamReader(
                    commandProcess.getInputStream(), StandardCharsets.UTF_8)) {
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
                    retained.append("\n\n[Output truncated by ODM]");
                }
                captured.complete(retained.toString());
            } catch (IOException e) {
                captured.completeExceptionally(e);
            }
        });
        return captured;
    }

    private static String awaitOutput(CompletableFuture<String> output)
            throws InterruptedException {
        try {
            return output.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return "[Could not read command output: "
                    + (cause == null ? e.getMessage() : cause.getMessage()) + "]";
        } catch (TimeoutException e) {
            return "[Command output reader did not finish]";
        }
    }
}
