# Download Engine

This package provides a full-featured download engine for managing multiple downloads simultaneously. It supports various protocols including HTTP, FTP, BitTorrent, and Magnet links, as well as YouTube video downloading and website scraping.

## Overview

The download engine is built on top of the [aria2](https://aria2.github.io/) download utility, providing a Java interface to manage downloads with features such as:

-   Multi-connection downloads
-   Download queue management
-   Pause/resume functionality
-   Real-time progress tracking (via polling mechanism)
-   Proxy support (including Tor, Proxychains, and Curl)
-   BitTorrent downloads
-   YouTube video downloads (via yt-dlp)
-   Website scraping (via httrack)
-   After-completion actions (move files, shutdown computer, antivirus check, etc.)

## Key Components

-   **Download**: Model class representing a download task with properties like status, progress, and type.
-   **DownloadListener**: Interface for receiving download events and status updates.
-   **DownloadManager**: Interface defining the core functionality of the download engine.
-   **DownloadManagerImpl**: Implementation of the DownloadManager interface using aria2 as the backend.
-   **DownloadManagerFactory**: Factory for creating instances of the DownloadManager.
-   **AfterCompletionAction**: Interface for actions to be executed after a download completes.
-   **AfterCompletionActionManager**: Manages the execution of after-completion actions.

## Usage

### Initialization

```java
// Get the singleton instance
DownloadManager manager = DownloadManagerFactory.getInstance();

// Initialize the manager
manager.initialize().join();

// Or create a custom instance
DownloadManager customManager = DownloadManagerFactory.createCustomManager(
    Paths.get("/path/to/downloads"),
    5,   // 5 concurrent downloads
    0    // No speed limit
);
customManager.initialize().join();
```

### Creating Downloads

```java
// Create an HTTP download
Download httpDownload = manager.createDownload(
    new URI("https://example.com/file.zip"),
    Paths.get("/path/to/save")
);

// Create a torrent download
Download torrentDownload = manager.createTorrentDownload(
    Paths.get("/path/to/file.torrent"),
    Paths.get("/path/to/save")
);

// Create a magnet download
Download magnetDownload = manager.createMagnetDownload(
    new URI("magnet:?xt=urn:btih:..."),
    Paths.get("/path/to/save")
);

// Create a YouTube download
Download youtubeDownload = manager.createYoutubeDownload(
    new URI("https://www.youtube.com/watch?v=..."),
    Paths.get("/path/to/save"),
    new HashMap<>()
);

// Configure YouTube-specific options
youtubeDownload.getSettings().setOption("format", "best");
youtubeDownload.getSettings().setOption("extract-audio", "true");
```

### Managing Downloads

```java
// Queue a download (will start if under concurrent limit)
manager.queueDownload(download).join();

// Start a download immediately
manager.startDownload(download).join();

// Pause a download
manager.pauseDownload(download).join();

// Resume a download
manager.resumeDownload(download).join();

// Cancel a download (optionally delete files)
manager.cancelDownload(download, true).join();
```

### Tracking Download Progress

```java
// Add a listener to receive download events
manager.addDownloadListener(new DownloadListener() {
    @Override
    public void onDownloadStart(Download download) {
        System.out.println("Download started: " + download.getName());
    }

    @Override
    public void onDownloadProgress(Download download, float progress,
                                 long downloadedBytes, long totalBytes, float speed) {
        System.out.printf("Progress: %.2f%% at %.2f KB/s (%d/%d bytes)\n",
                        progress, speed / 1024, downloadedBytes, totalBytes);
    }

    @Override
    public void onDownloadComplete(Download download) {
        System.out.println("Download completed: " + download.getName());
    }

    // Implement other methods as needed
});
```

### Global Settings

```java
// Get global settings
GlobalSettings settings = manager.getGlobalSettings();

// Configure settings
settings.setDefaultDownloadDirectory(Paths.get("/path/to/downloads"));
settings.setMaxConcurrentDownloads(5);
settings.setGlobalSpeedLimit(1024); // 1 MB/s (in KB/s)
settings.setGlobalProxyEnabled(true);
settings.setGlobalProxyAddress("http://proxy.example.com:8080");

// Apply the modified settings
manager.setGlobalSettings(settings);

// For Tor proxy configuration
settings.setGlobalProxyAddress("socks5h://127.0.0.1:9050");
manager.setGlobalSettings(settings);
```

### Querying Downloads

```java
// Get a specific download by ID
Download download = manager.getDownload("download-id");

// Get all downloads
List<Download> allDownloads = manager.getAllDownloads();

// Get downloads by status
List<Download> activeDownloads = manager.getDownloadsByStatus(Download.Status.DOWNLOADING);
List<Download> completedDownloads = manager.getDownloadsByStatus(Download.Status.COMPLETED);
```

### After-Completion Actions

The download engine supports executing actions after a download completes:

```java
// Add an action to move the file after download completes
manager.addAfterCompletionAction(download,
    new MoveFileAction(Paths.get("/path/to/destination"), true));

// Add an action to shut down the computer after download completes
manager.addAfterCompletionAction(download,
    new ShutdownComputerAction(60)); // 60 seconds delay

// Add an action to scan the file with antivirus after download completes
manager.addAfterCompletionAction(download,
    new AntivirusCheckAction(AntivirusCheckAction.AntivirusType.CLAMAV, 300)); // 5 minute timeout

// Get all actions for a download
List<AfterCompletionAction> actions = manager.getAfterCompletionActions(download);

// Remove an action
manager.removeAfterCompletionAction(download, action);

// Add a listener for action events
manager.addAfterCompletionActionListener(new AfterCompletionActionListener() {
    @Override
    public void onActionStart(Download download, AfterCompletionAction action) {
        System.out.println("Action started: " + action.getDescription());
    }

    @Override
    public void onActionComplete(Download download, AfterCompletionAction action) {
        System.out.println("Action completed: " + action.getDescription());
    }

    // Implement other methods as needed
});

// Manually execute all after-completion actions for a download
manager.executeAfterCompletionActions(download).join();
```

### Shutdown

```java
// Shutdown the manager when done
manager.shutdown().join();
```

## Prerequisites

This download engine requires:

1. java 21 or higher
2. aria2c executable in the system PATH
3. yt-dlp executable in the system PATH (for YouTube downloads)
4. httrack executable in the system PATH (for website scraping)
5. Optional: file scanners (ClamAV or a custom scanner command) for scan actions

## Persistent State

The download manager automatically saves the state of all downloads when shutdown, and restores them when initialized. This allows for downloads to be resumed even after the application is restarted.

## Download Types

The download engine supports various types of downloads:

-   **HTTP/HTTPS**: Standard web downloads
-   **FTP**: File Transfer Protocol downloads
-   **TORRENT**: BitTorrent downloads from .torrent files
-   **MAGNET**: BitTorrent downloads from magnet links
-   **YOUTUBE**: Video downloads using yt-dlp
-   **WEBSITE_SCRAPING**: Website scraping using httrack
-   **TOR**: Downloads through the Tor network
-   **PROXYCHAINS**: Downloads through proxychains
-   **CURL**: Fallback downloads using curl

## Progress Tracking

The download engine implements a polling mechanism to track download progress in real-time:

```java
// The polling happens automatically when downloads are started
// Your code just needs to register a listener:
manager.addDownloadListener(new DownloadListener() {
    @Override
    public void onDownloadProgress(Download download, float progress,
                                  long downloadedBytes, long totalBytes, float speed) {
        // Update UI or log progress
        System.out.printf("Download: %s - %.2f%% complete at %.2f KB/s\n",
                        download.getName(), progress, speed / 1024);

        // You can also access the download's current status
        if (download.getStatus() == Download.Status.DOWNLOADING) {
            // Do something while downloading
        }
    }
});
```

The progress polling mechanism:

-   Polls the aria2 RPC server at regular intervals (default: every 1 second)
-   Uses the `aria2.tellStatus` method to retrieve current download status
-   Automatically stops polling when downloads complete, pause, or encounter errors
-   Resumes polling when downloads are resumed
-   Properly cleans up all polling tasks when the download manager is shut down
