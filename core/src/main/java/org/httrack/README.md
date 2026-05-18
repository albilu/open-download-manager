# Httrack Package

Java wrapper and client for httrack website mirroring tool.

## Components

### HttrackClient

High-level Java client for website mirroring operations with:

-   Asynchronous API using CompletableFuture
-   Real-time progress monitoring
-   Job management (start, pause, resume, cancel)
-   Comprehensive error handling
-   Thread-safe operations

### HttrackSettings

Configuration class extending DownloadSettings with httrack-specific options:

-   URL and output directory configuration
-   Crawling depth and external link settings
-   File type filters (images, videos, audio, documents)
-   Connection and rate limiting options
-   Proxy configuration
-   Pattern-based include/exclude filters
-   Custom httrack command-line options

### HttrackJob

Represents a mirroring job with status tracking:

-   Job lifecycle management (PENDING, RUNNING, PAUSED, COMPLETED, ERROR, CANCELED)
-   Progress information (files downloaded, bytes transferred, transfer rate)
-   Timing information (duration, estimated time remaining)
-   Error reporting

### HttrackDownloadHandler

Integration with the download manager system for website scraping downloads. The handler now uses HttrackClient internally for improved functionality:

-   Delegates httrack process management to HttrackClient
-   Provides seamless integration between Download objects and HttrackJob objects
-   Maps download lifecycle events to httrack job events
-   Supports all HttrackClient features through the download manager interface

## Architecture

The httrack package follows a layered architecture:

1. **HttrackClient** - Low-level client for direct httrack interaction
2. **HttrackDownloadHandler** - High-level integration with download manager
3. **Download Manager** - Uses the handler for website scraping downloads

The HttrackDownloadHandler now uses HttrackClient internally, providing:

-   Better separation of concerns
-   Improved error handling and progress tracking
-   Access to advanced httrack features
-   Consistent behavior with other download handlers

## Usage

### Basic Example

```java
HttrackClient client = new HttrackClient();

HttrackSettings settings = new HttrackSettings();
settings.setUrl("https://example.com")
        .setOutputDirectory(Paths.get("/tmp/mirror"))
        .setDepth(3);

String jobId = client.startMirror(settings).join();
```

### Advanced Configuration

```java
HttrackSettings settings = new HttrackSettings();
settings.setUrl("https://example.com")
        .setOutputDirectory(Paths.get("/tmp/mirror"))
        .setDepth(5)
        .setFollowExternalLinks(false)
        .setConnections(8)
        .setMaxRate(2000) // 2MB/s
        .setIncludeImages(true)
        .setIncludeVideos(false)
        .addIncludePattern("*.html")
        .addExcludePattern("*/admin/*")
        .addAdditionalOption("K", ""); // Keep original links
```

### Progress Monitoring

```java
client.addNotificationListener(new HttrackClient.HttrackNotificationListener() {
    @Override
    public void onJobProgress(HttrackJob job) {
        System.out.printf("Progress: %.1f%% (%d/%d files)%n",
            job.getProgress(), job.getFilesDownloaded(), job.getTotalFiles());
    }
});
```

## Dependencies

-   httrack must be installed and available in system PATH
-   Java 21 or higher

## Integration with Download Manager

The HttrackDownloadHandler now leverages HttrackClient for all operations:

```java
// The handler automatically creates HttrackClient instance
HttrackDownloadHandler handler = new HttrackDownloadHandler(
    globalSettings, settingsFactory, executor);

// Downloads are automatically mapped to httrack jobs
Download download = new Download(new URI("https://example.com"));
download.setType(Download.Type.WEBSITE_SCRAPING);
download.setSettings(httrackSettings);

// Handler uses HttrackClient internally
CompletableFuture<String> future = handler.startDownload(download);

// Monitor using both Download and HttrackJob APIs
HttrackJob job = handler.getJobForDownload(download);
String jobId = handler.getJobIdForDownload(download);
```

### Handler Features

-   **Automatic Mapping**: Downloads are automatically mapped to httrack jobs
-   **Event Translation**: Download events are synchronized with httrack job events
-   **Settings Integration**: HttrackSettings are automatically applied
-   **Progress Tracking**: Real-time progress updates through both APIs
-   **Error Handling**: Comprehensive error reporting and recovery

## Files

-   `HttrackClient.java` - Main client implementation
-   `HttrackJob.java` - Job representation and tracking
-   `HttrackSettings.java` - Configuration class
-   `HttrackDownloadHandler.java` - Download manager integration (updated to use HttrackClient)
-   `HttrackClientExample.java` - Direct client usage examples
-   `HttrackDownloadHandlerExample.java` - Download handler usage examples
-   `HttrackClientIntegrationTest.java` - Integration tests
-   `README_CLIENT.md` - Detailed client documentation

## Command Line Reference

| Use Case                           | Example Command                                      |
| ---------------------------------- | ---------------------------------------------------- |
| Full site mirror                   | `httrack "https://example.com" -O ./site_backup -%v` |
| Resume interrupted download        | `httrack --continue`                                 |
| Limit depth (e.g. 5 levels)        | add `-r5` and/or `-e5`                               |
| Filter file types                  | e.g. `"+*.jpg" "+*.png" "-*"`                        |
| Mirror single page                 | `-N0 --depth=1`                                      |
| Use proxy or set rate limits       | `-P proxy:port` or `-A10000 -G...`                   |
| Preserve or disable external links | use `-K` or `-x`                                     |
