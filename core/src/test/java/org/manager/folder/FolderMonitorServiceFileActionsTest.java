package org.manager.folder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the folder monitor's post-processing file actions (delete,
 * move-to-directory, move-to-trash) and the processed-file bookkeeping via
 * the public scanFolder contract, with a recording listener.
 */
@DisplayName("FolderMonitorServiceImpl file actions and bookkeeping")
class FolderMonitorServiceFileActionsTest {

    @TempDir
    Path tempDir;

    private FolderMonitorServiceImpl service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown().join();
        }
    }

    private record Event(Path folder, Path file, FolderMonitorSettings.FileAction action) {}

    private FolderMonitorServiceImpl newService(List<Event> processed) throws Exception {
        FolderMonitorServiceImpl impl = new FolderMonitorServiceImpl();
        impl.addFolderMonitorListener(new FolderMonitorListener() {
            @Override public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) { }
            @Override public void onFileModified(Path folderPath, Path filePath, FolderMonitorSettings settings) { }
            @Override public void onFileProcessed(Path folderPath, Path filePath,
                    FolderMonitorSettings.FileAction action, FolderMonitorSettings settings) {
                processed.add(new Event(folderPath, filePath, action));
            }
        });
        return impl;
    }

    private FolderMonitorSettings settings(FolderMonitorSettings.FileAction action, Path moveTo) {
        FolderMonitorSettings settings = new FolderMonitorSettings()
                .setEnabled(true)
                .setFileExtensions(java.util.Set.of(".txt"))
                .setFileAction(action)
                .setProcessExistingFiles(true)
                .setDebounceDelay(Duration.ZERO);
        if (moveTo != null) {
            settings.setMoveToDirectory(moveTo);
        }
        return settings;
    }

    @Test
    @DisplayName("scanFolder with DELETE removes matching files and reports them processed")
    void deleteAction() throws Exception {
        List<Event> processed = new CopyOnWriteArrayList<>();
        service = newService(processed);
        Path folder = Files.createDirectories(tempDir.resolve("watch-delete"));
        Files.writeString(folder.resolve("a.txt"), "a");
        Files.writeString(folder.resolve("b.txt"), "b");
        Files.writeString(folder.resolve("keep.bin"), "binary");

        service.scanFolder(folder, settings(FolderMonitorSettings.FileAction.DELETE, null))
                .get(30, TimeUnit.SECONDS);

        assertFalse(Files.exists(folder.resolve("a.txt")));
        assertFalse(Files.exists(folder.resolve("b.txt")));
        assertTrue(Files.exists(folder.resolve("keep.bin")), "non-matching files stay");
        assertEquals(2, processed.size());
        assertEquals(FolderMonitorSettings.FileAction.DELETE, processed.get(0).action());
    }

    @Test
    @DisplayName("scanFolder with MOVE_TO_DIRECTORY relocates files without clobbering")
    void moveToDirectoryAction() throws Exception {
        List<Event> processed = new CopyOnWriteArrayList<>();
        service = newService(processed);
        Path folder = Files.createDirectories(tempDir.resolve("watch-move"));
        Files.writeString(folder.resolve("moved.txt"), "content");
        Path target = Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(target.resolve("moved.txt"), "already-here");

        service.scanFolder(folder, settings(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY, target))
                .get(30, TimeUnit.SECONDS);

        assertFalse(Files.exists(folder.resolve("moved.txt")), "source must be gone after moving");
        assertEquals(2, Files.list(target).count(),
                "a colliding target must be renamed, not clobbered");
        assertEquals(1, processed.size());
    }

    @Test
    @DisplayName("scanFolder with MOVE_TO_TRASH files the document into the XDG trash")
    void moveToTrashAction() throws Exception {
        Path dataHome = tempDir.resolve("xdg-data");
        com.github.stefanbirkner.systemlambda.SystemLambda
                .withEnvironmentVariable("XDG_DATA_HOME", dataHome.toString()).execute(() -> {
            List<Event> processed = new CopyOnWriteArrayList<>();
            service = newService(processed);
            Path folder = Files.createDirectories(tempDir.resolve("watch-trash"));
            Files.writeString(folder.resolve("trashable.txt"), "t");

            service.scanFolder(folder, settings(FolderMonitorSettings.FileAction.MOVE_TO_TRASH, null))
                    .get(30, TimeUnit.SECONDS);

            assertFalse(Files.exists(folder.resolve("trashable.txt")),
                    "the file must leave the watched folder");
            Path trashed = dataHome.resolve("Trash/files/trashable.txt");
            assertTrue(Files.exists(trashed),
                    "trash semantics must file the document into the XDG trash");
            assertTrue(Files.exists(dataHome.resolve("Trash/info/trashable.txt.trashinfo")),
                    "a .trashinfo record must be written per the freedesktop spec");
            assertEquals(1, processed.size());
        });
    }

    @Test
    @DisplayName("duplicate scans debounce recently processed files")
    void repeatedScanDebounces() throws Exception {
        List<Event> processed = new CopyOnWriteArrayList<>();
        service = newService(processed);
        Path folder = Files.createDirectories(tempDir.resolve("watch-debounce"));
        Path target = Files.createDirectories(tempDir.resolve("target2"));
        Files.writeString(folder.resolve("one.txt"), "1");

        FolderMonitorSettings moveSettings =
                settings(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY, target);
        service.scanFolder(folder, moveSettings).get(30, TimeUnit.SECONDS);
        // put the file back; an immediate rescan must skip it (1-minute debounce)
        Files.writeString(folder.resolve("one.txt"), "1-again");
        service.scanFolder(folder, moveSettings).get(30, TimeUnit.SECONDS);

        assertEquals(1, processed.size(),
                "a file processed moments ago must be debounced on rescan");
    }

    @Test
    @DisplayName("monitoring statistics are reported")
    void monitoringStatistics() throws Exception {
        service = new FolderMonitorServiceImpl();
        Path folder = Files.createDirectories(tempDir.resolve("watch-stats"));
        FolderMonitorSettings stats = settings(FolderMonitorSettings.FileAction.KEEP, null);
        service.startMonitoring(folder, stats).get(30, TimeUnit.SECONDS);

        assertTrue(service.isMonitoring(folder));
        assertTrue(service.getMonitoredFolders().stream()
                .anyMatch(f -> f.getFileName().equals(folder.getFileName())),
                "the started folder must be listed as monitored: "
                        + service.getMonitoredFolders());
        assertNotNull(service.getMonitoringStatistics());
        service.stopAllMonitoring().get(30, TimeUnit.SECONDS);
        assertFalse(service.isMonitoring(folder));
    }
}
