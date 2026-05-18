# HttrackClient

A high-level Java client for interfacing with the httrack website mirroring tool. This client provides a clean, asynchronous API for website scraping and mirroring operations.

## Overview

The HttrackClient is part of the Open Download Manager project and provides:

-   **Asynchronous Operations**: All operations return CompletableFuture for non-blocking execution
-   **Progress Monitoring**: Real-time progress tracking with notification listeners
-   **Job Management**: Start, pause, resume, and cancel mirroring jobs
-   **Flexible Configuration**: Extensive settings through HttrackSettings
-   **Error Handling**: Comprehensive error reporting and logging

## Key Components

### HttrackClient

The main client class that manages httrack processes and provides the public API.

### HttrackJob

Represents a running or completed mirroring job with status, progress, and metadata.

### HttrackSettings

Configuration class that extends DownloadSettings with httrack-specific options.

## Basic Usage

```java
// Create client
HttrackClient client = new HttrackClient();

// Configure settings
HttrackSettings settings = new HttrackSettings();
settings.setUrl("https://example.com")
        .setOutputDirectory(Paths.get("/tmp/mirror"))
        .setDepth(3)
        .setIncludeImages(true)
        .setIncludeVideos(false);

// Start mirroring
CompletableFuture<String> future = client.startMirror(settings);
future.thenAccept(jobId -> {
    System.out.println("Started job: " + jobId);
});
```

## Advanced Configuration

```java
HttrackSettings settings = new HttrackSettings();
settings.setUrl("https://example.com")
        .setOutputDirectory(Paths.get("/tmp/advanced_mirror"))
        .setDepth(5)
        .setFollowExternalLinks(false)
        .setConnections(8)
        .setMaxRate(2000) // 2MB/s limit
        .setUserAgent("CustomBot/1.0")

        // File type filters
        .setIncludeImages(true)
        .setIncludeVideos(false)
        .setIncludeAudio(false)
        .setIncludeDocuments(true)

        // Pattern filters
        .addIncludePattern("*.html")
        .addIncludePattern("*.css")
        .addExcludePattern("*/admin/*")
        .addExcludePattern("*/private/*")

        // Custom httrack options
        .addAdditionalOption("K", "") // Keep original links
        .addAdditionalOption("s", "2"); // Follow robots.txt

// Proxy configuration
settings.setUseProxy(true)
        .setProxyAddress("proxy.example.com:8080");
```

## Progress Monitoring

```java
client.addNotificationListener(new HttrackClient.HttrackNotificationListener() {
    @Override
    public void onJobStarted(HttrackJob job) {
        System.out.println("Job started: " + job.getJobId());
    }

    @Override
    public void onJobProgress(HttrackJob job) {
        System.out.printf("Progress: %.1f%% (%d/%d files, %s/s)%n",
            job.getProgress(),
            job.getFilesDownloaded(),
            job.getTotalFiles(),
            formatBytes(job.getTransferRate()));
    }

    @Override
    public void onJobCompleted(HttrackJob job) {
        System.out.println("Job completed: " + job.getJobId());
    }

    @Override
    public void onJobError(HttrackJob job, String errorMessage) {
        System.err.println("Job error: " + errorMessage);
    }
});
```

## Job Management

```java
// Start a job
String jobId = client.startMirror(settings).join();

// Get job status
HttrackJob job = client.getJobStatus(jobId);
System.out.println("Status: " + job.getStatus());
System.out.println("Progress: " + job.getProgress() + "%");

// Pause job
client.pauseJob(jobId).join();

// Resume job
client.resumeJob(jobId).join();

// Cancel job (optionally delete files)
client.cancelJob(jobId, true).join();

// Get all active jobs
Map<String, HttrackJob> activeJobs = client.getActiveJobs();
```

## HttrackJob Status and Information

```java
HttrackJob job = client.getJobStatus(jobId);

// Status information
HttrackJob.Status status = job.getStatus(); // PENDING, RUNNING, PAUSED, COMPLETED, ERROR, CANCELED
boolean isActive = job.isActive();
boolean isCompleted = job.isCompleted();

// Progress information
float progress = job.getProgress(); // 0-100%
long filesDownloaded = job.getFilesDownloaded();
long totalFiles = job.getTotalFiles();
long bytesDownloaded = job.getBytesDownloaded();
int transferRate = job.getTransferRate(); // bytes/second

// Timing information
LocalDateTime createdAt = job.getCreatedAt();
LocalDateTime startedAt = job.getStartedAt();
LocalDateTime completedAt = job.getCompletedAt();
long durationMs = job.getDurationMillis();
long remainingMs = job.getEstimatedTimeRemainingMillis();

// Error information
String errorMessage = job.getErrorMessage();

// Summary
String summary = job.getSummary();
```

## Available HttrackSettings Options

### Basic Options

-   `url` - The website URL to mirror
-   `outputDirectory` - Directory to save the mirrored website
-   `depth` - Maximum crawling depth (default: 5)
-   `followExternalLinks` - Whether to follow external links (default: false)

### Connection Options

-   `connections` - Number of simultaneous connections (default: 8)
-   `maxRate` - Maximum transfer rate in KB/s (0 = unlimited)
-   `userAgent` - Custom user agent string

### File Type Filters

-   `includeImages` - Include image files (default: true)
-   `includeVideos` - Include video files (default: true)
-   `includeAudio` - Include audio files (default: true)
-   `includeDocuments` - Include document files (default: true)
-   `includeArchives` - Include archive files (default: false)

### Pattern Filters

-   `includePatterns` - List of patterns to include (e.g., "\*.html")
-   `excludePatterns` - List of patterns to exclude (e.g., "_/admin/_")

### Proxy Options

-   `useProxy` - Enable proxy usage
-   `proxyAddress` - Proxy server address
-   `proxyUsername` - Proxy authentication username
-   `proxyPassword` - Proxy authentication password

### Advanced Options

-   `mirrorMode` - Enable mirror mode (default: true)
-   `additionalOptions` - Custom httrack command-line options

## Error Handling

```java
client.startMirror(settings)
    .thenAccept(jobId -> {
        // Success
        System.out.println("Job started: " + jobId);
    })
    .exceptionally(throwable -> {
        // Error handling
        System.err.println("Failed to start mirror: " + throwable.getMessage());
        return null;
    });
```

## Prerequisites

-   httrack must be installed and available in the system PATH
-   Java 21 or higher
-   Sufficient disk space for mirrored content

## Installation Check

```java
client.isHttrackAvailable().thenAccept(available -> {
    if (available) {
        System.out.println("httrack is available");
    } else {
        System.err.println("httrack is not installed or not in PATH");
    }
});
```

## Shutdown

```java
// Gracefully shutdown the client
client.shutdown();
```

This will cancel all active jobs and shutdown the internal executor service.

## Thread Safety

The HttrackClient is thread-safe and can be used concurrently from multiple threads. All operations are non-blocking and return CompletableFuture instances.

## Integration with Download Manager

The HttrackClient is designed to be used by the HttrackDownloadHandler as part of the broader download management system. It can also be used standalone for website mirroring tasks.

## Examples

See `HttrackClientExample.java` for complete usage examples including:

-   Basic website mirroring
-   Advanced configuration
-   Progress monitoring
-   Pause/resume functionality
