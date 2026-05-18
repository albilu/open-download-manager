# Dependency Validation Guide

This document explains how to use the new dependency validation features in the Open Download Manager, which allow you to check minimum version requirements and verify protocol/feature support for aria2c, curl, and other dependencies.

## Overview

The dependency validation system provides two main components:

1. **DependencyManager** - Core functionality for checking tool availability, versions, and features
2. **DependencyValidator** - High-level validation methods for common use cases

## Basic Usage

### Setting Up

```java
import org.manager.DependencyManager;
import org.manager.DependencyValidator;
import org.manager.GlobalSettings;

// Create global settings and dependency manager
GlobalSettings settings = new GlobalSettings();
DependencyManager dependencyManager = new DependencyManager(settings);
DependencyValidator validator = new DependencyValidator(dependencyManager);
```

### Checking Tool Availability

```java
// Check if tools are available
boolean aria2Available = dependencyManager.isToolAvailable(DependencyManager.ARIA2);
boolean curlAvailable = dependencyManager.isToolAvailable(DependencyManager.CURL);
boolean ytdlpAvailable = dependencyManager.isToolAvailable(DependencyManager.YT_DLP);

System.out.println("aria2c available: " + aria2Available);
System.out.println("curl available: " + curlAvailable);
System.out.println("yt-dlp available: " + ytdlpAvailable);
```

### Getting Tool Versions

```java
// Get tool versions
String aria2Version = dependencyManager.getToolVersion(DependencyManager.ARIA2);
String curlVersion = dependencyManager.getToolVersion(DependencyManager.CURL);

System.out.println("aria2c version: " + aria2Version);
System.out.println("curl version: " + curlVersion);
```

## Version Requirements

### Checking Minimum Versions

The system has built-in minimum version requirements:
- **aria2c**: 1.34.0 or higher
- **curl**: 7.50.0 or higher  
- **yt-dlp**: 2023.01.01 or higher

```java
// Check if tools meet minimum version requirements
boolean aria2MeetsMin = dependencyManager.checkMinimumVersion(DependencyManager.ARIA2);
boolean curlMeetsMin = dependencyManager.checkMinimumVersion(DependencyManager.CURL);

System.out.println("aria2c meets minimum: " + aria2MeetsMin);
System.out.println("curl meets minimum: " + curlMeetsMin);
```

### Custom Version Checks

```java
// Check against custom version requirements
boolean aria2HasRpc = dependencyManager.checkMinimumVersion(DependencyManager.ARIA2, "1.34.0");
boolean curlModern = dependencyManager.checkMinimumVersion(DependencyManager.CURL, "7.60.0");

System.out.println("aria2c supports RPC (≥1.34.0): " + aria2HasRpc);
System.out.println("curl has modern features (≥7.60.0): " + curlModern);
```

## Protocol and Feature Validation

### Aria2c Feature Detection

```java
// Get comprehensive aria2c feature support
Map<String, Boolean> aria2Features = dependencyManager.checkAria2Features();

// Check specific features
boolean httpSupport = aria2Features.getOrDefault("http", false);
boolean httpsSupport = aria2Features.getOrDefault("https", false);
boolean torrentSupport = aria2Features.getOrDefault("bittorrent", false);
boolean rpcSupport = aria2Features.getOrDefault("rpc", false);
boolean websocketSupport = aria2Features.getOrDefault("websocket", false);

System.out.println("aria2c HTTP support: " + httpSupport);
System.out.println("aria2c HTTPS support: " + httpsSupport);
System.out.println("aria2c BitTorrent support: " + torrentSupport);
System.out.println("aria2c RPC support: " + rpcSupport);
System.out.println("aria2c WebSocket support: " + websocketSupport);
```

### Curl Feature Detection

```java
// Get comprehensive curl feature support
Map<String, Boolean> curlFeatures = dependencyManager.checkCurlFeatures();

// Check specific features
boolean httpSupport = curlFeatures.getOrDefault("http_supported", false);
boolean httpsSupport = curlFeatures.getOrDefault("https_supported", false);
boolean ftpSupport = curlFeatures.getOrDefault("ftp_supported", false);
boolean sslSupport = curlFeatures.getOrDefault("ssl_supported", false);
boolean proxySupport = curlFeatures.getOrDefault("proxy_supported", false);

System.out.println("curl HTTP support: " + httpSupport);
System.out.println("curl HTTPS support: " + httpsSupport);
System.out.println("curl FTP support: " + ftpSupport);
System.out.println("curl SSL support: " + sslSupport);
System.out.println("curl proxy support: " + proxySupport);
```

