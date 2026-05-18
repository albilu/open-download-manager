# Clipboard Monitoring

This package provides comprehensive clipboard monitoring functionality for the Open Download Manager. It automatically detects URLs copied to the clipboard and can integrate them with the download system.

## Features

-   **Automatic URL Detection**: Monitors clipboard changes and extracts valid URLs
-   **Multiple URL Types**: Supports HTTP/HTTPS, FTP, magnet links, torrents, and video URLs
-   **Configurable Monitoring**: Customizable polling intervals and filtering options
-   **Download Integration**: Seamlessly integrates with the download manager
-   **Silent Mode**: Optional background operation without user interruption
-   **URL Filtering**: Configurable filters for different types of content

## Architecture

### Core Components

-   **`ClipboardMonitor`**: Interface for clipboard monitoring functionality
-   **`ClipboardMonitorImpl`**: Implementation using Java AWT Clipboard API
-   **`ClipboardService`**: Integration layer with the download manager
-   **`ClipboardSettings`**: Configuration for clipboard monitoring behavior
-   **`UrlDetector`**: Utility for extracting and validating URLs from text
-   **`ClipboardFactory`**: Factory for creating configured clipboard components

### Event System

-   **`ClipboardListener`**: Interface for clipboard change notifications
-   **`ClipboardServiceListener`**: Interface for service-level events
-   **`ClipboardEvent`**: Event data structure for clipboard changes

## Quick Start

### Basic Usage

```java
// Create and start basic clipboard monitoring
ClipboardMonitor monitor = ClipboardFactory.createClipboardMonitor();
monitor.addClipboardListener(new ClipboardListener() {
    @Override
    public void onUrlsDetected(List<URI> urls, String clipboardContent) {
        System.out.println("Detected URLs: " + urls);
    }
});
monitor.startMonitoring();
```

### Integration with Download Manager

```java
// Get download manager instance
DownloadManager downloadManager = DownloadManagerFactory.getInstance();
downloadManager.initialize().join();

// Enable clipboard monitoring
downloadManager.setClipboardMonitoringEnabled(true);

// Configure settings
ClipboardSettings settings = new ClipboardSettings()
    .setMonitoringEnabled(true)
    .setSilentMode(false)
    .setAutoDownloadDetectedUrls(false)
    .setShowConfirmationDialog(true);

downloadManager.updateClipboardSettings(settings);
```

### Manual Import

```java
// Import URLs from current clipboard content
CompletableFuture<List<Download>> downloads = downloadManager.importFromClipboard();
downloads.thenAccept(list -> {
    System.out.println("Created " + list.size() + " downloads");
});
```

## Configuration

### ClipboardSettings Options

```java
ClipboardSettings settings = new ClipboardSettings()
    // Enable/disable monitoring
    .setMonitoringEnabled(true)

    // Silent mode (no dialogs)
    .setSilentMode(false)

    // Monitoring frequency (milliseconds)
    .setMonitoringIntervalMs(500)

    // Auto-download detected URLs
    .setAutoDownloadDetectedUrls(false)

    // Show confirmation dialogs
    .setShowConfirmationDialog(true)

    // URL type filtering
    .setFilterVideoUrls(true)
    .setFilterTorrentUrls(true)
    .setFilterDirectDownloads(true)

    // Limit URLs processed per clipboard change
    .setMaxUrlsPerClipboard(10)

    // Enable activity logging
    .setLogClipboardActivity(false);
```

### Preset Configurations

```java
// Default settings (conservative)
ClipboardSettings defaultSettings = ClipboardFactory.createDefaultSettings();

// Power user settings (aggressive)
ClipboardSettings powerSettings = ClipboardFactory.createPowerUserSettings();

// Minimal resource usage
ClipboardSettings minimalSettings = ClipboardFactory.createMinimalSettings();

// Development/testing
ClipboardSettings devSettings = ClipboardFactory.createDevelopmentSettings();
```

### Builder Pattern

```java
ClipboardService service = ClipboardFactory.builder()
    .withInterval(300)
    .withSilentMode(true)
    .withUrlFiltering(true, true, false) // videos, torrents, no direct downloads
    .withMaxUrls(20)
    .withLogging(true)
    .buildService(downloadManager);
```

## URL Detection

### Supported URL Types

1. **HTTP/HTTPS URLs**

    - Direct file downloads
    - Web pages with downloadable content
    - API endpoints

2. **Video URLs**

    - YouTube (`youtube.com`, `youtu.be`)
    - Vimeo (`vimeo.com`)
    - Dailymotion (`dailymotion.com`)
    - Twitch (`twitch.tv`)
    - Other video platforms

3. **Torrent/P2P**

    - Magnet links (`magnet:?xt=urn:btih:...`)
    - Torrent files (`.torrent` extension)

4. **FTP URLs**

    - Direct FTP downloads
    - Anonymous FTP access

5. **Local Files**
    - Local torrent files (`file://...torrent`)

### Detection Logic

The `UrlDetector` class uses sophisticated pattern matching to identify valid URLs:

