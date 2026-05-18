# jYTdlp - Java yt-dlp Integration

A comprehensive Java wrapper for the yt-dlp command-line tool, providing a clean API for downloading videos from YouTube and hundreds of other supported sites.

## Overview

This package provides a complete integration with yt-dlp, including:

-   **YtDlpClient**: Core client for executing yt-dlp commands
-   **YtDlpDownloadTask**: High-level task management for downloads
-   **YtDlpFactory**: Factory for creating and managing yt-dlp components
-   **YtDlpSettings**: Comprehensive configuration options
-   **YtDlpUrlUtils**: URL validation and platform detection utilities
-   **YtDlpDownloadHandler**: Integration with the download manager framework
-   **Aria2c Integration**: External downloader support for faster multi-connection downloads

## Features

### Core Functionality

-   Video information extraction without downloading
-   High-quality video downloads with format selection
-   Audio-only extraction with configurable quality
-   Subtitle download and embedding
-   Thumbnail embedding and metadata preservation
-   Progress monitoring with real-time callbacks
-   Playlist support
-   Proxy and rate limiting support
-   **Aria2c external downloader** for faster multi-connection downloads

### Advanced Features

-   Concurrent download management
-   Task lifecycle management (start, pause, resume, cancel)
-   URL validation and platform detection
-   Custom output templates
-   Error handling and retry logic
-   Resource cleanup and shutdown management

## Quick Start

### Basic Video Download

```java
// Create client and settings
YtDlpClient client = new YtDlpClient();
YtDlpSettings settings = new YtDlpSettings()
    .setFormat("best[height<=720]/best")
    .setEmbedThumbnail(true)
    .setEmbedMetadata(true);

// Set up progress callback
ProgressCallback callback = new ProgressCallback() {
    @Override
    public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
        System.out.printf("Progress: %.1f%% (%.2f MB/s)\n", percentage, speed / (1024 * 1024));
    }

    @Override
    public void onComplete(String filename) {
        System.out.println("Download completed: " + filename);
    }

    @Override
    public void onError(String error) {
        System.err.println("Error: " + error);
    }
};

// Start download
Path outputDir = Paths.get(System.getProperty("user.home"), "Downloads");
CompletableFuture<String> future = client.download(
    "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    settings,
    outputDir,
    callback
);

// Wait for completion
String result = future.get();
client.shutdown();
```

### Using Aria2c External Downloader for Faster Downloads

```java
YtDlpFactory factory = YtDlpFactory.getInstance();

// Check if aria2c is available
if (factory.isAria2cAvailable()) {
    // Create aria2c-optimized settings
    YtDlpSettings settings = factory.createAria2cSettings();
    settings.setFormat("best[height<=1080]/best")
           .setAria2cConnections(16)     // 16 total connections
           .setAria2cSplitConnections(16) // 16 connections per server
           .setAria2cMinSplitSize("1M");  // 1MB minimum split size

    YtDlpClient client = factory.createClient();
    CompletableFuture<String> future = client.download(url, settings, outputPath, callback);
    String result = future.get();

    client.shutdown();
} else {
    System.out.println("aria2c not available, using default downloader");
}
```

### Using YtDlpFactory for Simplified Management

```java
// Get factory instance
YtDlpFactory factory = YtDlpFactory.getInstance();

// Create task with default settings
YtDlpDownloadTask task = factory.createDownloadTask(
    "my-task-id",
    "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
);

// Start download
CompletableFuture<String> future = task.start();

// Monitor progress
while (!task.isDone()) {
    System.out.printf("Status: %s, Progress: %.1f%%\n",
        task.getStatus(), task.getProgress());
    Thread.sleep(1000);
}

// Get result
String result = future.get();
factory.removeDownloadTask("my-task-id");
```

### Audio-Only Download

```java
YtDlpFactory factory = YtDlpFactory.getInstance();
YtDlpSettings audioSettings = factory.createAudioSettings()
    .setAudioFormat("mp3")
    .setAudioQuality("192");

YtDlpClient client = factory.createClient();
CompletableFuture<String> future = client.download(url, audioSettings, outputPath, callback);
```

### Video Information Extraction

```java
YtDlpClient client = new YtDlpClient();

// Extract video information
CompletableFuture<VideoInfo> infoFuture = client.extractInfo(
    "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
);

VideoInfo info = infoFuture.get();
System.out.println("Title: " + info.getTitle());
System.out.println("Duration: " + info.getDuration() + " seconds");
System.out.println("Uploader: " + info.getUploader());

// List available formats
CompletableFuture<List<VideoFormat>> formatsFuture = client.listFormats(url);
List<VideoFormat> formats = formatsFuture.get();
for (VideoFormat format : formats) {
    System.out.printf("Format: %s (%s) - %s\n",
        format.getFormatId(), format.getResolution(), format.getExt());
}

client.shutdown();
```

