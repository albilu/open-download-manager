# Folder Monitoring for Torrent Files

This module provides automatic folder monitoring functionality for the Open Download Manager, specifically designed to detect and process `.torrent` files automatically. When a torrent file is added to a monitored folder, it is automatically added to the download queue and the file can be moved to trash, deleted, or moved to another directory.

## Features

- **Automatic Torrent Detection**: Monitors folders for `.torrent` files and automatically adds them to the download queue
- **Flexible File Actions**: Configure what happens to torrent files after processing (delete, move to trash, move to directory, or keep)
- **Multiple Folder Support**: Monitor multiple folders simultaneously with different settings for each
- **Debouncing**: Prevents duplicate processing when files are being written or modified rapidly
- **Batch Processing**: Process multiple files efficiently with configurable batch sizes
- **Persistent Configuration**: Save and restore monitoring settings across application restarts
- **Event Listeners**: Get notified of file events and monitoring status changes
- **Recursive Monitoring**: Optionally monitor subdirectories
- **File Filtering**: Advanced filtering by file size, exclude patterns, and file extensions

## Quick Start

### Basic Usage

```java
// Get the download manager instance
DownloadManager downloadManager = DownloadManagerFactory.getInstance();
downloadManager.initialize().join();

// Enable torrent folder monitoring
downloadManager.setTorrentFolderMonitoringEnabled(true);

// Start monitoring the default Downloads folder
downloadManager.startDefaultTorrentFolderMonitoring().join();

// The system will now automatically process any .torrent files added to ~/Downloads
```

### Custom Folder Monitoring

```java
// Monitor a specific folder with custom settings
Path watchFolder = Paths.get("/home/user/TorrentWatch");

FolderMonitorSettings settings = TorrentFolderMonitor.createDefaultTorrentSettings()
    .setFileAction(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY)
    .setMoveToDirectory(Paths.get("/home/user/ProcessedTorrents"))
    .setDebounceDelay(Duration.ofSeconds(5))
    .setProcessExistingFiles(true);

downloadManager.startTorrentFolderMonitoring(watchFolder, settings).join();
```

## Configuration Options

### FolderMonitorSettings

The `FolderMonitorSettings` class provides comprehensive configuration options:

```java
FolderMonitorSettings settings = new FolderMonitorSettings()
    .addFileExtension(".torrent")                    // File extensions to monitor
    .setRecursive(false)                             // Monitor subdirectories
    .setFileAction(FileAction.MOVE_TO_TRASH)         // Action after processing
    .setMoveToDirectory(Paths.get("/path/to/dir"))   // Directory for MOVE_TO_DIRECTORY action
    .setProcessExistingFiles(true)                   // Process files already in folder
    .setDebounceDelay(Duration.ofSeconds(2))         // Delay before processing
    .setEnabled(true)                                // Enable/disable monitoring
    .setMaxFilesPerBatch(10)                         // Max files per batch
    .setCaseSensitive(false)                         // Case sensitive extension matching
    .addExcludePattern("*.tmp")                      // Exclude patterns
    .setMaxFileSize(10 * 1024 * 1024L)              // Max file size (10MB)
    .setMinFileSize(100L);                           // Min file size (100 bytes)
```

### File Actions

Configure what happens to torrent files after they are processed:

- **`DELETE`**: Delete the file after processing
- **`MOVE_TO_TRASH`**: Move the file to system trash (Linux: `~/.local/share/Trash/files/`)
- **`MOVE_TO_DIRECTORY`**: Move the file to a specified directory
- **`KEEP`**: Keep the file in place (no action)

## Advanced Usage

### Multiple Folders with Different Settings

```java
// Monitor Downloads with trash action
Path downloadsFolder = Paths.get(System.getProperty("user.home"), "Downloads");
downloadManager.startTorrentFolderMonitoring(downloadsFolder, 
    TorrentFolderMonitor.createDefaultTorrentSettings());

// Monitor a watched folder with delete action
Path watchFolder = Paths.get("/home/user/TorrentWatch");
downloadManager.startTorrentFolderMonitoring(watchFolder, 
    TorrentFolderMonitor.createTorrentSettingsWithDelete());

// Monitor auto-import folder with move action
Path autoFolder = Paths.get("/home/user/AutoImport");
Path processedFolder = Paths.get("/home/user/Processed");
downloadManager.startTorrentFolderMonitoring(autoFolder, 
    TorrentFolderMonitor.createTorrentSettingsWithMove(processedFolder));
```

### Event Monitoring

