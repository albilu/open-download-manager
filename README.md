# open-download-manager

A full featured native download manager for Linux based on aria2, yt-dlp and httrack

## Compatibility

-   java 21
-   GTK 3 and GTK 4 through JNA
-   QT

## Deployment matrix

| Toolkit | Desktop Environments                | OS    | Packages                |
| ------- | ----------------------------------- | ----- | ----------------------- |
| GTK     | GNOME, XFCE, Cinnamon, MATE, Fedora | Linux | .deb, .rpm, .pkg.tar.gz |
| QT      | KDE Plasma, LXQt, Deepin, LXDE      | Linux | .deb, .rpm, .pkg.tar.gz |

## MVP Features

-   Multi-Connection Downloads
-   Clipboard Monitor
-   Downloads Queue
-   Pause/Resume Downloads
-   After Completion Actions (shutdown computer, move file etc...)
-   Multiple Mirrors/Sources
-   Multiple Protocols (HTTP, BitTorrent, Magnet)
-   Background Mode (with tray icon)
-   Download History
-   Plug into proxychains for SOCKS4/5 proxies and Tor download (fall back to curl)
-   Tor support

## Enhancements

-   Delete related files when removing tasks (optional)
-   Predefined regex for specific categories
-   Batch Downloads (import list of url in txt file)
-   speed limit
-   Update tracker list every day automatically
-   Torents property dialog
-   [LATER] Browser extension
    -   Right click download
    -   Detect files from selection
    -   yt-dlp integration to download videos
-   [LATER] Command-Line / Terminal
-   [LATER] i18n
-   [LATER] FTP Login & Anonymous FTP
-   [LATER] theme support
-   [LATER] Keyboard Shortcuts
-   [LATER] Interface to Search Torrents
    -   https://github.com/Jackett/Jackett?tab=readme-ov-file
    -   https://prowlarr.com/
-   [LATER] Interface to download videos (youtube etc...)
-   [LATER] QT version

## Development Steps

1. Create Java project architecture (Maven)
2.  - **Notes:**

        - Prioritize performance, memory efficiency, and stability.
        - Align with Gnome/GTK by using approriate widjets, APIs
        - Skip unit tests for now.

    - **Tasks:**

        - A. Set up Java bindings for GTK using JNA (ensure codebase is reusable for QT).
        - B. Set up maven Build for Linux: Package as .deb, .rpm, .pkg.tar.gz
        - C. Implement a wrapper for aria2 supporting features relevant for this app.-
        - D. Design main windows (refer to uGet screenshots).\*
        - E. Implement the corresponding Glade file for each window screenshot.\*
        - F. Implement Download engine: Develop logic to manage multiple downloads, queue, and pause/resume functionality.
            - Plug into proxychains for SOCKS4/5 proxies and Tor download
            - if proxychains fails, fallback to curl (except torrents...)
        - G. Keep track of downloads (resume even after exit).
        - H. Implement Clipboard monitor: Use Java Clipboard API to detect new URLs.
        - I. Implement Tray icon: GTK system tray for background mode.\*
        - J. Tor support: Allow proxy configuration for downloads (for a single download or all downloads).
        - K. Implement After Completion Actions (shutdown computer, move file etc...)
            - Automatic antivirus check (chkrootkit etc…)
        - Scrap website (with httrack)
        - Download completed Notification
        - Scheduler
        - Automatic Proxy rotation
        - Download Youtube Videos (with yt-dlp)

3. Implement Enhancements
4. Testing & Documentation
    - Write unit/integration tests.
    - Document features and usage in README.
5. Open Source & Community
    - Host on GitHub.
    - Encourage contributions and feedback.
        - gnome forums
        - linux forums
        - alternativeto

## Concurrent apps

-   uget
-   Varia
-   https://github.com/agalwood/Motrix
-   Persepolis Download Manager
-   AB Download Manager
-   kget
-   JDownloader
-   idm
-   https://github.com/filecxx/FileCentipede (Proprietary)

## Progress Notes

All clients implement progress throttling to prevent UI flooding:

1. **Time-based throttling**: Updates limited to 1-second intervals
2. **Size-based throttling**: Updates when significant data transferred (1MB)
3. **Percentage-based throttling**: Updates at specific percentage intervals

## Example Usage with New Settings System

### Global Settings

```java
// Get the download manager instance
DownloadManager manager = DownloadManagerFactory.getInstance();

// Configure global settings
GlobalSettings globalSettings = manager.getGlobalSettings();
globalSettings.setMaxConcurrentDownloads(3)
             .setGlobalSpeedLimit(5000)  // 5000 KB/s = 5 MB/s
             .setDefaultDownloadDirectory(Paths.get(System.getProperty("user.home"), "Downloads", "odm"));

// Apply the updated global settings
manager.setGlobalSettings(globalSettings);
```

### Download-Specific Settings

```java
// Create an HTTP download with aria2 settings
URI uri = new URI("https://example.com/large-file.iso");
Download download = manager.createDownload(uri, null);

// Configure Aria2-specific settings
Aria2Settings aria2Settings = new Aria2Settings();
aria2Settings.setMaxConnectionPerServer(16)
            .setMinSplitSize(10)  // 10 MB
            .setFileAllocation("falloc")
            .setRetryWait(10)
            .setCheckIntegrity(true);

// Apply the settings to the download
download.setSettings(aria2Settings);

// Queue the download
manager.queueDownload(download).join();
```

See `.github/copilot-instructions.md` for workspace-specific coding guidelines.
