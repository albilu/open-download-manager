# open-download-manager

A full featured native download manager for Linux based on aria2, yt-dlp and httrack

## Compatibility

-   Java 25
-   GTK 4 through java-gi

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
-   [LATER] i18n
-   [LATER] FTP Login & Anonymous FTP
-   [LATER] theme support
-   [LATER] Keyboard accessibility
-   [LATER] Interface to Search Torrents
    -   https://github.com/Jackett/Jackett?tab=readme-ov-file
    -   https://prowlarr.com/
-   [LATER] Interface to download videos (youtube etc...)
-   [LATER] Downloader: derive m4s manifestv
        -the m4s support means that the download manager can download media from streaming services that use fragmented mp4 files. The download manager should be able to detect such format:
        1. by its manifest (ex: http://example.com/playlist.m3u8) => yt-dlp http://example.com/manifest.m3u8.
        2. or derive the manifest or media segments from the streaming service video page (ex: http://example.com/video-page) .
-   [LATER] Consider
	- replace odm-state.json persistence by Sqlite for better downloads managment
	- User agent rotation
	- Clipboard monitor waitlist
		- Meaning detected url are silently places in a waitlist and dont popup like in uget
		- User can then start download from the waitlist
-   [LATER] QT version

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
