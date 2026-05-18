# Open Download Manager Settings System

The Open Download Manager now includes a robust settings system that allows fine-grained control over downloads. This document describes how to use the new settings system to configure downloads with specific options.

## Settings Architecture

The settings system is organized in a hierarchical structure:

- `GlobalSettings`: System-wide settings that apply to all downloads
- `DownloadSettings`: Base abstract class for all download-specific settings
  - `Aria2Settings`: Settings specific to aria2 downloads (HTTP, FTP, Magnet, Torrent)
  - `CurlSettings`: Settings specific to curl downloads
  - `YtDlpSettings`: Settings specific to YouTube downloads
  - `ProxychainsSettings`: Settings specific to proxychains downloads

This design provides several advantages:
- Type safety with proper validation
- Better IDE auto-completion
- Clearer organization of settings
- Easier to add new download types and settings

## Using Global Settings

Global settings affect the entire download manager and all downloads. You can access and modify them through the DownloadManager interface:

```java
// Get the download manager instance
DownloadManager manager = DownloadManagerFactory.getInstance();

// Get the global settings
GlobalSettings globalSettings = manager.getGlobalSettings();

// Configure global settings
globalSettings.setMaxConcurrentDownloads(3)
             .setGlobalSpeedLimit(5000)  // 5000 KB/s = 5 MB/s
             .setGlobalProxyEnabled(true)
             .setGlobalProxyAddress("socks5h://127.0.0.1:9050")
             .setDefaultDownloadDirectory(Paths.get(System.getProperty("user.home"), "Downloads", "odm"));

// Apply the updated global settings
manager.setGlobalSettings(globalSettings);
```

## Using Download-Specific Settings

Each download can have its own specific settings based on the download type. Here's how to configure them:

### Aria2 Settings (HTTP, FTP, Magnet, Torrent)

```java
// Create a download
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

// Queue the download with the new settings
manager.queueDownload(download);
```

### Curl Settings

```java
URI uri = new URI("https://example.org/secure-download.zip");
Download download = manager.createDownload(uri, null);
download.setType(Download.Type.CURL);

// Configure Curl-specific settings
CurlSettings curlSettings = new CurlSettings();
curlSettings.setConnectTimeout(60)
           .setRetryCount(5)
           .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
           .setReferer("https://example.org/")
           .setInsecureMode(true);  // Skip SSL verification

// Apply the settings to the download
download.setSettings(curlSettings);

// Queue the download with the new settings
manager.queueDownload(download);
```

### YouTube Download Settings

```java
URI uri = new URI("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
Download download = manager.createDownload(uri, null);
download.setType(Download.Type.YOUTUBE);

// Configure YtDlp-specific settings
YtDlpSettings ytSettings = new YtDlpSettings();
ytSettings.setFormat("bestvideo[height<=1080]+bestaudio/best[height<=1080]")
         .setEmbedThumbnail(true)
         .setEmbedMetadata(true)
         .setWriteSubtitles(true)
         .addSubtitleLanguage("en");

// Apply the settings to the download
download.setSettings(ytSettings);

// Queue the download with the new settings
manager.queueDownload(download);
```

### Proxychains Settings

```java
URI uri = new URI("https://example.onion/hidden-file.zip");
Download download = manager.createDownload(uri, null);
download.setType(Download.Type.PROXYCHAINS);

// Configure Proxychains-specific settings
ProxychainsSettings proxySettings = new ProxychainsSettings();
proxySettings.setProgram("curl")
            .setTorMode(true)
            .setQuiet(false);

// Apply the settings to the download
download.setSettings(proxySettings);

// Queue the download with the new settings
manager.queueDownload(download);
```

## Backward Compatibility

The new settings system maintains backward compatibility with the old options system. You can still use the legacy methods, but they are now marked as deprecated:

```java
// Legacy way (deprecated)
download.setOption("max-connection-per-server", "16");
download.setConnections(16);
download.setUseProxy(true);
download.setProxyAddress("socks5h://127.0.0.1:9050");

// New way (preferred)
Aria2Settings settings = (Aria2Settings) download.getSettings();
settings.setMaxConnectionPerServer(16)
        .setConnections(16)
        .setUseProxy(true)
        .setProxyAddress("socks5h://127.0.0.1:9050");
```

## Converting Between Settings and Options Map

For compatibility with external systems or plugins, the settings can be converted to and from option maps:

```java
// Get settings as a map
Map<String, String> optionsMap = download.getSettings().toMap();

// Use the map for external systems
System.out.println("Options: " + optionsMap);
```

## Best Practices

1. Use the most specific settings class for the download type
2. Chain settings methods for more concise code
3. Configure all settings before queueing the download
4. Use proper settings instead of legacy options map
5. Set global settings that apply to all downloads, and override only what's necessary for specific downloads