### Checking Requirements

```java
// Check if aria2c supports required protocols and features
List<String> requiredProtocols = Arrays.asList("http", "https", "bittorrent");
List<String> requiredFeatures = Arrays.asList("rpc");

boolean aria2Meets = dependencyManager.checkAria2Requirements(requiredProtocols, requiredFeatures);
System.out.println("aria2c meets requirements: " + aria2Meets);

// Check if curl supports required protocols
List<String> curlProtocols = Arrays.asList("http", "https", "ftp");
List<String> curlFeatures = Arrays.asList("ssl", "proxy");

boolean curlMeets = dependencyManager.checkCurlRequirements(curlProtocols, curlFeatures);
System.out.println("curl meets requirements: " + curlMeets);
```

## High-Level Validation with DependencyValidator

### Core Requirements Validation

```java
// Validate core requirements for basic functionality
DependencyValidator.ValidationResult coreResult = validator.validateCoreRequirements();

if (coreResult.isValid()) {
    System.out.println("✓ Core requirements met");
} else {
    System.out.println("✗ Core requirements not met");
    for (String error : coreResult.getErrors()) {
        System.out.println("  Error: " + error);
    }
}

for (String warning : coreResult.getWarnings()) {
    System.out.println("  Warning: " + warning);
}
```

### Specific Use Case Validation

```java
// Validate BitTorrent support
DependencyValidator.ValidationResult torrentResult = validator.validateBitTorrentSupport();
System.out.println("BitTorrent support: " + (torrentResult.isValid() ? "✓" : "✗"));

// Validate secure downloads (HTTPS/SSL)
DependencyValidator.ValidationResult secureResult = validator.validateSecureDownloads();
System.out.println("Secure downloads: " + (secureResult.isValid() ? "✓" : "⚠"));

// Validate video downloading (yt-dlp)
DependencyValidator.ValidationResult videoResult = validator.validateVideoDownloading();
System.out.println("Video downloads: " + (videoResult.isValid() ? "✓" : "⚠"));

// Validate website scraping (httrack)
DependencyValidator.ValidationResult scrapingResult = validator.validateWebsiteScraping();
System.out.println("Website scraping: " + (scrapingResult.isValid() ? "✓" : "⚠"));
```

### Protocol-Specific Validation

```java
// Validate specific protocols for aria2c
DependencyValidator.ValidationResult aria2ProtocolResult = 
    validator.validateAria2Protocols("http", "https", "bittorrent");

// Validate specific protocols for curl
DependencyValidator.ValidationResult curlProtocolResult = 
    validator.validateCurlProtocols("http", "https", "ftp");

System.out.println("aria2c protocols: " + (aria2ProtocolResult.isValid() ? "✓" : "✗"));
System.out.println("curl protocols: " + (curlProtocolResult.isValid() ? "✓" : "✗"));
```

### Comprehensive Validation

```java
// Perform comprehensive validation of all dependencies
DependencyValidator.ValidationResult allResult = validator.validateAll();

// Get formatted summary
String summary = DependencyValidator.getSummary(allResult);
System.out.println(summary);

// Check if minimum requirements are met
boolean meetsMinimum = validator.meetsMinimumRequirements();
System.out.println("Meets minimum requirements: " + meetsMinimum);
```

## Dependency Reports

### Generating Reports

```java
// Generate comprehensive dependency report
Map<String, Object> report = dependencyManager.getDependencyReport();

// Check overall status
boolean allRequirementsMet = (Boolean) report.get("all_requirements_met");
System.out.println("All requirements met: " + allRequirementsMet);

// Get tool status
@SuppressWarnings("unchecked")
Map<String, Map<String, Object>> toolStatus = 
    (Map<String, Map<String, Object>>) report.get("tools");

for (Map.Entry<String, Map<String, Object>> entry : toolStatus.entrySet()) {
    String toolId = entry.getKey();
    Map<String, Object> status = entry.getValue();
    
    System.out.printf("%s: available=%s, version=%s%n",
        toolId,
        status.get("available"),
        status.get("version")
    );
}

// Get version requirements status
@SuppressWarnings("unchecked")
Map<String, Object> versionReqs = (Map<String, Object>) report.get("version_requirements");

for (Map.Entry<String, Object> entry : versionReqs.entrySet()) {
    String toolId = entry.getKey();
    @SuppressWarnings("unchecked")
    Map<String, Object> versionInfo = (Map<String, Object>) entry.getValue();
    
    System.out.printf("%s: required=%s, current=%s, meets=%s%n",
        toolId,
        versionInfo.get("required"),
        versionInfo.get("current"),
        versionInfo.get("meets_requirement")
    );
}
```