```java
// Extract all URLs from text
List<URI> urls = UrlDetector.extractUrls(clipboardText);

// Check specific URL types
boolean isVideo = UrlDetector.isVideoUrl(uri);
boolean isMagnet = UrlDetector.isMagnetLink(uri);
boolean isTorrent = UrlDetector.isTorrentFile(uri);
```

### URL Validation

URLs are validated based on:

-   Protocol support (http, https, ftp, magnet, file)
-   File extensions for downloadable content
-   Domain patterns for video sites
-   Magnet link format validation
-   General downloadability heuristics

## Event Handling

### Clipboard Events

```java
monitor.addClipboardListener(new ClipboardListener() {
    @Override
    public void onUrlsDetected(List<URI> urls, String clipboardContent) {
        // Handle detected URLs
    }

    @Override
    public void onClipboardChanged(String clipboardContent) {
        // Handle non-URL clipboard changes
    }

    @Override
    public void onClipboardError(Exception error) {
        // Handle clipboard access errors
    }

    @Override
    public void onMonitoringStarted() {
        // Monitoring started
    }

    @Override
    public void onMonitoringStopped() {
        // Monitoring stopped
    }
});
```

### Service Events

```java
clipboardService.addServiceListener(new ClipboardServiceListener() {
    @Override
    public void onUrlsDetected(List<URI> urls, String clipboardContent) {
        // URLs detected and filtered
    }

    @Override
    public void onConfirmationRequired(List<URI> urls, String clipboardContent) {
        // User confirmation needed (show dialog)
    }

    @Override
    public void onDownloadsCreated(List<URI> urls, int downloadCount) {
        // Downloads automatically created
    }
});
```

## System Requirements

### Dependencies

-   Java 21 or higher
-   AWT/Swing support (desktop environment)
-   System clipboard access

### Platform Support

-   **Linux**: Full support with X11/Wayland
-   **Windows**: Full support
-   **macOS**: Full support
-   **Headless**: Limited support (no clipboard access)

### Permissions

-   Clipboard read access
-   File system access for downloads
-   Network access for URL validation

## Performance Considerations

### Monitoring Frequency

-   Default: 500ms (good balance)
-   Responsive: 200-300ms (higher CPU usage)
-   Conservative: 1000-2000ms (lower CPU usage)

### Memory Usage

-   URL detection: Minimal overhead
-   Event listeners: O(n) where n = number of listeners
-   History: Configurable retention limits

### CPU Impact

-   Polling-based approach (Java limitation)
-   Regex pattern matching for URL detection
-   Minimal impact with reasonable intervals

## Troubleshooting

### Common Issues

1. **Clipboard Access Denied**

    - Check desktop environment permissions
    - Verify AWT initialization
    - Run with proper display settings

2. **URLs Not Detected**

    - Check URL format validity
    - Verify filtering settings
    - Enable debug logging

3. **High CPU Usage**
    - Increase monitoring interval
    - Reduce URL complexity
    - Check for clipboard polling conflicts

### Debug Mode

```java
ClipboardSettings debugSettings = ClipboardFactory.createDevelopmentSettings()
    .setLogClipboardActivity(true);

// Enable Java logging
Logger.getLogger("org.manager.clipboard").setLevel(Level.FINE);
```

### Capability Check

```java
// Check if clipboard monitoring is supported
boolean supported = ClipboardFactory.isClipboardMonitoringSupported();

// Get system capability information
String info = ClipboardFactory.getClipboardCapabilityInfo();
System.out.println(info);
```

## Examples

See the `example` package for complete usage examples:

-   **`ClipboardExample`**: Comprehensive demonstration
-   **`SimpleClipboardTest`**: Basic URL detection testing

## Thread Safety

All clipboard monitoring components are designed to be thread-safe:

-   `ClipboardMonitorImpl`: Uses atomic variables and concurrent collections
-   `ClipboardService`: Thread-safe event notification
-   `ClipboardSettings`: Immutable configuration objects

## Best Practices

1. **Resource Management**

    ```java
    try {
        clipboardService.startService();
        // Use the service
    } finally {
        clipboardService.cleanup(); // Always cleanup
    }
    ```

2. **Error Handling**

    ```java
    clipboardService.addServiceListener(new ClipboardServiceListener() {
        @Override
        public void onClipboardError(Exception error) {
            logger.warning("Clipboard error: " + error.getMessage());
            // Handle gracefully
        }
    });
    ```

3. **Configuration**

    ```java
    // Start with conservative settings
    ClipboardSettings settings = ClipboardFactory.createDefaultSettings();

    // Adjust based on user preferences
    if (userWantsAutoDownload) {
        settings.setAutoDownloadDetectedUrls(true);
    }
    ```

4. **Performance**
    ```java
    // Adjust interval based on system capabilities
    long interval = SystemInfo.isLowEndSystem() ? 1000 : 500;
    settings.setMonitoringIntervalMs(interval);
    ```

## License

This clipboard monitoring functionality is part of the Open Download Manager project and follows the same licensing terms.
