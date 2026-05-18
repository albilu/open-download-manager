# Folder Monitoring Implementation Summary

## Overview

This document summarizes the implementation of the folder monitoring feature for the Open Download Manager (ODM). This feature automatically detects `.torrent` files added to monitored folders and adds them to the download queue, with configurable post-processing actions.

## Architecture

### Core Components

1. **FolderMonitorService** - Main interface for folder monitoring functionality
2. **FolderMonitorServiceImpl** - Implementation using Java NIO WatchService
3. **TorrentFolderMonitor** - Specialized monitor for torrent files with DownloadManager integration
4. **FolderMonitorSettings** - Configuration class for monitoring behavior
5. **FolderMonitorListener** - Event listener interface for monitoring events
6. **FolderMonitorConfiguration** - Persistent configuration management

### File Structure

```
org/manager/folder/
├── FolderMonitorService.java           # Main service interface
├── FolderMonitorServiceImpl.java       # WatchService implementation
├── TorrentFolderMonitor.java           # Torrent-specific monitor
├── FolderMonitorSettings.java          # Configuration settings
├── FolderMonitorListener.java          # Event listener interface
├── FolderMonitorConfiguration.java     # Persistent configuration
├── TorrentFolderMonitorExample.java    # Usage examples
├── FolderMonitorDemo.java              # Standalone demo
└── README.md                           # Detailed documentation
```

## Key Features

### 1. Automatic Torrent Detection

-   Monitors folders for `.torrent` files using Java NIO WatchService
-   Validates torrent files before processing (checks bencode format)
-   Supports both real-time detection and existing file processing

### 2. Flexible File Actions

After processing a torrent file, the system can:

-   **DELETE**: Remove the file completely
-   **MOVE_TO_TRASH**: Move to system trash (`~/.local/share/Trash/files/` on Linux)
-   **MOVE_TO_DIRECTORY**: Move to a specified directory
-   **KEEP**: Leave the file in place

### 3. Advanced Configuration

-   **Debouncing**: Prevents duplicate processing during file writes
-   **Batch Processing**: Process multiple files efficiently
-   **File Filtering**: Size limits, extension filtering, exclude patterns
-   **Recursive Monitoring**: Optional subdirectory monitoring
-   **Case Sensitivity**: Configurable extension matching

### 4. Event System

-   Real-time notifications for file events
-   Error handling and reporting
-   Monitoring lifecycle events
-   Integration with DownloadManager events

### 5. Performance Features

-   Memory-efficient using Java NIO
-   Configurable batch sizes and concurrent limits
-   Automatic resource cleanup
-   Statistics and monitoring

## Integration with DownloadManager

### New Methods Added

```java
// Enable/disable torrent folder monitoring
void setTorrentFolderMonitoringEnabled(boolean enabled);
boolean isTorrentFolderMonitoringEnabled();

// Start/stop monitoring specific folders
CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath);
CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath, FolderMonitorSettings settings);
CompletableFuture<Void> stopTorrentFolderMonitoring(Path folderPath);

// Query monitoring status
List<Path> getMonitoredTorrentFolders();
boolean isTorrentFolderMonitored(Path folderPath);

// Convenience methods
CompletableFuture<Void> startDefaultTorrentFolderMonitoring();

// Access to underlying services
FolderMonitorService getFolderMonitorService();
TorrentFolderMonitor getTorrentFolderMonitor();
```

### Implementation Changes

1. **DownloadManagerImpl** - Added folder monitoring services and integration
2. **Shutdown Coordination** - Proper cleanup of monitoring services
3. **Dependency Management** - Integration with existing DI container

## Usage Examples

### Basic Usage

```java
// Get download manager
DownloadManager downloadManager = DownloadManagerFactory.getInstance();
downloadManager.initialize().join();

// Enable torrent folder monitoring
downloadManager.setTorrentFolderMonitoringEnabled(true);

// Monitor default Downloads folder
downloadManager.startDefaultTorrentFolderMonitoring().join();
```

### Custom Configuration

```java
// Custom folder with specific settings
Path watchFolder = Paths.get("/home/user/TorrentWatch");
FolderMonitorSettings settings = TorrentFolderMonitor.createDefaultTorrentSettings()
    .setFileAction(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY)
    .setMoveToDirectory(Paths.get("/home/user/ProcessedTorrents"))
    .setDebounceDelay(Duration.ofSeconds(5));

downloadManager.startTorrentFolderMonitoring(watchFolder, settings).join();
```

### Event Monitoring

```java
// Add folder monitor listener
downloadManager.getFolderMonitorService().addFolderMonitorListener(
    new FolderMonitorListener() {
        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            System.out.println("Torrent detected: " + filePath);
        }

        @Override
        public void onFileProcessed(Path folderPath, Path filePath,
                                   FileAction action, FolderMonitorSettings settings) {
            System.out.println("Torrent processed: " + filePath + " (" + action + ")");
        }
    }
);
```

## Configuration Persistence

### FolderMonitorConfiguration

Supports saving and loading monitoring configurations:

