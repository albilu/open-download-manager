package org.manager.download.action;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.manager.download.Download;

/**
 * After completion action that moves the downloaded file to a different
 * location.
 */
public class MoveFileAction implements AfterCompletionAction {

    private static final Logger LOGGER = Logger.getLogger(MoveFileAction.class.getName());

    private final Path destinationPath;
    private boolean overwriteExisting;
    private Path sourceFile;

    /**
     * Creates a new MoveFileAction.
     *
     * @param destinationPath   The destination path where the file should be
     *                          moved
     * @param overwriteExisting Whether to overwrite existing files at the
     *                          destination
     */
    public MoveFileAction(Path destinationPath, boolean overwriteExisting) {
        this.destinationPath = destinationPath;
        this.overwriteExisting = overwriteExisting;
    }

    @Override
    public boolean execute(Download download) {
        // If no download destination is set, we can't move the file
        if (download.getDestination() == null) {
            LOGGER.warning("Cannot move file: download destination is not set");
            return false;
        }

        sourceFile = download.getPrimaryOutputPath();
        if (sourceFile == null) {
            LOGGER.warning("Cannot move file: output path is unknown");
            return false;
        }

        // Check if source file exists
        if (!Files.exists(sourceFile)) {
            LOGGER.warning("Cannot move file: source file does not exist: " + sourceFile);
            return false;
        }

        try {
            // Create destination directory if it doesn't exist
            Files.createDirectories(destinationPath.getParent());

            // Determine target path
            Path targetPath = destinationPath;
            if (Files.isDirectory(destinationPath)) {
                targetPath = destinationPath.resolve(sourceFile.getFileName());
            }

            // Move the file
            StandardCopyOption[] options = overwriteExisting
                    ? new StandardCopyOption[] { StandardCopyOption.REPLACE_EXISTING }
                    : new StandardCopyOption[] {};

            Files.move(sourceFile, targetPath, options);
            download.setOutputPaths(java.util.List.of(targetPath));
            LOGGER.info("Moved file from " + sourceFile + " to " + targetPath);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to move file: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public ActionType getType() {
        return ActionType.MOVE_FILE;
    }

    @Override
    public String getDescription() {
        return "Move file to " + destinationPath + (overwriteExisting ? " (overwrite if exists)" : "");
    }

    @Override
    public boolean cancel() {
        // Nothing to cancel for a move operation
        return true;
    }

    @Override
    public Severity getSeverity() {
        return Severity.MEDIUM;
    }

    /**
     * Get the destination path for this move action.
     *
     * @return The destination path
     */
    public Path getDestinationPath() {
        return destinationPath;
    }

    /**
     * Check if this action will overwrite existing files.
     *
     * @return true if existing files will be overwritten, false otherwise
     */
    public boolean isOverwriteExisting() {
        return overwriteExisting;
    }

    /**
     * Set whether to overwrite existing files.
     *
     * @param overwriteExisting Whether to overwrite existing files
     */
    public void setOverwriteExisting(boolean overwriteExisting) {
        this.overwriteExisting = overwriteExisting;
    }
}
