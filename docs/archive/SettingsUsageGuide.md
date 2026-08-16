# Settings Usage Guide for Open Download Manager

This guide explains how to properly use the settings system in Open Download Manager, including best practices and example code for different scenarios.

## Table of Contents

1. [Overview](#overview)
2. [Global Settings](#global-settings)
3. [Download Settings](#download-settings)
4. [Settings Factory](#settings-factory)
5. [External Tool Settings](#external-tool-settings)
6. [Best Practices](#best-practices)
7. [Migration from Legacy API](#migration-from-legacy-api)

## Overview

The Open Download Manager uses a structured settings system with two main components:

- **GlobalSettings**: System-wide settings that apply to all downloads
- **DownloadSettings**: Per-download settings with specialized subclasses for different download types

This design provides a clean separation between global and per-download configuration, makes settings persistent, and allows for easy customization of different download types.

## Global Settings

Global settings affect all downloads and the overall behavior of the download manager.

### Accessing Global Settings

```java
// Get the download manager instance
DownloadManager manager = DownloadManagerFactory.getInstance();

// Get the current global settings
GlobalSettings settings = manager.getGlobalSettings();
```

### Modifying Global Settings

```java
// Modify settings
settings.setMaxConcurrentDownloads(5);
settings.setGlobalSpeedLimit(1024); // 1024 KB/s = 1 MB/s
settings.setDefaultDownloadDirectory(Paths.get("/path/to/downloads"));
settings.setGlobalProxyEnabled(true);
settings.setGlobalProxyAddress("http://proxy.example.com:8080");
settings.setSaveDownloadHistory(true);

// Apply the modified settings
manager.setGlobalSettings(settings);
```

### Tool Path Configuration

Global settings also manage paths to external tools:

```java
// Configure tool paths
settings.setAria2Path("/usr/local/bin/aria2c");
settings.setYtDlpPath("/usr/local/bin/yt-dlp");
settings.setHttrackPath("/usr/bin/httrack");
settings.setCurlPath("/usr/bin/curl");
settings.setProxychainsPath("/usr/bin/proxychains");

// Apply the modified settings
manager.setGlobalSettings(settings);
```

## Download Settings

Each download has its own settings object, tailored to its download type.

### Accessing Download Settings

```java
// Create a download
Download download = manager.createDownload(new URI("https://example.com/file.zip"), null);

// Get the settings for this download
DownloadSettings settings = download.getSettings();

// For type-specific settings, cast to the appropriate type
if (settings instanceof Aria2Settings) {
    Aria2Settings aria2Settings = (Aria2Settings) settings;
    // Configure aria2-specific settings
    aria2Settings.setMaxConnectionPerServer(10);
    aria2Settings.setMinSplitSize(5); // 5MB
}
```

### Available Download Settings Types

The download manager supports several specialized settings types:

1. **Aria2Settings**: For HTTP, FTP, BitTorrent, and Magnet downloads
2. **CurlSettings**: For downloads using curl
3. **YtDlpSettings**: For YouTube and streaming site downloads
4. **HttrackSettings**: For website scraping
5. **ProxychainsSettings**: For downloads through proxy chains

### Common Settings for All Download Types

All download types share these common settings:

```java
// These work for any download type
settings.setConnections(5);
settings.setUseProxy(true);
settings.setProxyAddress("http://proxy.example.com:8080");
```

### Type-Specific Settings Examples

#### HTTP/FTP Downloads with aria2

```java
Download httpDownload = manager.createDownload(new URI("https://example.com/file.zip"), null);
Aria2Settings settings = (Aria2Settings) httpDownload.getSettings();

settings.setMaxConnectionPerServer(16);
settings.setContinueDownload(true);
settings.setMinSplitSize(10); // 10MB
settings.setFileAllocation("falloc");
settings.setCheckIntegrity(true);
settings.setRetryWait(5);
settings.setMaxTries(5);
```

#### YouTube Downloads with yt-dlp

```java
Download ytDownload = manager.createYoutubeDownload(
    new URI("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), 
    null, 
    null
);

YtDlpSettings settings = (YtDlpSettings) ytDownload.getSettings();
settings.setFormat("bestvideo+bestaudio/best");
settings.setWriteThumbnail(true);
settings.setWriteSubtitles(true);
settings.setSubtitleLanguage("en");
settings.setExtractAudio(false);
```

#### Website Downloads with httrack

```java
Download webDownload = manager.createWebsiteDownload(
    new URI("https://example.com"), 
    null, 
    null
);

HttrackSettings settings = (HttrackSettings) webDownload.getSettings();
settings.setRecursionDepth(3);
settings.setIncludeExternalLinks(false);
settings.setWildcardFilters("+*.png +*.jpg +*.css +*.js");
```

## Settings Factory

The `DownloadSettingsFactory` provides a centralized way to create appropriate settings objects with sensible defaults:

```java
// Create a factory with the current global settings
DownloadSettingsFactory factory = new DownloadSettingsFactory(globalSettings);

// Create settings for different download types
Aria2Settings httpSettings = factory.createAria2Settings();
YtDlpSettings ytSettings = factory.createYtDlpSettings();
HttrackSettings webSettings = factory.createHttrackSettings();
CurlSettings curlSettings = factory.createCurlSettings();
ProxychainsSettings proxySettings = factory.createProxychainsSettings();
```

## External Tool Settings

You can set advanced options for external tools using the generic option method:

```java
// For aria2 downloads
Aria2Settings aria2Settings = (Aria2Settings) download.getSettings();
aria2Settings.setOption("bt-prioritize-piece", "head");
aria2Settings.setOption("disk-cache", "64M");

// For YouTube downloads
YtDlpSettings ytSettings = (YtDlpSettings) download.getSettings();
ytSettings.setOption("geo-bypass", "true");
ytSettings.setOption("add-metadata", "true");
```

## Best Practices

1. **Use Type-Specific Settings**: Always cast to the specific settings type when possible to access specialized settings.

2. **Apply Settings Before Starting**: Configure all settings before queueing or starting the download.

3. **Use the Factory**: For new downloads, use the settings factory to get proper defaults.

4. **Avoid Direct Field Access**: Always use getters and setters to modify settings.

5. **Batch Settings Updates**: Make all settings changes before applying them to avoid multiple update events.

6. **Check Tool Availability**: Before using specialized download types, check if the required tools are available:

   ```java
   if (globalSettings.isYtDlpAvailable()) {
       // Create a YouTube download
   } else {
       // Show an error or fallback to another method
   }
   ```

## Migration from Legacy API

If you're migrating from the older API, here are the equivalent patterns:

### Old Way:

```java
// Setting connections directly
download.setConnections(10);

// Setting proxy directly
download.setUseProxy(true);
download.setProxyAddress("http://proxy.example.com:8080");

// Setting options directly
download.setOption("min-split-size", "10M");
```

### New Way:

```java
// Getting the appropriate settings object
DownloadSettings settings = download.getSettings();

// Setting connections
settings.setConnections(10);

// Setting proxy
settings.setUseProxy(true);
settings.setProxyAddress("http://proxy.example.com:8080");

// Setting specific options
if (settings instanceof Aria2Settings) {
    Aria2Settings aria2Settings = (Aria2Settings) settings;
    aria2Settings.setMinSplitSize(10); // 10MB
} else {
    // Generic option setting for other types
    settings.setOption("min-split-size", "10M");
}
```

The new approach provides better type safety, clearer organization, and more powerful configuration options.