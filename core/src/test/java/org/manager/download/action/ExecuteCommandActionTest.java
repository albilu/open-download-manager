package org.manager.download.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
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
    }

    @Test
    void executeFailsOnNonexistentBinary() {
        Download download = new Download(URI.create("https://example.com/x"));
        download.setName("x");
        download.setDestination(Path.of("/tmp"));

        ExecuteCommandAction action = new ExecuteCommandAction(
                "this-binary-does-not-exist-12345 {file_path}");
        assertFalse(action.execute(download));
    }

    @Test
    void getTypeAndDescriptionExposeCommand() {
        ExecuteCommandAction action = new ExecuteCommandAction("mv {file_path} /tmp");
        assertEquals(AfterCompletionAction.ActionType.EXECUTE_COMMAND, action.getType());
        assertTrue(action.getDescription().contains("mv {file_path} /tmp"));
        assertEquals(AfterCompletionAction.Severity.MEDIUM, action.getSeverity());
    }
}
