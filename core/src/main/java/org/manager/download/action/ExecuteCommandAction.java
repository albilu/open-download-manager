package org.manager.download.action;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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

    private final String commandTemplate;
    private final int timeoutSeconds;
    private volatile Process process;
    private volatile boolean cancelled;

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
        if (commandTemplate.isBlank()) {
            LOGGER.warn("Custom command is empty; nothing to execute");
            return false;
        }
        Path filePath = resolveFilePath(download);
        if (filePath == null) {
            LOGGER.warn("Cannot run custom command: no file path for download " + download.getName());
            return false;
        }

        List<String> command = tokenize(substitute(commandTemplate, download, filePath));
        if (command.isEmpty()) {
            LOGGER.warn("Custom command produced no tokens");
            return false;
        }

        try {
            LOGGER.info("Executing configured after-completion command");
            process = new ProcessBuilder(command).inheritIO().start();
            boolean finished = timeoutSeconds > 0
                    ? process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                    : waitForUninterruptibly();
            if (cancelled) {
                return false;
            }
            if (!finished) {
                LOGGER.warn("Custom command timed out after " + timeoutSeconds + "s");
                process.destroyForcibly();
                return false;
            }
            int exit = process.exitValue();
            if (exit != 0) {
                LOGGER.warn("Custom command exited with code " + exit);
                return false;
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to execute custom command", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Custom command execution interrupted");
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
}