```java
// Add a folder monitor listener
FolderMonitorService folderService = downloadManager.getFolderMonitorService();

folderService.addFolderMonitorListener(new FolderMonitorListener() {
    @Override
    public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        System.out.println("Torrent file detected: " + filePath);
    }
    
    @Override
    public void onFileProcessed(Path folderPath, Path filePath, 
                               FileAction action, FolderMonitorSettings settings) {
        System.out.println("Torrent processed with action: " + action);
    }
    
    @Override
    public void onFileProcessingError(Path folderPath, Path filePath, 
                                    Throwable error, FolderMonitorSettings settings) {
        System.err.println("Error processing torrent: " + error.getMessage());
    }
});

// Add a download listener to track when torrents are queued
downloadManager.addDownloadListener(new DownloadListener() {
    @Override
    public void onDownloadAdded(Download download) {
        if (download.getType() == Download.Type.TORRENT) {
            System.out.println("Torrent added to queue: " + download.getFileName());
        }
    }
});
```

### Configuration Persistence

```java
// Save current folder monitoring configuration
FolderMonitorConfiguration config = FolderMonitorConfiguration.createDefault();
config.setEnabled(true);
config.setAutoStartDefault(true);

// Add folder configurations
FolderMonitorConfiguration.FolderConfig folderConfig = new FolderMonitorConfiguration.FolderConfig();
folderConfig.setEnabled(true);
folderConfig.setSettings(TorrentFolderMonitor.createDefaultTorrentSettings());
folderConfig.setDescription("Auto-import torrents from Downloads");

config.addFolderConfig(Paths.get(System.getProperty("user.home"), "Downloads"), folderConfig);

// Save configuration
config.save().join();

// Load configuration on startup
FolderMonitorConfiguration loadedConfig = FolderMonitorConfiguration.load().join();
```

## Monitoring Status and Statistics

### Check Monitoring Status

```java
// Check if torrent folder monitoring is enabled
boolean enabled = downloadManager.isTorrentFolderMonitoringEnabled();

// Get list of monitored folders
List<Path> monitoredFolders = downloadManager.getMonitoredTorrentFolders();

// Check if specific folder is monitored
boolean isMonitored = downloadManager.isTorrentFolderMonitored(watchFolder);

// Get settings for a specific folder
FolderMonitorSettings settings = downloadManager.getFolderMonitorService()
    .getMonitoringSettings(watchFolder);
```

### Monitoring Statistics

```java
// Get monitoring statistics
Map<String, Object> stats = downloadManager.getFolderMonitorService()
    .getMonitoringStatistics();

System.out.println("Processed files: " + stats.get("processedFiles"));
System.out.println("Errors: " + stats.get("errors"));
System.out.println("Monitored folders: " + stats.get("monitoredFolders"));
System.out.println("Start time: " + stats.get("startTime"));
```

## Error Handling

The folder monitoring system includes robust error handling:

- **File Access Errors**: Handles cases where files cannot be read or processed
- **Permission Errors**: Gracefully handles permission issues
- **Invalid Torrent Files**: Validates torrent files before processing
- **Directory Not Found**: Handles cases where monitored directories are deleted
- **Disk Space Issues**: Handles cases where file operations fail due to disk space

## Performance Considerations

### Recommended Settings

- **Debounce Delay**: 2-5 seconds for most use cases
- **Max Files Per Batch**: 5-10 files for optimal performance
- **Max Concurrent Folders**: Limit to 10-15 folders
- **File Size Limits**: Set reasonable min/max sizes to avoid processing invalid files

### Memory Usage

The folder monitoring system is designed to be memory-efficient:

- Uses Java NIO WatchService for efficient file system monitoring
- Processes files in batches to limit memory usage
- Automatically cleans up resources when folders are no longer monitored

## Platform Support

### Linux

- Uses `~/.local/share/Trash/files/` for trash functionality
- Supports inotify-based file system monitoring
- Handles symbolic links and mounted filesystems

### File System Requirements

- Requires read access to monitored directories
- Requires write access for file actions (delete, move)
- Works with local filesystems and most network filesystems

## Integration Examples

### Complete Setup Example

```java
public class TorrentFolderSetup {
    public static void setupTorrentMonitoring(DownloadManager downloadManager) {
        // Enable folder monitoring
        downloadManager.setTorrentFolderMonitoringEnabled(true);
        
        // Set up default monitoring
        downloadManager.startDefaultTorrentFolderMonitoring();
        
        // Set up custom folders
        setupCustomFolders(downloadManager);
        
        // Add event listeners
        setupEventListeners(downloadManager);
        
        // Save configuration
        saveConfiguration();
    }
    
    private static void setupCustomFolders(DownloadManager downloadManager) {
        String userHome = System.getProperty("user.home");
        
        // Auto-import folder (deletes processed files)
        Path autoImportFolder = Paths.get(userHome, "TorrentAutoImport");
        downloadManager.startTorrentFolderMonitoring(autoImportFolder, 
            TorrentFolderMonitor.createTorrentSettingsWithDelete());
        
        // Manual review folder (moves to processed directory)
        Path reviewFolder = Paths.get(userHome, "TorrentReview");
        Path processedFolder = Paths.get(userHome, "TorrentProcessed");
        downloadManager.startTorrentFolderMonitoring(reviewFolder, 
            TorrentFolderMonitor.createTorrentSettingsWithMove(processedFolder));
    }
    
    private static void setupEventListeners(DownloadManager downloadManager) {
        // Add comprehensive event logging
        downloadManager.getFolderMonitorService().addFolderMonitorListener(
            new LoggingFolderMonitorListener());
        
        downloadManager.addDownloadListener(new TorrentDownloadListener());
    }
    
    private static void saveConfiguration() {
        FolderMonitorConfiguration config = FolderMonitorConfiguration.createDefault();
        config.save().join();
    }
}
```

