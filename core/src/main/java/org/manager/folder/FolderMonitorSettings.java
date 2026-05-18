package org.manager.folder;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Configuration settings for folder monitoring functionality. This class
 * contains all the settings needed to configure how a folder should be
 * monitored for file changes.
 */
public class FolderMonitorSettings {

    /**
     * Enum defining the action to take on monitored files after processing.
     */
    public enum FileAction {
        /**
         * Delete the file after processing
         */
        DELETE,
        /**
         * Move the file to trash after processing
         */
        MOVE_TO_TRASH,
        /**
         * Move the file to a specified directory
         */
        MOVE_TO_DIRECTORY,
        /**
         * Keep the file in place (no action)
         */
        KEEP
    }

    private Set<String> fileExtensions;
    private boolean recursive;
    private FileAction fileAction;
    private Path moveToDirectory;
    private boolean processExistingFiles;
    private boolean moveToTrash;
    private Duration debounceDelay;
    private boolean enabled;
    private int maxFilesPerBatch;
    private boolean caseSensitive;
    private Set<String> excludePatterns;
    private long maxFileSize;
    private long minFileSize;

    /**
     * Creates default folder monitor settings for torrent files.
     */
    public FolderMonitorSettings() {
        this.fileExtensions = new HashSet<>();
        this.fileExtensions.add(".torrent");
        this.fileExtensions.add(".meta4");
        this.fileExtensions.add(".metalink");
        this.recursive = false;
        this.moveToTrash = true;
        this.fileAction = moveToTrash ? FileAction.MOVE_TO_TRASH : FileAction.KEEP;
        this.moveToDirectory = null;
        this.processExistingFiles = true;
        this.debounceDelay = Duration.ofSeconds(2);
        this.enabled = true;
        this.maxFilesPerBatch = 10;
        this.caseSensitive = false;
        this.excludePatterns = new HashSet<>();
        this.maxFileSize = Long.MAX_VALUE; // No limit by default
        this.minFileSize = 0L;
    }

    /**
     * Gets the file extensions to monitor (including the dot, e.g.,
     * ".torrent").
     *
     * @return An unmodifiable set of file extensions
     */
    public Set<String> getFileExtensions() {
        return Collections.unmodifiableSet(fileExtensions);
    }

    /**
     * Sets the file extensions to monitor.
     *
     * @param fileExtensions The file extensions (including the dot)
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setFileExtensions(Set<String> fileExtensions) {
        this.fileExtensions = new HashSet<>(fileExtensions);
        return this;
    }

    /**
     * Adds a file extension to monitor.
     *
     * @param extension The file extension (including the dot)
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings addFileExtension(String extension) {
        this.fileExtensions.add(extension);
        return this;
    }

    /**
     * Removes a file extension from monitoring.
     *
     * @param extension The file extension to remove
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings removeFileExtension(String extension) {
        this.fileExtensions.remove(extension);
        return this;
    }

    /**
     * Checks if recursive monitoring is enabled.
     *
     * @return true if recursive monitoring is enabled
     */
    public boolean isRecursive() {
        return recursive;
    }

    /**
     * Sets whether to monitor subdirectories recursively.
     *
     * @param recursive true to enable recursive monitoring
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setRecursive(boolean recursive) {
        this.recursive = recursive;
        return this;
    }

    /**
     * Gets the action to take on files after processing.
     *
     * @return The file action
     */
    public FileAction getFileAction() {
        return fileAction;
    }

    /**
     * Sets the action to take on files after processing.
     *
     * @param fileAction The file action
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setFileAction(FileAction fileAction) {
        this.fileAction = fileAction;
        return this;
    }

    /**
     * Gets the directory to move files to when using MOVE_TO_DIRECTORY action.
     *
     * @return The move-to directory, or null if not set
     */
    public Path getMoveToDirectory() {
        return moveToDirectory;
    }

    /**
     * Sets the directory to move files to when using MOVE_TO_DIRECTORY action.
     *
     * @param moveToDirectory The directory path
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setMoveToDirectory(Path moveToDirectory) {
        this.moveToDirectory = moveToDirectory;
        return this;
    }

    /**
     * Checks if existing files should be processed when monitoring starts.
     *
     * @return true if existing files should be processed
     */
    public boolean isProcessExistingFiles() {
        return processExistingFiles;
    }

    public boolean isMoveToTrash() {
        return moveToTrash;
    }