## Error Handling

The validation system is designed to handle errors gracefully:

```java
try {
    // All validation methods handle errors gracefully
    boolean available = dependencyManager.isToolAvailable("non-existent-tool");
    String version = dependencyManager.getToolVersion("invalid-tool");
    boolean meetsVersion = dependencyManager.checkMinimumVersion("invalid-tool", "1.0.0");
    
    // These will return false/null rather than throwing exceptions
    System.out.println("Available: " + available);  // false
    System.out.println("Version: " + version);      // null
    System.out.println("Meets version: " + meetsVersion); // false
    
} catch (Exception e) {
    // Validation methods should not throw exceptions under normal circumstances
    System.err.println("Unexpected error: " + e.getMessage());
}
```

## Best Practices

### 1. Check Core Requirements First

```java
// Always check core requirements before proceeding
if (!validator.meetsMinimumRequirements()) {
    System.err.println("System does not meet minimum requirements for download manager");
    DependencyValidator.ValidationResult result = validator.validateCoreRequirements();
    for (String error : result.getErrors()) {
        System.err.println("Error: " + error);
    }
    return;
}
```

### 2. Validate Use Case Requirements

```java
// Before enabling BitTorrent downloads
if (validator.validateBitTorrentSupport().isValid()) {
    // Enable BitTorrent functionality
    enableBitTorrentDownloads();
} else {
    System.out.println("BitTorrent downloads not available");
}

// Before enabling video downloads
if (validator.validateVideoDownloading().isValid()) {
    // Enable video download functionality
    enableVideoDownloads();
} else {
    System.out.println("Video downloads not available");
}
```

### 3. Provide User Feedback

```java
// Generate user-friendly validation summary
DependencyValidator.ValidationResult result = validator.validateAll();
String summary = DependencyValidator.getSummary(result);

// Display to user or log
if (result.isValid()) {
    System.out.println("✓ All dependencies are properly configured");
} else {
    System.out.println("⚠ Some dependencies have issues:");
    System.out.println(summary);
}
```

### 4. Cleanup Resources

```java
// Always cleanup when done
try {
    // Perform validation work
    DependencyValidator.ValidationResult result = validator.validateAll();
    // Process results...
    
} finally {
    // Cleanup dependency manager resources
    dependencyManager.shutdown();
}
```

## Supported Features by Tool

### aria2c
- **Protocols**: HTTP, HTTPS, FTP, BitTorrent, Metalink
- **Features**: RPC, WebSocket, checksum verification, proxy support, resume downloads

### curl
- **Protocols**: HTTP, HTTPS, FTP, FTPS, SFTP, and many others
- **Features**: SSL/TLS, proxy support, compression, IPv6, resume downloads

### yt-dlp
- **Features**: Video downloading from hundreds of sites, format selection, metadata extraction

### httrack
- **Features**: Website mirroring, recursive downloading, link conversion

### proxychains
- **Features**: SOCKS4/5 proxy support, Tor integration

## Troubleshooting

### Common Issues

1. **Tool not found**: Ensure the tool is installed and in the system PATH, or configure custom paths in GlobalSettings.

2. **Version too old**: Update the tool to meet minimum requirements or accept limited functionality.

3. **Missing features**: Some Linux distributions compile tools without certain features. Consider using alternative packages or compiling from source.

4. **Permission issues**: Ensure the tools are executable and accessible by the application user.

### Debugging

```java
// Enable debug logging to see detailed validation information
Logger logger = Logger.getLogger(DependencyManager.class.getName());
logger.setLevel(Level.FINE);

// Generate detailed dependency report for troubleshooting
Map<String, Object> report = dependencyManager.getDependencyReport();
System.out.println("Debug report: " + report);
```