## Troubleshooting

### Common Issues

1. **Permissions**: Ensure the application has read/write permissions for monitored folders
2. **File Locks**: Some applications may lock torrent files while downloading them
3. **Invalid Files**: The system validates torrent files and will skip invalid ones
4. **Path Issues**: Use absolute paths for reliable monitoring

### Debugging

Enable debug logging to troubleshoot issues:

```java
// Enable detailed logging
Logger.getLogger("org.manager.folder").setLevel(Level.FINE);

// Monitor statistics for performance issues
Map<String, Object> stats = folderMonitorService.getMonitoringStatistics();
```

### Performance Issues

If experiencing performance issues:

1. Reduce the number of monitored folders
2. Increase debounce delay
3. Reduce batch size
4. Check for disk I/O bottlenecks
5. Monitor memory usage statistics

## API Reference

### Core Classes

- **`FolderMonitorService`**: Main interface for folder monitoring
- **`FolderMonitorServiceImpl`**: Implementation using Java NIO WatchService
- **`TorrentFolderMonitor`**: Specialized monitor for torrent files
- **`FolderMonitorSettings`**: Configuration settings for folder monitoring
- **`FolderMonitorListener`**: Event listener interface
- **`FolderMonitorConfiguration`**: Persistent configuration management

### Key Methods

```java
// DownloadManager methods
CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath);
CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath, FolderMonitorSettings settings);
CompletableFuture<Void> stopTorrentFolderMonitoring(Path folderPath);
List<Path> getMonitoredTorrentFolders();
boolean isTorrentFolderMonitored(Path folderPath);
void setTorrentFolderMonitoringEnabled(boolean enabled);
boolean isTorrentFolderMonitoringEnabled();

// FolderMonitorService methods
CompletableFuture<Void> startMonitoring(Path folderPath, FolderMonitorSettings settings);
CompletableFuture<Void> stopMonitoring(Path folderPath);
CompletableFuture<Void> stopAllMonitoring();
List<Path> getMonitoredFolders();
boolean isMonitoring(Path folderPath);
Map<String, Object> getMonitoringStatistics();
```

## Best Practices

1. **Use Default Settings**: Start with `TorrentFolderMonitor.createDefaultTorrentSettings()` and customize as needed
2. **Monitor Specific Folders**: Don't monitor entire file systems; use specific directories
3. **Set Appropriate Debounce**: Use 2-5 seconds to avoid processing incomplete files
4. **Handle Events**: Implement listeners to track processing status and errors
5. **Save Configuration**: Use `FolderMonitorConfiguration` to persist settings
6. **Monitor Performance**: Check statistics regularly to ensure optimal performance
7. **Test Thoroughly**: Test with various file types and edge cases
8. **Plan for Errors**: Implement proper error handling and recovery

## Recent Improvements (v1.1)

### Enhanced File Validation
- **Torrent Files**: Improved bencode structure validation with proper field detection for `announce`, `info`, `pieces`, etc.
- **Metalink Files**: Enhanced XML parsing with namespace validation and required element checking
- **Reduced False Positives**: Better detection of corrupted or incomplete files

### Memory Management
- **Bounded Cache**: ProcessedFiles now uses a time-based cache with automatic cleanup
- **Configurable Limits**: Maximum 10,000 tracked files with 24-hour expiration
- **Periodic Cleanup**: Automatic cleanup every hour to prevent memory leaks
- **Statistics Tracking**: Added metrics for cache size and cleanup operations

### Recursive Directory Handling
- **Proper Watch Key Tracking**: All recursive subdirectories are now properly tracked
- **Cleanup on Removal**: Watch keys for subdirectories are properly cleaned up when monitoring stops
- **Race Condition Fix**: Eliminated orphaned watch keys for dynamically created subdirectories

### Performance Improvements
- **Efficient Validation**: Optimized file validation to read only necessary data portions
- **Debounce Enhancement**: Improved duplicate processing prevention with timestamp tracking
- **Resource Management**: Better cleanup of watch service resources

## Future Enhancements

Planned improvements for the folder monitoring system:

- Support for additional file types (magnet link files)
- GUI configuration interface
- Advanced filtering rules (regex patterns)
- Integration with file system notifications
- Bandwidth throttling during file processing
- Automatic retry mechanisms for failed operations
- Cloud storage integration
- Real-time monitoring dashboard