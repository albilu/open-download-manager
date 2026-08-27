package org.manager.folder;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.manager.util.DescriptorStaging;

/**
 * Implementation of FolderMonitorService using Java NIO WatchService for
 * efficient folder monitoring with support for torrent file detection and
 * automatic download queue integration.
 */
public class FolderMonitorServiceImpl implements FolderMonitorService {

    private static final Logger LOGGER = Logger.getLogger(FolderMonitorServiceImpl.class.getName());

    private final WatchService watchService;
    /** Exclusive root watched descriptors are staged into before dispatch. */
    private final Path descriptorStagingRoot;
    private final Map<Path, WatchKey> watchKeys = new ConcurrentHashMap<>();
    private final Map<Path, Set<WatchKey>> recursiveWatchKeys = new ConcurrentHashMap<>();
    private final Map<Path, FolderMonitorSettings> folderSettings = new ConcurrentHashMap<>();
    private final List<FolderMonitorListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final Map<Path, ScheduledFuture<?>> debounceTimers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong processedFilesCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final Map<String, Object> statistics = new ConcurrentHashMap<>();
    private final Map<Path, Long> processedFiles = new ConcurrentHashMap<>(); // Track processed files with timestamp
    /** Files already announced via onFileAdded (dedupes CREATE vs later MODIFY eligibility). */
    private final Set<Path> announcedFiles = ConcurrentHashMap.newKeySet();
    /** Files whose processing error was already reported (exactly-once per round). */
    private final Set<Path> fileErrorReported = ConcurrentHashMap.newKeySet();
    private static final long PROCESSED_FILES_CLEANUP_INTERVAL_MS = 60 * 60 * 1000; // 1 hour
    private static final long PROCESSED_FILES_MAX_AGE_MS = 24 * 60 * 60 * 1000; // 24 hours
    private static final int PROCESSED_FILES_MAX_SIZE = 10000; // Maximum number of tracked files
    private volatile long lastCleanupTime = System.currentTimeMillis();

    private Future<?> monitoringTask;

    /**
     * Creates a new FolderMonitorServiceImpl instance staging watched
     * descriptors beneath ODM's default staging root.
     *
     * @throws IOException If the WatchService cannot be created
     */
    public FolderMonitorServiceImpl() throws IOException {
        this(DescriptorStaging.stagingRoot());
    }