## Configuration Options

### YtDlpSettings

```java
YtDlpSettings settings = new YtDlpSettings()
    // Video format selection
    .setFormat("bestvideo[height<=1080]+bestaudio/best[height<=1080]")

    // Audio extraction
    .setExtractAudio(true)
    .setAudioFormat("mp3")
    .setAudioQuality("192")

    // Metadata and thumbnails
    .setEmbedThumbnail(true)
    .setEmbedMetadata(true)

    // Subtitles
    .setWriteSubtitles(true)
    .setEmbedSubs(true)
    .addSubtitleLanguage("en")
    .addSubtitleLanguage("es")

    // Network settings
    .setFragmentRetries(5)
    .setLimitRate(true)
    .setRateLimit(1000) // 1MB/s

    // Proxy settings
    .setUseProxy(true)
    .setProxyAddress("http://proxy.example.com:8080")

    // Error handling
    .setIgnoreErrors(true)
    .setSkipUnavailableFragments(true)

    // Custom options
    .setOption("write-description", "true")
    .setOption("write-info-json", "true")

    // Aria2c external downloader (if available)
    .setUseAria2c(true)
    .setAria2cConnections(32)
    .setAria2cSplitConnections(16)
    .setAria2cMinSplitSize("512K");
```

### Predefined Settings

```java
YtDlpFactory factory = YtDlpFactory.getInstance();

// Audio-optimized settings
YtDlpSettings audioSettings = factory.createAudioSettings();

// High-quality video settings
YtDlpSettings hqSettings = factory.createHighQualityVideoSettings();

// Playlist-optimized settings
YtDlpSettings playlistSettings = factory.createPlaylistSettings();

// Aria2c-optimized settings for faster downloads
YtDlpSettings aria2cSettings = factory.createAria2cSettings();

// Custom aria2c settings with specific parameters
YtDlpSettings customAria2c = factory.createAria2cSettings(32, 16, "512K");
```

## URL Validation and Platform Detection

```java
// Validate URL
boolean isSupported = YtDlpUrlUtils.isSupported("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

// Analyze URL
YtDlpUrlUtils.UrlInfo info = YtDlpUrlUtils.analyzeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
System.out.println("Platform: " + YtDlpUrlUtils.getPlatformDisplayName(info.getPlatform()));
System.out.println("Video ID: " + info.getVideoId());
System.out.println("Is Playlist: " + info.isPlaylist());

// Get suggested format for platform
String suggestedFormat = YtDlpUrlUtils.getSuggestedFormat(info.getPlatform(), "720p");
```

## Task Management

### YtDlpDownloadTask Features

```java
YtDlpDownloadTask task = factory.createDownloadTask("task-id", url, settings, outputPath);

// Start download
CompletableFuture<String> future = task.start();

// Monitor progress
System.out.println("Status: " + task.getStatus());
System.out.println("Progress: " + task.getProgress() + "%");
System.out.println("Speed: " + task.getSpeed() / (1024 * 1024) + " MB/s");
System.out.println("ETA: " + task.getEstimatedTimeRemaining() + " seconds");

// Control download
task.pause();   // Pause download
task.resume();  // Resume download
task.cancel();  // Cancel download

// Check status
boolean isActive = task.isActive();
boolean isDone = task.isDone();
boolean isCancelled = task.isCancelled();

// Check aria2c availability
YtDlpClient client = new YtDlpClient();
boolean aria2cAvailable = client.isAria2cAvailable();
String aria2cVersion = client.getAria2cVersion();
```

## Integration with Download Manager

The `YtDlpDownloadHandler` integrates seamlessly with the download manager framework:

```java
// Create download with YouTube type
Download download = new Download(new URI("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
download.setType(Download.Type.YOUTUBE);

// Configure yt-dlp specific settings
YtDlpSettings settings = new YtDlpSettings()
    .setFormat("best[height<=720]/best")
    .setEmbedThumbnail(true);
download.setSettings(settings);

// Use download manager
DownloadManager manager = DownloadManagerFactory.getInstance();
manager.queueDownload(download);
```

## Error Handling

```java
try {
    CompletableFuture<String> future = client.download(url, settings, outputPath, callback);
    String result = future.get(10, TimeUnit.MINUTES);
    System.out.println("Download successful: " + result);
} catch (TimeoutException e) {
    System.err.println("Download timed out");
} catch (ExecutionException e) {
    Throwable cause = e.getCause();
    System.err.println("Download failed: " + cause.getMessage());
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    System.err.println("Download interrupted");
}
```

