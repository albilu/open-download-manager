package org.manager.download.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;

/**
 * Tests for the custom-command completion action: template substitution,
 * quote-aware tokenization, and real process execution.
 */
class ExecuteCommandActionTest {

    @Test
    void substitutesAllTemplateVariables() {
        Download download = new Download(URI.create("https://example.com/p/file.iso"));
        download.setName("file.iso");
        download.setDestination(Path.of("/tmp/odm"));
        download.setGid("abc123");

        String result = ExecuteCommandAction.substitute(
                "mv {file_path} {dir} && echo {filename} {url} {id} {gid}",
                download, Path.of("/tmp/odm/file.iso"));

        assertTrue(result.startsWith("mv /tmp/odm/file.iso /tmp/odm"));
        assertTrue(result.contains("file.iso"));
        assertTrue(result.contains("https://example.com/p/file.iso"));
        assertTrue(result.contains(download.getId()));
        assertTrue(result.contains("abc123"));
    }

    @Test
    void tokenizeRespectsQuotedArguments() {
        List<String> tokens = ExecuteCommandAction.tokenize(
                "notify-send \"Download done\" 'file name.iso' /tmp/x");
        assertEquals(List.of("notify-send", "Download done", "file name.iso", "/tmp/x"), tokens);
    }

    @Test
    void executeRunsCommandOnDownloadedFile(@TempDir Path tempDir) throws Exception {
        Path downloaded = tempDir.resolve("data.bin");
        Files.writeString(downloaded, "payload");

        Download download = new Download(URI.create("https://example.com/data.bin"));
        download.setName("data.bin");
        download.setDestination(tempDir);

        Path marker = tempDir.resolve("marker.txt");
        ExecuteCommandAction action = new ExecuteCommandAction(
                "touch " + marker + " {file_path}");

        assertTrue(action.execute(download), "command should succeed");
        assertTrue(Files.exists(marker), "command should have run with the file path");
        assertTrue(action.getOutput().contains("Exit code: 0"));
        assertTrue(action.getOutput().contains("Process output: (none)"));
        assertTrue(action.getOutput().contains("Result: Command completed successfully"));
    }

    @Test
    void capturesStandardOutputAndError(@TempDir Path tempDir) throws Exception {
        Path downloaded = Files.writeString(tempDir.resolve("data.bin"), "payload");
        Path script = tempDir.resolve("emit.sh");
        Files.writeString(script, "#!/bin/sh\necho stdout-line\necho stderr-line >&2\n");
        Files.setPosixFilePermissions(script,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Download download = new Download(URI.create("https://example.com/data.bin"));
        download.setDestination(tempDir);
        download.setOutputPaths(List.of(downloaded));
        ExecuteCommandAction action = new ExecuteCommandAction(
                script + " {file_path}");

        assertTrue(action.execute(download));
        assertTrue(action.getOutput().contains("Exit code: 0"));
        assertTrue(action.getOutput().contains("Process output:\nstdout-line"));
        assertTrue(action.getOutput().contains("stdout-line"));
        assertTrue(action.getOutput().contains("stderr-line"));
        assertTrue(action.getOutput().contains("Result: Command completed successfully"));
        assertEquals("Command completed successfully", action.getResultMessage());
    }

    @Test
    void failedCommandPersistsActualProcessDiagnostics(@TempDir Path tempDir) throws Exception {
        Path downloaded = Files.writeString(tempDir.resolve("data.bin"), "payload");
        Path script = tempDir.resolve("fail.sh");
        Files.writeString(script, """
                #!/bin/sh
                echo 'stdout command detail'
                echo 'stderr command failure' >&2
                exit 9
                """);
        Files.setPosixFilePermissions(script,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Download download = new Download(URI.create("https://example.com/data.bin"));
        download.setDestination(tempDir);
        download.setOutputPaths(List.of(downloaded));
        ExecuteCommandAction action = new ExecuteCommandAction(script + " {file_path}");
        AfterCompletionActionManager manager = new AfterCompletionActionManager();
        try {
            manager.addAction(download, action);

            manager.executeActions(download).get(5, TimeUnit.SECONDS);

            CompletionActionResult result = download.getCompletionActionResults().getFirst();
            assertEquals(CompletionActionResult.Status.FAILED, result.status());
            assertEquals("Custom command exited with code 9", result.message());
            assertTrue(result.output().contains("Exit code: 9"));
            assertTrue(result.output().contains("stdout command detail"));
            assertTrue(result.output().contains("stderr command failure"));
            assertTrue(result.output().contains("Result: Custom command exited with code 9"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @Timeout(15)
    void timedOutCommandRetainsPartialProcessOutput(@TempDir Path tempDir) throws Exception {
        Path downloaded = Files.writeString(tempDir.resolve("data.bin"), "payload");
        Path script = tempDir.resolve("timeout.sh");
        Files.writeString(script, "#!/bin/sh\necho before-timeout\nexec sleep 30\n");
        Files.setPosixFilePermissions(script,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Download download = new Download(URI.create("https://example.com/data.bin"));
        download.setDestination(tempDir);
        download.setOutputPaths(List.of(downloaded));
        ExecuteCommandAction action = new ExecuteCommandAction(
                script + " {file_path}", 1);

        assertFalse(action.execute(download));
        assertTrue(action.getOutput().contains("Process output:\nbefore-timeout"));
        assertTrue(action.getOutput().contains(
                "Result: Custom command timed out after 1 seconds"));
    }

    @Test
    void executeFailsOnNonexistentBinary() {
        Download download = new Download(URI.create("https://example.com/x"));
        download.setName("x");
        download.setDestination(Path.of("/tmp"));

        ExecuteCommandAction action = new ExecuteCommandAction(
                "this-binary-does-not-exist-12345 {file_path}");
        assertFalse(action.execute(download));
        assertTrue(action.getOutput().contains("Process output: (none)"));
        assertTrue(action.getOutput().contains("Could not run custom command"));
    }

    @Test
    void getTypeAndDescriptionExposeCommand() {
        ExecuteCommandAction action = new ExecuteCommandAction("mv {file_path} /tmp");
        assertEquals(AfterCompletionAction.ActionType.EXECUTE_COMMAND, action.getType());
        assertTrue(action.getDescription().contains("mv {file_path} /tmp"));
        assertEquals(AfterCompletionAction.Severity.MEDIUM, action.getSeverity());
    }
}