```java
// Create and save configuration
FolderMonitorConfiguration config = FolderMonitorConfiguration.createDefault();
config.setEnabled(true);
config.setAutoStartDefault(true);

FolderConfig folderConfig = new FolderConfig();
folderConfig.setEnabled(true);
folderConfig.setSettings(TorrentFolderMonitor.createDefaultTorrentSettings());
config.addFolderConfig(watchFolder, folderConfig);

config.save().join();

// Load on startup
FolderMonitorConfiguration loadedConfig = FolderMonitorConfiguration.load().join();
```

### Configuration File Format

Configurations are saved as JSON in `~/.odm/folder-monitor-config.json`:

```json
{
	"enabled": true,
	"autoStartDefault": true,
	"monitoredFolders": {
		"/home/user/Downloads": {
			"enabled": true,
			"description": "Default Downloads folder",
			"settings": {
				"fileExtensions": [".torrent"],
				"fileAction": "MOVE_TO_TRASH",
				"processExistingFiles": true,
				"debounceDelay": "PT2S"
			}
		}
	},
	"globalSettings": {
		"maxConcurrentFolders": 10,
		"defaultDebounceDelay": "PT2S",
		"enableLogging": true
	}
}
```

## Testing

### Unit Tests

Comprehensive test suite in `TorrentFolderMonitorTest.java`:

-   Basic torrent detection and processing
-   Custom settings validation
-   Multiple folder monitoring
-   File action verification (delete, move, trash)
-   Error handling and invalid file rejection
-   Debounce delay functionality
-   Enable/disable functionality

### Demo Application

`FolderMonitorDemo.java` provides a standalone demonstration:

-   Creates sample directory structure
-   Sets up monitoring with different configurations
-   Creates sample torrent files automatically
-   Shows real-time event logging

## Performance Considerations

### Memory Usage

-   Uses Java NIO WatchService for efficient file system monitoring
-   Processes files in configurable batches to limit memory usage
-   Automatic cleanup of completed monitoring tasks

### Recommended Settings

-   **Debounce Delay**: 2-5 seconds for most use cases
-   **Max Files Per Batch**: 5-10 files for optimal performance
-   **Max Concurrent Folders**: Limit to 10-15 folders
-   **File Size Limits**: Set reasonable min/max sizes (100 bytes - 10MB for torrents)

### Error Handling

-   Graceful handling of file access errors
-   Validation of torrent file format
-   Recovery from temporary file system issues
-   Comprehensive logging and error reporting

## Platform Support

### Linux

-   Uses `~/.local/share/Trash/files/` for trash functionality
-   Supports inotify-based file system monitoring via Java NIO
-   Handles symbolic links and mounted filesystems

### File System Requirements

-   Read access to monitored directories
-   Write access for file actions (delete, move)
-   Compatible with local and most network filesystems

## Security Considerations

### File Validation

-   Basic torrent file validation (bencode format check)
-   File size limits to prevent processing of invalid files
-   Pattern-based exclusion of temporary or system files

### File System Safety

-   Uses atomic file operations where possible
-   Handles permission errors gracefully
-   Validates target directories before moving files

## Future Enhancements

### Planned Features

1. **GUI Configuration Interface** - Visual setup and management
2. **Advanced File Validation** - Full torrent file parsing and validation
3. **Regex Pattern Support** - More flexible file filtering
4. **Cloud Storage Integration** - Monitor cloud storage folders
5. **Bandwidth Throttling** - Limit file processing impact
6. **Auto-retry Mechanisms** - Retry failed operations
7. **Real-time Dashboard** - Monitoring statistics and status

### Extension Points

-   **Custom File Processors** - Support for other file types (magnet links, metalinks)
-   **Plugin Architecture** - Allow custom file actions and processors
-   **Integration APIs** - REST/WebSocket APIs for external integration
-   **Notification System** - Email, desktop notifications for events

## Dependencies

### Core Java Dependencies

-   java 21+ (for NIO.2 and CompletableFuture)
-   Jackson (for JSON configuration serialization)

### Integration Dependencies

-   ODM Core (DownloadManager, GlobalSettings)
-   ODM Download System (Download, DownloadListener)

### Test Dependencies

-   JUnit 5 (for unit testing)
-   Temporary folder support for file system tests

## Deployment

### Configuration

1. Enable torrent folder monitoring in global settings
2. Configure default folders and actions
3. Set up appropriate file permissions
4. Configure logging levels for monitoring events

### Startup Sequence

1. Initialize FolderMonitorService
2. Load saved configuration
3. Start monitoring configured folders
4. Register shutdown hooks for cleanup

### Monitoring

-   Check monitoring statistics regularly
-   Monitor log files for errors
-   Verify file processing is working as expected
-   Monitor system resources (memory, file handles)

## Conclusion

The folder monitoring implementation provides a robust, configurable solution for automatic torrent file processing. It integrates seamlessly with the existing ODM architecture while providing extensibility for future enhancements. The implementation prioritizes performance, reliability, and ease of use while maintaining compatibility with Linux desktop environments.

Key benefits:

-   **Automatic Processing**: No manual intervention required
-   **Flexible Configuration**: Supports various use cases and workflows
-   **Robust Error Handling**: Graceful handling of edge cases
-   **Performance Optimized**: Efficient resource usage
-   **Extensible Design**: Easy to add new features and file types

The implementation is production-ready and provides a solid foundation for automatic torrent management in the Open Download Manager.