    /**
     * Creates a new FolderMonitorServiceImpl instance with an explicit
     * descriptor staging root.
     *
     * @param descriptorStagingRoot exclusive directory watched torrent and
     *            Metalink descriptors are copied into before listeners are
     *            notified
     * @throws IOException If the WatchService cannot be created
     */
    public FolderMonitorServiceImpl(Path descriptorStagingRoot) throws IOException {
        this.descriptorStagingRoot = java.util.Objects.requireNonNull(descriptorStagingRoot,
                "descriptorStagingRoot");
        this.watchService = FileSystems.getDefault().newWatchService();
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "FolderMonitor-Worker");
            t.setDaemon(true);
            return t;
        });
        this.scheduledExecutorService = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "FolderMonitor-Scheduler");
            t.setDaemon(true);
            return t;
        });
        initializeStatistics();
    }

    /**
     * Starts the monitoring loop and the cleanup schedule on first use.
     * Constructors must stay cheap: the manager builds this service eagerly,
     * and the loop thread + hourly cleanup ran even when no folder was ever
     * monitored.
     */
    private synchronized void ensureMonitoringLoopStarted() {
        if (running.get()) {
            return;
        }
        running.set(true);
        monitoringTask = executorService.submit(this::monitoringLoop);
        // Schedule periodic cleanup of processed files (with the loop)
        scheduledExecutorService.scheduleAtFixedRate(
                this::cleanupProcessedFiles,
                PROCESSED_FILES_CLEANUP_INTERVAL_MS,
                PROCESSED_FILES_CLEANUP_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        LOGGER.info("Folder monitoring service started");
    }

    private void initializeStatistics() {
        statistics.put("processedFiles", 0L);
        statistics.put("errors", 0L);
        statistics.put("monitoredFolders", 0);
        statistics.put("startTime", System.currentTimeMillis());
    }

    @Override
    public CompletableFuture<Void> startMonitoring(Path folderPath, FolderMonitorSettings settings) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (!Files.exists(folderPath)) {
                    throw new IllegalArgumentException("Folder does not exist: " + folderPath);
                }

                if (!Files.isDirectory(folderPath)) {
                    throw new IllegalArgumentException("Path is not a directory: " + folderPath);
                }

                // Stop existing monitoring for this folder if any
                stopMonitoringInternal(folderPath);

                // Register the folder for monitoring
                WatchKey watchKey = registerFolder(folderPath, settings);
                watchKeys.put(folderPath, watchKey);
                folderSettings.put(folderPath, settings.copy());

                // The loop starts with the first monitored folder (lazy)
                ensureMonitoringLoopStarted();

                // Process existing files if enabled
                if (settings.isProcessExistingFiles()) {
                    scanFolderInternal(folderPath, settings);
                }

                updateStatistics();
                notifyListeners(listener -> listener.onMonitoringStarted(folderPath, settings));

                LOGGER.info("Started monitoring folder: " + folderPath + " with settings: " + settings);

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to start monitoring folder: " + folderPath, e);
                throw new RuntimeException("Failed to start monitoring folder: " + folderPath, e);
            }
        }, executorService);
    }

    private WatchKey registerFolder(Path folderPath, FolderMonitorSettings settings) throws IOException {
        WatchKey watchKey = folderPath.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);

        // If recursive monitoring is enabled, register subdirectories too
        if (settings.isRecursive()) {
            Set<WatchKey> recursiveKeys = ConcurrentHashMap.newKeySet();
            Files.walkFileTree(folderPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(folderPath)) {
                        WatchKey subDirKey = dir.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE);
                        recursiveKeys.add(subDirKey);
                        watchKeys.put(dir, subDirKey);
                        // Recursive subdirectories must resolve to the root's
                        // settings, otherwise monitoringLoop drops their events
                        folderSettings.put(dir, settings.copy());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            recursiveWatchKeys.put(folderPath, recursiveKeys);
        }

        return watchKey;
    }

    @Override
    public CompletableFuture<Void> stopMonitoring(Path folderPath) {
        return CompletableFuture.runAsync(() -> {
            stopMonitoringInternal(folderPath);
        }, executorService);
    }

    private void stopMonitoringInternal(Path folderPath) {
        WatchKey watchKey = watchKeys.remove(folderPath);
        if (watchKey != null) {
            watchKey.cancel();
        }

        // Cancel all recursive watch keys for this folder
        Set<WatchKey> recursiveKeys = recursiveWatchKeys.remove(folderPath);
        if (recursiveKeys != null) {
            for (WatchKey key : recursiveKeys) {
                key.cancel();
            }
            // Remove recursive paths from watchKeys map
            watchKeys.entrySet().removeIf(entry -> {
                Path path = entry.getKey();
                return path.startsWith(folderPath) && !path.equals(folderPath);
            });
        }

        FolderMonitorSettings settings = folderSettings.remove(folderPath);

        // Remove settings registered for recursively monitored subdirectories
        folderSettings.keySet().removeIf(path -> path.startsWith(folderPath) && !path.equals(folderPath));

        // Clear announced-file tracking for this folder (see handleFileEvent)
        announcedFiles.removeIf(path -> path.startsWith(folderPath));

        // Cancel any pending debounce timers for files in this folder
        List<Path> timersToCancel = new ArrayList<>();
        for (Path filePath : debounceTimers.keySet()) {
            if (filePath.startsWith(folderPath)) {
                timersToCancel.add(filePath);
            }
        }

        for (Path filePath : timersToCancel) {
            ScheduledFuture<?> timer = debounceTimers.remove(filePath);
            if (timer != null) {
                timer.cancel(false);
            }
        }

        // Clear processed files for this folder to allow reprocessing if monitoring is
        // restarted
        processedFiles.entrySet().removeIf(entry -> entry.getKey().startsWith(folderPath));

        updateStatistics();

        if (settings != null) {
            notifyListeners(listener -> listener.onMonitoringStopped(folderPath, settings));
        }

        LOGGER.info("Stopped monitoring folder: " + folderPath);
    }

    @Override
    public CompletableFuture<Void> stopAllMonitoring() {
        // Check if executor is available, if not execute synchronously
        if (executorService == null || executorService.isShutdown()) {
            stopAllMonitoringSynchronously();
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            List<Path> foldersToStop = new ArrayList<>(watchKeys.keySet());
            for (Path folderPath : foldersToStop) {
                stopMonitoringInternal(folderPath);
            }
            // Clear all processed files when stopping all monitoring
            processedFiles.clear();
            LOGGER.info("Stopped monitoring all folders");
        }, executorService);
    }

    @Override
    public List<Path> getMonitoredFolders() {
        return new ArrayList<>(folderSettings.keySet());
    }

    @Override
    public boolean isMonitoring(Path folderPath) {
        return folderSettings.containsKey(folderPath);
    }

    @Override
    public FolderMonitorSettings getMonitoringSettings(Path folderPath) {
        FolderMonitorSettings settings = folderSettings.get(folderPath);
        return settings != null ? settings.copy() : null;
    }

    @Override
    public CompletableFuture<Void> updateMonitoringSettings(Path folderPath, FolderMonitorSettings settings) {
        return CompletableFuture.runAsync(() -> {
            if (!isMonitoring(folderPath)) {
                throw new IllegalArgumentException("Folder is not being monitored: " + folderPath);
            }

            folderSettings.put(folderPath, settings.copy());
            LOGGER.info("Updated monitoring settings for folder: " + folderPath);
        }, executorService);
    }

    @Override
    public void addFolderMonitorListener(FolderMonitorListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeFolderMonitorListener(FolderMonitorListener listener) {
        listeners.remove(listener);
    }

    @Override
    public CompletableFuture<Void> scanFolder(Path folderPath, FolderMonitorSettings settings) {
        return CompletableFuture.runAsync(() -> {
            scanFolderInternal(folderPath, settings);
        }, executorService);
    }

    private void scanFolderInternal(Path folderPath, FolderMonitorSettings settings) {
        if (!settings.isEnabled()) {
            LOGGER.info("Skipping folder scan - monitoring disabled: " + folderPath);
            return;
        }
        try {
            LOGGER.info("Scanning folder for existing files: " + folderPath);

            List<Path> filesToProcess = new ArrayList<>();

            if (settings.isRecursive()) {
                Files.walkFileTree(folderPath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (shouldProcessFile(file, settings)) {
                            filesToProcess.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(folderPath)) {
                    for (Path file : stream) {
                        if (Files.isRegularFile(file) && shouldProcessFile(file, settings)) {
                            filesToProcess.add(file);
                        }
                    }
                }
            }

            // Process files in batches
            int batchSize = settings.getMaxFilesPerBatch();
            for (int i = 0; i < filesToProcess.size(); i += batchSize) {
                int end = Math.min(i + batchSize, filesToProcess.size());
                List<Path> batch = filesToProcess.subList(i, end);
                processBatch(folderPath, batch, settings);
            }

            LOGGER.info("Completed scanning folder: " + folderPath + ", processed " + filesToProcess.size() + " files");

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error scanning folder: " + folderPath, e);
            errorCount.incrementAndGet();
            updateStatistics();
        }
    }

    private void processBatch(Path folderPath, List<Path> files, FolderMonitorSettings settings) {
        for (Path file : files) {
            // Notify detection for every file found by a scan. announcedFiles
            // dedupes against a later MODIFY re-announcing the same file.
            announcedFiles.add(file);
            // A failed announcement (staging or listener dispatch) must not
            // proceed to the disposition: the original stays in place and
            // the error was already reported
            if (announceFileAdded(folderPath, file, settings)) {
                try {
                    processFile(folderPath, file, settings);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error processing file: " + file, e);
                    errorCount.incrementAndGet();
                    notifyListeners(listener -> listener.onFileProcessingError(folderPath, file, e, settings));
                }
            }
        }
        updateStatistics();
    }

    private void monitoringLoop() {
        while (running.get()) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }

                Path folderPath = findFolderPath(key);
                if (folderPath == null) {
                    key.reset();
                    continue;
                }

                FolderMonitorSettings settings = folderSettings.get(folderPath);
                if (settings == null || !settings.isEnabled()) {
                    key.reset();
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        LOGGER.warning("Watch event overflow occurred for folder: " + folderPath
                                + "; rescanning to recover lost events");
                        // Events were dropped by the OS: a rescan is the only
                        // way to catch files that appeared during the overflow
                        try {
                            scanFolderInternal(folderPath, settings);
                        } catch (Exception scanError) {
                            LOGGER.log(Level.WARNING, "Overflow rescan failed for " + folderPath, scanError);
                        }
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();
                    Path filePath = folderPath.resolve(fileName);

                    handleFileEvent(folderPath, filePath, kind, settings);
                }

                boolean valid = key.reset();
                if (!valid) {
                    LOGGER.warning("Watch key is no longer valid for folder: " + folderPath);
                    stopMonitoringInternal(folderPath);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error in monitoring loop", e);
                errorCount.incrementAndGet();
                updateStatistics();
            }
        }
    }

    private Path findFolderPath(WatchKey key) {
        for (Map.Entry<Path, WatchKey> entry : watchKeys.entrySet()) {
            if (entry.getValue().equals(key)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void handleFileEvent(Path folderPath, Path filePath, WatchEvent.Kind<?> kind,
            FolderMonitorSettings settings) {
        // Double-check that monitoring is still active before processing any events
        if (!isMonitoring(folderPath) || !folderSettings.containsKey(folderPath)) {
            LOGGER.info("Ignoring file event - monitoring stopped for folder: " + folderPath + ", file: " + filePath);
            return;
        }

        // Get current settings to ensure they're up to date
        FolderMonitorSettings currentSettings = folderSettings.get(folderPath);
        if (currentSettings == null || !currentSettings.isEnabled()) {
            LOGGER.info("Ignoring file event - monitoring disabled: " + folderPath + ", file: " + filePath);
            return;
        }

        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            // If it's a directory and recursive monitoring is enabled, register it
            if (Files.isDirectory(filePath) && currentSettings.isRecursive()) {
                try {
                    WatchKey subDirKey = filePath.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);

                    // Track the new subdirectory watch key
                    watchKeys.put(filePath, subDirKey);
                    recursiveWatchKeys.computeIfAbsent(folderPath, k -> ConcurrentHashMap.newKeySet()).add(subDirKey);
                    // The subdirectory must resolve to the root's settings,
                    // otherwise monitoringLoop drops its events
                    folderSettings.put(filePath, currentSettings.copy());

                    LOGGER.info("Registered new subdirectory for monitoring: " + filePath);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to register subdirectory: " + filePath, e);
                }
            } else if (Files.isRegularFile(filePath)) {
                scheduleFileProcessing(folderPath, filePath, currentSettings);
            }
            // The onFileAdded announcement is made from the debounced task
            // (see scheduleFileProcessing): announcing at CREATE raced files
            // still being copied — validation failed, the path was
            // permanently marked announced, and the download was never
            // created even though the file later completed.
        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            if (Files.isRegularFile(filePath)) {
                scheduleFileProcessing(folderPath, filePath, currentSettings);
            }
            // Only notify listeners about files that should be processed.
            // The added-announcement itself comes from the debounced task.
            if (Files.isRegularFile(filePath) && shouldProcessFile(filePath, currentSettings)) {
                notifyFileEvent(folderPath, filePath, currentSettings,
                        listener -> listener.onFileModified(folderPath, filePath, currentSettings));
            }
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            cancelScheduledProcessing(filePath);
            announcedFiles.remove(filePath);

            // If it's a directory being deleted, clean up its watch key
            if (currentSettings.isRecursive()) {
                WatchKey deletedKey = watchKeys.remove(filePath);
                if (deletedKey != null) {
                    deletedKey.cancel();
                    // Remove from recursive keys set
                    Set<WatchKey> recursiveKeys = recursiveWatchKeys.get(folderPath);
                    if (recursiveKeys != null) {
                        recursiveKeys.remove(deletedKey);
                    }
                }
            }

            notifyListeners(listener -> listener.onFileDeleted(folderPath, filePath, currentSettings));
        }
    }

    private void scheduleFileProcessing(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        // Check if monitoring is still active before even scheduling
        if (!isMonitoring(folderPath) || !shouldProcessFile(filePath, settings)) {
            return;
        }

        // Cancel any existing timer for this file
        cancelScheduledProcessing(filePath);

        // Schedule processing after debounce delay
        ScheduledFuture<?> timer = scheduledExecutorService.schedule(() -> {
            debounceTimers.remove(filePath);
            // Check if monitoring is still active and folder still exists in settings
            FolderMonitorSettings currentSettings = folderSettings.get(folderPath);
            if (currentSettings != null && currentSettings.isEnabled()
                    && Files.exists(filePath) && shouldProcessFile(filePath, currentSettings)
                    && isMonitoring(folderPath)) {
                // Announce once the file has settled (quiet for the whole
                // debounce window), then run the file action. Announce first:
                // the torrent/metalink listener creates the download
                // synchronously, so it always observes the complete file.
                if (announcedFiles.add(filePath)) {
                    // A failed announcement (staging or listener dispatch)
                    // must not proceed to the disposition: the original
                    // stays in place and the error was already reported
                    if (!announceFileAdded(folderPath, filePath, currentSettings)) {
                        return;
                    }
                }
                processFile(folderPath, filePath, currentSettings);
            } else {
                LOGGER.fine("Skipping scheduled processing - monitoring stopped or file invalid: " + filePath);
            }
        }, settings.getDebounceDelay().toMillis(), TimeUnit.MILLISECONDS);

        debounceTimers.put(filePath, timer);
    }

    private void cancelScheduledProcessing(Path filePath) {
        ScheduledFuture<?> timer = debounceTimers.remove(filePath);
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private boolean shouldProcessFile(Path filePath, FolderMonitorSettings settings) {
        try {
            if (!Files.isRegularFile(filePath)) {
                return false;
            }

            String fileName = filePath.getFileName().toString();

            // Check file extension
            boolean matchesExtension = false;
            for (String extension : settings.getFileExtensions()) {
                if (settings.isCaseSensitive()) {
                    if (fileName.endsWith(extension)) {
                        matchesExtension = true;
                        break;
                    }
                } else {
                    if (fileName.toLowerCase().endsWith(extension.toLowerCase())) {
                        matchesExtension = true;
                        break;
                    }
                }
            }

            if (!matchesExtension) {
                return false;
            }

            // Check exclude patterns
            for (String pattern : settings.getExcludePatterns()) {
                if (matchesPattern(fileName, pattern)) {
                    return false;
                }
            }

            // Check file size
            long fileSize = Files.size(filePath);
            if (fileSize < settings.getMinFileSize() || fileSize > settings.getMaxFileSize()) {
                return false;
            }

            return true;

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error checking file: " + filePath, e);
            return false;
        }
    }

    private boolean matchesPattern(String fileName, String pattern) {
        // Simple wildcard pattern matching
        String regex = pattern.replace("*", ".*").replace("?", ".");
        return fileName.matches(regex);
    }

    private void processFile(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        try {
            LOGGER.info("Processing file: " + filePath);

            // Double-check that settings are still active before processing.
            // Note: the isMonitoring() check is deliberately absent so a
            // standalone scanFolder() (which does not register the folder)
            // still processes files, per the FolderMonitorService contract.
            if (!settings.isEnabled()) {
                LOGGER.info("Skipping file processing - monitoring disabled: " + filePath);
                return;
            }

            // Check if file has already been processed to prevent duplicates
            long currentTime = System.currentTimeMillis();
            Long lastProcessedTime = processedFiles.get(filePath);
            if (lastProcessedTime != null && (currentTime - lastProcessedTime) < 60000) { // 1 minute debounce
                LOGGER.info("Skipping file processing - already processed recently: " + filePath);
                return;
            }

            // Mark file as processed with current timestamp
            processedFiles.put(filePath, currentTime);

            // Trigger cleanup if needed
            if (shouldCleanupProcessedFiles()) {
                cleanupProcessedFiles();
            }

            // Execute the configured file action
            executeFileAction(folderPath, filePath, settings);

            processedFilesCount.incrementAndGet();
            fileErrorReported.remove(filePath); // allow future rounds to report again
            updateStatistics();

            LOGGER.info("Successfully processed file: " + filePath);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing file: " + filePath, e);
            reportFileError(folderPath, filePath, e, settings);
        }
    }

    private void executeFileAction(Path folderPath, Path filePath, FolderMonitorSettings settings) throws IOException {
        FolderMonitorSettings.FileAction action = settings.getFileAction();

        // Check file permissions before processing. Errors are thrown only:
        // processFile's catch block performs the single onFileProcessingError
        // notification (notifying here as well would double-notify).
        if (!Files.isReadable(filePath)) {
            String errorMsg = "Cannot access file due to permissions";
            throw new IOException(errorMsg + ": " + filePath);
        }

        // Validate file format for specific file types
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".torrent") || fileName.endsWith(".metalink")) {
            if (!isValidFileFormat(filePath, fileName)) {
                String errorMsg = "Invalid file format";
                throw new IOException(errorMsg + ": " + filePath);
            }
        }

        notifyListeners(listener -> listener.onFileProcessed(folderPath, filePath, action, settings));

        switch (action) {
            case DELETE -> {
                Files.deleteIfExists(filePath);
                LOGGER.info("Deleted file: " + filePath);
            }
            case MOVE_TO_TRASH -> {
                moveToTrash(filePath);
                LOGGER.info("Moved file to trash: " + filePath);
            }
            case MOVE_TO_DIRECTORY -> {
                Path moveToDir = settings.getMoveToDirectory();
                if (moveToDir != null) {
                    Files.createDirectories(moveToDir);
                    // Reserve a collision-safe destination name: never
                    // REPLACE_EXISTING — a previously processed file with
                    // the same name must not be silently clobbered
                    Path targetPath = moveToDir.resolve(filePath.getFileName());
                    int attempt = 0;
                    while (true) {
                        try {
                            Files.move(filePath, targetPath);
                            break;
                        } catch (FileAlreadyExistsException collision) {
                            attempt++;
                            targetPath = DescriptorStaging.collisionSafeTarget(
                                    moveToDir, filePath.getFileName().toString(), attempt);
                        }
                    }
                    LOGGER.info("Moved file to: " + targetPath);
                } else {
                    LOGGER.warning("Move to directory specified but no target directory set for file: " + filePath);
                }
            }
            case KEEP -> {
                // Do nothing, just keep the file
                LOGGER.info("Keeping file: " + filePath);
            }
            default ->
                throw new IllegalArgumentException("Unknown file action: " + action);
        }
    }

    private boolean isValidFileFormat(Path filePath, String fileName) {
        try {
            // Basic validation - check if file is readable and has minimum size
            if (Files.size(filePath) < 10) {
                return false;
            }

            // For torrent files, check for basic structure
            if (fileName.endsWith(".torrent")) {
                byte[] header = new byte[20];
                try (var inputStream = Files.newInputStream(filePath)) {
                    int bytesRead = inputStream.read(header);
                    if (bytesRead < 4) {
                        return false;
                    }
                    // Check if it starts with 'd' (dictionary) which is typical for torrent files
                    String headerStr = new String(header, 0, Math.min(bytesRead, 10));
                    return headerStr.startsWith("d") || headerStr.contains("announce");
                }
            }

            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error validating file format: " + filePath, e);
            return false;
        }
    }

    private void moveToTrash(Path filePath) throws IOException {
        // Try to use system trash if available (Linux-specific)
        String userHome = System.getProperty("user.home");
        Path trashDir = Paths.get(userHome, ".local/share/Trash/files");
        Path trashInfoDir = Paths.get(userHome, ".local/share/Trash/info");

        if (!Files.exists(trashDir)) {
            // Create trash directory if it exists
            Files.createDirectories(trashDir);
        }

        String fileName = filePath.getFileName().toString();

        // Reserve a collision-safe name via the .trashinfo record: it is
        // written FIRST (freedesktop spec — file managers treat a trashed
        // file without it as unknown junk and may purge it) with CREATE_NEW,
        // so an existing file or .trashinfo record is never replaced
        Files.createDirectories(trashInfoDir);
        int attempt = -1;
        while (true) {
            attempt++;
            Path trashPath = DescriptorStaging.collisionSafeTarget(trashDir, fileName, attempt);
            Path infoPath = trashInfoDir.resolve(trashPath.getFileName() + ".trashinfo");
            String trashInfo = "[Trash Info]\nPath="
                    + filePath.toAbsolutePath().toString().replace("\n", "%0A")
                    + "\nDeletionDate="
                    + java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                    + "\n";
            try {
                Files.writeString(infoPath, trashInfo, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException reserved) {
                continue; // name already taken: try the next candidate
            }
            try {
                // No REPLACE_EXISTING: losing a race for the reserved name
                // must not silently clobber the winner
                Files.move(filePath, trashPath, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (FileAlreadyExistsException raced) {
                // The reserved name lost the race; drop our info record and
                // reserve the next candidate
                Files.deleteIfExists(infoPath);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                try {
                    Files.move(filePath, trashPath);
                    return;
                } catch (FileAlreadyExistsException racedFallback) {
                    Files.deleteIfExists(infoPath);
                }
            }
        }
    }

    private void notifyListeners(ListenerAction action) {
        for (FolderMonitorListener listener : listeners) {
            try {
                action.execute(listener);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener", e);
            }
        }
    }

    /**
     * Notifies listeners about a file event (onFileModified).
     * A listener that throws must not break the pipeline, but the failure is
     * reported to all listeners through onFileProcessingError so pipeline
     * errors stay observable.
     */
    private void notifyFileEvent(Path folderPath, Path filePath, FolderMonitorSettings settings,
            ListenerAction action) {
        for (FolderMonitorListener listener : listeners) {
            try {
                action.execute(listener);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener for file: " + filePath, e);
                reportFileError(folderPath, filePath, e, settings);
            }
        }
    }

    /**
     * Announces onFileAdded for a detected file. Torrent and Metalink
     * descriptors are first copied into the exclusive staging directory and
     * the STAGED path is announced, so listeners — and the asynchronous
     * download queue behind them — always observe durable bytes even after
     * the source disposition removes the original.
     *
     * @return false when the file must NOT proceed to its disposition:
     *         staging failed or a listener threw. In both cases the original
     *         stays in place and the error was already reported.
     */
    private boolean announceFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        Path announcePath = filePath;
        if (isDescriptorFile(filePath)) {
            try {
                announcePath = DescriptorStaging.stageFile(filePath, descriptorStagingRoot);
            } catch (Exception stagingFailure) {
                LOGGER.log(Level.SEVERE,
                        "Failed to stage descriptor; original kept in place: " + filePath, stagingFailure);
                reportFileError(folderPath, filePath, stagingFailure, settings);
                return false;
            }
        }

        boolean dispatchedToAll = true;
        for (FolderMonitorListener listener : listeners) {
            try {
                listener.onFileAdded(folderPath, announcePath, settings);
            } catch (Exception e) {
                dispatchedToAll = false;
                LOGGER.log(Level.WARNING, "Error notifying listener for file: " + filePath, e);
                // The error is identified by the source path; the staged
                // copy (if any) remains durable for recovery
                reportFileError(folderPath, filePath, e, settings);
            }
        }
        return dispatchedToAll;
    }

    /** Reports a file-processing error exactly once per round, keeping the error statistics current. */
    private void reportFileError(Path folderPath, Path filePath, Throwable error, FolderMonitorSettings settings) {
        errorCount.incrementAndGet();
        updateStatistics();
        // Exactly-once per processing round: a file that later also fails
        // format validation must not produce a second onFileProcessingError
        if (fileErrorReported.add(filePath)) {
            notifyListeners(l -> l.onFileProcessingError(folderPath, filePath, error, settings));
        }
    }

    /**
     * Whether the file is a torrent or Metalink descriptor whose bytes the
     * download queue reads later: exactly the files that require staging.
     */
    private static boolean isDescriptorFile(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        return fileName.endsWith(".torrent") || fileName.endsWith(".metalink") || fileName.endsWith(".meta4");
    }

    private void updateStatistics() {
        statistics.put("processedFiles", processedFilesCount.get());
        statistics.put("errors", errorCount.get());
        statistics.put("monitoredFolders", folderSettings.size());
        statistics.put("lastUpdate", System.currentTimeMillis());
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            try {
                shutdownInternal();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during folder monitor service shutdown", e);
            }
        });
    }

    private void shutdownInternal() {
        running.set(false);

        // Cancel monitoring task first
        if (monitoringTask != null) {
            monitoringTask.cancel(true);
        }

        // Cancel all debounce timers before shutdown
        for (ScheduledFuture<?> timer : debounceTimers.values()) {
            timer.cancel(false);
        }
        debounceTimers.clear();

        // Stop all monitoring synchronously without using CompletableFuture
        stopAllMonitoringSynchronously();

        // Shutdown executor services in proper order
        shutdownExecutorServices();

        try {
            // Close watch service
            if (watchService != null) {
                watchService.close();
            }
            LOGGER.info("Folder monitor service shut down successfully");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error closing watch service", e);
        }
    }

    private void stopAllMonitoringSynchronously() {
        try {
            List<Path> foldersToStop = new ArrayList<>(watchKeys.keySet());
            for (Path folderPath : foldersToStop) {
                stopMonitoringInternal(folderPath);
            }
            // Clear all processed files when stopping all monitoring
            processedFiles.clear();
            LOGGER.info("Stopped monitoring all folders");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error stopping folder monitoring", e);
        }
    }

    private void shutdownExecutorServices() {
        // Shutdown in order: scheduled executor first, then main executor
        if (scheduledExecutorService != null && !scheduledExecutorService.isShutdown()) {
            scheduledExecutorService.shutdown();
            try {
                if (!scheduledExecutorService.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduledExecutorService.shutdownNow();
                    if (!scheduledExecutorService.awaitTermination(2, TimeUnit.SECONDS)) {
                        LOGGER.warning("Scheduled executor did not terminate cleanly");
                    }
                }
            } catch (InterruptedException e) {
                scheduledExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(3, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                    if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                        LOGGER.warning("Main executor did not terminate cleanly");
                    }
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public Map<String, Object> getMonitoringStatistics() {
        Map<String, Object> stats = new HashMap<>(statistics);
        stats.put("processedFilesTracked", processedFiles.size());
        stats.put("lastCleanupTime", lastCleanupTime);
        return stats;
    }

    /**
     * Determines if processed files cache needs cleanup based on size or time.
     */
    private boolean shouldCleanupProcessedFiles() {
        long currentTime = System.currentTimeMillis();
        return processedFiles.size() > PROCESSED_FILES_MAX_SIZE
                || (currentTime - lastCleanupTime) > PROCESSED_FILES_CLEANUP_INTERVAL_MS;
    }

    /**
     * Cleans up old entries from the processed files cache to prevent memory
     * leaks.
     */
    private void cleanupProcessedFiles() {
        try {
            long currentTime = System.currentTimeMillis();
            long cutoffTime = currentTime - PROCESSED_FILES_MAX_AGE_MS;

            // Remove entries older than max age
            int removedByAge = 0;
            Iterator<Map.Entry<Path, Long>> iterator = processedFiles.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Path, Long> entry = iterator.next();
                if (entry.getValue() < cutoffTime) {
                    iterator.remove();
                    removedByAge++;
                }
            }

            // If still over max size, remove oldest entries
            int removedBySize = 0;
            if (processedFiles.size() > PROCESSED_FILES_MAX_SIZE) {
                List<Map.Entry<Path, Long>> entries = new ArrayList<>(processedFiles.entrySet());
                entries.sort(Map.Entry.comparingByValue());

                int entriesToRemove = processedFiles.size() - (PROCESSED_FILES_MAX_SIZE * 3 / 4); // Remove to 75%
                                                                                                   // capacity
                for (int i = 0; i < entriesToRemove && i < entries.size(); i++) {
                    processedFiles.remove(entries.get(i).getKey());
                    removedBySize++;
                }
            }

            // Bound the announce/error tracking sets the same way: entries
            // are only removed on file deletion or folder stop, so KEEP
            // actions or externally-moved files would grow them forever
            if (announcedFiles.size() > PROCESSED_FILES_MAX_SIZE) {
                LOGGER.warning("Announced-file tracking exceeded " + PROCESSED_FILES_MAX_SIZE
                        + " entries; clearing (worst case: a still-present file is re-announced once)");
                announcedFiles.clear();
            }
            if (fileErrorReported.size() > PROCESSED_FILES_MAX_SIZE) {
                fileErrorReported.clear();
            }

            lastCleanupTime = currentTime;

            if (removedByAge > 0 || removedBySize > 0) {
                LOGGER.info("Cleaned up processed files cache: " + removedByAge + " by age, "
                        + removedBySize + " by size. Remaining: " + processedFiles.size());
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error cleaning up processed files cache", e);
        }
    }

    @FunctionalInterface
    private interface ListenerAction {

        void execute(FolderMonitorListener listener);
    }
}