    /**
     * Sets whether to process existing files when monitoring starts.
     *
     * @param processExistingFiles true to process existing files
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setProcessExistingFiles(boolean processExistingFiles) {
        this.processExistingFiles = processExistingFiles;
        return this;
    }

    public FolderMonitorSettings setMoveToTrash(boolean moveToTrash) {
        this.moveToTrash = moveToTrash;
        return this;
    }

    /**
     * Gets the debounce delay for file events.
     *
     * @return The debounce delay
     */
    public Duration getDebounceDelay() {
        return debounceDelay;
    }

    /**
     * Sets the debounce delay to avoid processing files multiple times when
     * they are being written or modified rapidly.
     *
     * @param debounceDelay The debounce delay
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setDebounceDelay(Duration debounceDelay) {
        this.debounceDelay = debounceDelay;
        return this;
    }

    /**
     * Checks if monitoring is enabled.
     *
     * @return true if monitoring is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether monitoring is enabled.
     *
     * @param enabled true to enable monitoring
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Gets the maximum number of files to process in a single batch.
     *
     * @return The maximum files per batch
     */
    public int getMaxFilesPerBatch() {
        return maxFilesPerBatch;
    }

    /**
     * Sets the maximum number of files to process in a single batch.
     *
     * @param maxFilesPerBatch The maximum files per batch
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setMaxFilesPerBatch(int maxFilesPerBatch) {
        this.maxFilesPerBatch = maxFilesPerBatch;
        return this;
    }

    /**
     * Checks if file extension matching is case sensitive.
     *
     * @return true if case sensitive
     */
    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    /**
     * Sets whether file extension matching should be case sensitive.
     *
     * @param caseSensitive true for case sensitive matching
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
        return this;
    }

    /**
     * Gets the patterns for files to exclude from monitoring.
     *
     * @return An unmodifiable set of exclude patterns
     */
    public Set<String> getExcludePatterns() {
        return Collections.unmodifiableSet(excludePatterns);
    }

    /**
     * Sets the patterns for files to exclude from monitoring. Patterns support
     * wildcards (* and ?).
     *
     * @param excludePatterns The exclude patterns
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setExcludePatterns(Set<String> excludePatterns) {
        this.excludePatterns = new HashSet<>(excludePatterns);
        return this;
    }

    /**
     * Adds an exclude pattern.
     *
     * @param pattern The pattern to add
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings addExcludePattern(String pattern) {
        this.excludePatterns.add(pattern);
        return this;
    }

    /**
     * Gets the maximum file size to process (in bytes).
     *
     * @return The maximum file size
     */
    public long getMaxFileSize() {
        return maxFileSize;
    }

    /**
     * Sets the maximum file size to process (in bytes).
     *
     * @param maxFileSize The maximum file size
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
        return this;
    }

    /**
     * Gets the minimum file size to process (in bytes).
     *
     * @return The minimum file size
     */
    public long getMinFileSize() {
        return minFileSize;
    }

    /**
     * Sets the minimum file size to process (in bytes).
     *
     * @param minFileSize The minimum file size
     * @return This settings object for method chaining
     */
    public FolderMonitorSettings setMinFileSize(long minFileSize) {
        this.minFileSize = minFileSize;
        return this;
    }

    /**
     * Creates a copy of this settings object.
     *
     * @return A new FolderMonitorSettings object with the same configuration
     */
    public FolderMonitorSettings copy() {
        FolderMonitorSettings copy = new FolderMonitorSettings();
        copy.fileExtensions = new HashSet<>(this.fileExtensions);
        copy.recursive = this.recursive;
        copy.fileAction = this.fileAction;
        copy.moveToDirectory = this.moveToDirectory;
        copy.processExistingFiles = this.processExistingFiles;
        copy.moveToTrash = this.moveToTrash;
        copy.debounceDelay = this.debounceDelay;
        copy.enabled = this.enabled;
        copy.maxFilesPerBatch = this.maxFilesPerBatch;
        copy.caseSensitive = this.caseSensitive;
        copy.excludePatterns = new HashSet<>(this.excludePatterns);
        copy.maxFileSize = this.maxFileSize;
        copy.minFileSize = this.minFileSize;
        return copy;
    }

    @Override
    public String toString() {
        return "FolderMonitorSettings{"
                + "fileExtensions=" + fileExtensions
                + ", recursive=" + recursive
                + ", fileAction=" + fileAction
                + ", moveToDirectory=" + moveToDirectory
                + ", processExistingFiles=" + processExistingFiles
                + ", moveToTrash=" + moveToTrash
                + ", debounceDelay=" + debounceDelay
                + ", enabled=" + enabled
                + ", maxFilesPerBatch=" + maxFilesPerBatch
                + ", caseSensitive=" + caseSensitive
                + ", excludePatterns=" + excludePatterns
                + ", maxFileSize=" + maxFileSize
                + ", minFileSize=" + minFileSize
                + '}';
    }
}