## Supported Platforms

The integration supports hundreds of sites through yt-dlp, including:

-   **Video**: YouTube, Vimeo, Dailymotion, Twitch, TikTok
-   **Social**: Instagram, Facebook, Twitter/X, Reddit
-   **Audio**: SoundCloud, Bandcamp
-   **News**: CNN, BBC, Reuters, Vice
-   **Educational**: Coursera, Udemy, Khan Academy
-   **Streaming**: Crunchyroll, Funimation
-   **And many more...**

Use `YtDlpUrlUtils.isSupported(url)` to check if a specific URL is supported.

## Requirements

-   **java 21+**
-   **yt-dlp** installed and available in system PATH
-   **FFmpeg** (recommended for best format support)
-   **aria2c** (optional, for faster multi-connection downloads)

## Installation

Ensure yt-dlp is installed:

```bash
# Using pip
pip install yt-dlp

# Using package manager (Ubuntu/Debian)
sudo apt install yt-dlp

# Using package manager (macOS)
brew install yt-dlp

# Install aria2c for faster downloads (optional)
# Ubuntu/Debian
sudo apt install aria2

# macOS
brew install aria2

# Windows (using chocolatey)
choco install aria2
```

## Performance Tips

1. **Use appropriate formats**: Choose formats that match your needs to reduce download time
2. **Limit concurrent downloads**: Too many simultaneous downloads can overwhelm the system
3. **Enable rate limiting**: Use rate limiting to avoid being blocked by video platforms
4. **Use proxy rotation**: For bulk downloads, consider proxy rotation
5. **Monitor resource usage**: yt-dlp can be memory-intensive for large files
6. **Use aria2c for large files**: Enable aria2c external downloader for faster downloads of large videos

## Examples

See `YtDlpExample.java` for comprehensive usage examples including:

-   Basic video downloads
-   Audio extraction
-   High-quality video with subtitles
-   Video information extraction
-   Task management
-   Concurrent downloads
-   Custom settings configuration
-   Aria2c external downloader integration

## Architecture

```
YtDlpFactory (Singleton)
├── YtDlpClient (Process management)
├── YtDlpDownloadTask (Task lifecycle)
├── YtDlpSettings (Configuration)
└── YtDlpUrlUtils (URL validation)

Integration Layer:
├── YtDlpDownloadHandler (Download manager integration)
└── DownloadSettingsFactory (Settings creation)
```

## Thread Safety

-   All classes are designed to be thread-safe
-   `YtDlpFactory` uses concurrent collections for task management
-   `YtDlpClient` manages process execution safely
-   `YtDlpDownloadTask` uses atomic variables for state management

## Resource Management

-   Always call `shutdown()` on clients when done
-   Use try-with-resources or finally blocks for cleanup
-   The factory manages client lifecycle automatically
-   Cancel tasks before application shutdown

## Troubleshooting

### Common Issues

1. **"yt-dlp not found"**: Ensure yt-dlp is installed and in PATH
2. **Download failures**: Check internet connection and URL validity
3. **Format not available**: Use `listFormats()` to see available options
4. **Slow downloads**: Consider using aria2c external downloader or adjusting fragment retries and rate limits
5. **Memory issues**: Limit concurrent downloads and use appropriate formats
6. **Aria2c not working**: Ensure aria2c is installed and check `isAria2cAvailable()`

### Debug Mode

Enable verbose output for troubleshooting:

```java
YtDlpSettings settings = new YtDlpSettings()
    .setVerboseOutput(true);
```

### Logging

The package uses Java's built-in logging. Configure log levels:

```java
Logger.getLogger("org.ytdlp").setLevel(Level.FINE);
```

| Task                  | Command Example                     |     |
| --------------------- | ----------------------------------- | --- |
| Single video          | `yt‑dlp https://…`                  |     |
| Playlist              | `yt‑dlp https://…playlists…`        |     |
| List formats          | `yt‑dlp -F https://…`               |     |
| Choose format         | `yt‑dlp -f 22 https://…`            |     |
| Extract audio         | `-x --audio-format mp3`             |     |
| Resume download       | `-c`                                |     |
| Rate limit            | `-r 100K`                           |     |
| Select playlist items | `--playlist-items 1,3,5`            |     |
| Download thumbnail    | `--write-thumbnail --skip-download` |     |

yt-dlp --external-downloader aria2c \
 --external-downloader-args "-x 16 -s 16 -k 1M" <video-URL>
