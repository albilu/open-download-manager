package org.manager.download.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.manager.download.action.AfterCompletionAction.ActionType;
import org.manager.download.action.AfterCompletionAction.Severity;

@DisplayName("MoveFileAction moves completed downloads safely")
class MoveFileActionTest {

    @TempDir
    Path sourceDir;

    @TempDir
    Path targetDir;

    private Download completedDownload(Path outputFile) throws IOException {
        Download download = new Download(URI.create("https://example.test/file.zip"));
        download.setName("file.zip");
        download.setDestination(sourceDir);
        download.setStatus(Download.Status.COMPLETED);
        download.setOutputPaths(List.of(outputFile));
        return download;
    }

    @Test
    @DisplayName("moves a file into a destination directory keeping its name and updates the model")
    void movesIntoDirectory() throws IOException {
        Path file = Files.writeString(sourceDir.resolve("file.zip"), "payload");
        Download download = completedDownload(file);
        MoveFileAction action = new MoveFileAction(targetDir, false);

        assertTrue(action.execute(download));
        Path moved = targetDir.resolve("file.zip");
        assertTrue(Files.exists(moved), "file must exist at the destination");
        assertFalse(Files.exists(file), "move must not leave the original behind");
        assertEquals("payload", Files.readString(moved));
        assertEquals(List.of(moved), download.getOutputPaths());

        assertEquals(ActionType.MOVE_FILE, action.getType());
        assertEquals(Severity.MEDIUM, action.getSeverity());
        assertTrue(action.getDescription().contains(targetDir.toString()));
        assertTrue(action.cancel(), "move cannot be canceled but must report success");
        assertEquals(targetDir, action.getDestinationPath());
        assertFalse(action.isOverwriteExisting());
    }

    @Test
    @DisplayName("model output paths are repointed at the moved location")
    void modelOutputPathsAreUpdated() throws IOException {
        Path file = Files.writeString(sourceDir.resolve("renamed.bin"), "data");
        MoveFileAction action = new MoveFileAction(targetDir, false);
        Download download = completedDownload(file);

        assertTrue(action.execute(download));
        assertEquals(List.of(targetDir.resolve("renamed.bin")), download.getOutputPaths());
    }

    @Test
    @DisplayName("moves to an explicit file path, not just a directory")
    void movesToExplicitFileTarget() throws IOException {
        Path file = Files.writeString(sourceDir.resolve("a.log"), "log");
        Path targetFile = targetDir.resolve("renamed.log");
        MoveFileAction action = new MoveFileAction(targetFile, false);
        Download download = completedDownload(file);

        assertTrue(action.execute(download));
        assertEquals("log", Files.readString(targetFile));
        assertEquals(List.of(targetFile), download.getOutputPaths());
    }

    @Test
    @DisplayName("without overwrite an existing target fails the move and keeps both files")
    void collisionWithoutOverwriteFails() throws IOException {
        Path file = Files.writeString(sourceDir.resolve("file.zip"), "new");
        Path existing = Files.writeString(targetDir.resolve("file.zip"), "old");
        MoveFileAction action = new MoveFileAction(targetDir, false);

        assertFalse(action.execute(completedDownload(file)));
        assertEquals("old", Files.readString(existing), "existing target must be untouched");
        assertTrue(Files.exists(file), "source must be untouched after a failed move");
    }

    @Test
    @DisplayName("with overwrite the existing target is replaced")
    void collisionWithOverwriteReplaces() throws IOException {
        Path file = Files.writeString(sourceDir.resolve("file.zip"), "new");
        Files.writeString(targetDir.resolve("file.zip"), "old");
        MoveFileAction action = new MoveFileAction(targetDir, true);
        action.setOverwriteExisting(true); // setter must keep semantics identical

        assertTrue(action.execute(completedDownload(file)));
        assertEquals("new", Files.readString(targetDir.resolve("file.zip")));
        assertTrue(action.isOverwriteExisting());
    }

    @Test
    @DisplayName("missing source file fails without changes")
    void missingSourceFails() throws IOException {
        MoveFileAction action = new MoveFileAction(targetDir, false);
        Download download = completedDownload(sourceDir.resolve("ghost.zip"));

        assertFalse(action.execute(download));
        assertTrue(download.getOutputPaths().contains(sourceDir.resolve("ghost.zip")),
                "model must be untouched when nothing was moved");
    }

    @Test
    @DisplayName("a download without a destination cannot be moved")
    void missingDestinationFails() throws IOException {
        Download download = new Download(URI.create("https://example.test/x.zip"));
        download.setName("x.zip");
        download.setDestination(null);
        MoveFileAction action = new MoveFileAction(targetDir, false);

        assertFalse(action.execute(download));
        assertTrue(Files.list(targetDir).findAny().isEmpty(),
                "nothing may be written when the move is rejected");
    }

    @Test
    @DisplayName("missing output path fails even when a destination is set")
    void missingOutputPathFails() throws IOException {
        Download download = new Download(URI.create("https://example.test/y.zip"));
        download.setDestination(sourceDir);
        download.setOutputPaths(java.util.List.of());
        MoveFileAction action = new MoveFileAction(targetDir, false);

        assertFalse(action.execute(download));
    }

    @Test
    @DisplayName("destination parent directories are created on demand")
    void createsMissingDestinationDirectories() throws IOException {
        Path file = Files.writeString(sourceDir.resolve("deep.zip"), "deep");
        Path nestedTarget = targetDir.resolve("a").resolve("b").resolve("c");
        MoveFileAction action = new MoveFileAction(nestedTarget, false);
        Download download = completedDownload(file);

        assertTrue(action.execute(download));
        // a non-existing final target is treated as an explicit file path
        assertTrue(Files.exists(nestedTarget), "file must land at the explicit nested path");
        assertEquals("deep", Files.readString(nestedTarget));
        assertEquals(List.of(nestedTarget), download.getOutputPaths());
    }
}
