package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LastChosenFolderTest {
    @TempDir Path directory;

    @Test void persistsTheLatestFolderAcrossInstancesWithoutChangingItsName() throws Exception {
        Path state = directory.resolve("state/last-chosen-folder");
        LastChosenFolder history = new LastChosenFolder(state);
        assertNull(history.load());
        Path first = Files.createDirectory(directory.resolve("Téléchargements with spaces "));
        history.remember(first.resolve("../" + first.getFileName()));
        assertEquals(first, new LastChosenFolder(state).load());

        Path second = Files.createDirectory(directory.resolve("another\nfolder"));
        history.remember(second);
        assertEquals(second, new LastChosenFolder(state).load());
        try (var files = Files.list(state.getParent())) {
            assertEquals(java.util.List.of(state), files.toList(), "no temporary files remain");
        }
    }

    @Test void ignoresMissingFoldersAndInvalidStoredPaths() throws Exception {
        Path state = directory.resolve("last-chosen-folder");
        LastChosenFolder history = new LastChosenFolder(state);
        Path folder = Files.createDirectory(directory.resolve("removed"));
        history.remember(folder);
        Files.delete(folder);
        assertNull(history.load());
        for (String value : java.util.List.of("", "relative/folder", "bad\u0000path", state.toString())) {
            Files.writeString(state, value);
            assertNull(history.load(), value);
        }
    }

    @Test void nonFolderSelectionsDoNotReplaceTheRememberedFolder() throws Exception {
        Path state = directory.resolve("last-chosen-folder");
        LastChosenFolder history = new LastChosenFolder(state);
        history.remember(directory);
        history.remember(null);
        history.remember(directory.resolve("missing"));
        history.remember(Files.createFile(directory.resolve("file")));
        assertEquals(directory, history.load());
    }

    @Test void unavailableStateStorageDoesNotPreventChoosingAFolder() throws Exception {
        Path blocked = Files.createFile(directory.resolve("not-a-directory"));
        LastChosenFolder history = new LastChosenFolder(blocked.resolve("last-chosen-folder"));
        assertDoesNotThrow(() -> history.remember(directory));
        assertNull(history.load());
    }
}
