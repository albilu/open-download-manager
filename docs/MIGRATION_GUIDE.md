# Migration Guide: From DependencyManager to ToolManagerFactory

This guide helps you migrate from the deprecated monolithic `DependencyManager` to the new modular `ToolManagerFactory` architecture.

## Overview

The Open Download Manager has migrated from a monolithic dependency management approach to a modular tool manager architecture for better performance, maintainability, and extensibility.

### What Changed

- **Old System**: Single `DependencyManager` class handling all tools
- **New System**: `ToolManagerFactory` + individual `ToolManager` implementations
- **Benefits**: Better separation of concerns, easier testing, improved performance, cleaner APIs

## Migration Steps

### 1. Replace DependencyManager Usage

**Before (Deprecated):**
```java
import org.manager.tools.DependencyManager;

DependencyManager deps = ApplicationContext.getDependencyManager();
boolean aria2Available = deps.isToolAvailable(DependencyManager.ARIA2);
String aria2Path = deps.getToolPath(DependencyManager.ARIA2);
String aria2Version = deps.getToolVersion(DependencyManager.ARIA2);
```

**After (Recommended):**
```java
import org.manager.tools.ToolManagerFactory;
import org.aria2.Aria2ToolManager;

ToolManagerFactory factory = ApplicationContext.getToolManagerFactory();
Aria2ToolManager aria2Manager = factory.getAria2Manager();
boolean aria2Available = aria2Manager.isAvailable();
String aria2Path = aria2Manager.getToolPath();
String aria2Version = aria2Manager.getVersion();
```

### 2. Update Tool Availability Checks

**Before:**
```java
if (ApplicationContext.isToolAvailable(DependencyManager.ARIA2)) {
    // Use aria2
}
```

**After:**
```java
if (ApplicationContext.isToolAvailable("aria2")) {
    // Use aria2 - ApplicationContext now handles both systems
}
```

### 3. Update Dependency Validation

**Before:**
```java
import org.manager.tools.DependencyValidator;
import org.manager.tools.DependencyManager;

DependencyManager deps = ApplicationContext.getDependencyManager();
DependencyValidator validator = new DependencyValidator(deps);
ValidationResult result = validator.validateCoreRequirements();
```

**After:**
```java
import org.manager.tools.DependencyValidator;
import org.manager.tools.ToolManagerFactory;

ToolManagerFactory factory = ApplicationContext.getToolManagerFactory();
DependencyValidator validator = new DependencyValidator(factory);
ValidationResult result = validator.validateCoreRequirements();
```

### 4. Feature Detection

**Before:**
```java
DependencyManager deps = ApplicationContext.getDependencyManager();
Map<String, Boolean> aria2Features = deps.checkAria2Features();
boolean supportsRpc = aria2Features.getOrDefault("rpc", false);
```

**After:**
```java
ToolManagerFactory factory = ApplicationContext.getToolManagerFactory();
Aria2ToolManager aria2Manager = factory.getAria2Manager();
Map<String, Boolean> features = aria2Manager.getSupportedFeatures();
boolean supportsRpc = aria2Manager.checkFeatureSupport("rpc");
```

### 5. Async Operations

**Before:**
```java
ApplicationContext.checkAllDependenciesAsync(); // Returns void
```

**After:**
```java
CompletableFuture<Map<String, Boolean>> future = ApplicationContext.checkAllDependenciesAsync();
future.thenAccept(results -> {
    for (Map.Entry<String, Boolean> entry : results.entrySet()) {
        System.out.println(entry.getKey() + ": " + entry.getValue());
    }
});
```

## Tool-Specific Managers

### Available Tool Managers

- `Aria2ToolManager` - For aria2c download accelerator
- `CurlToolManager` - For cURL command-line tool
- `YtDlpToolManager` - For yt-dlp video downloader
- `HttrackToolManager` - For HTTrack website mirror
- `ProxychainsToolManager` - For proxychains proxy tool
- `TorToolManager` - For Tor anonymity network

### Getting Specific Managers

```java
ToolManagerFactory factory = ApplicationContext.getToolManagerFactory();

// Get specific managers
Aria2ToolManager aria2 = factory.getAria2Manager();
CurlToolManager curl = factory.getCurlManager();
YtDlpToolManager ytdlp = factory.getYtDlpManager();
HttrackToolManager httrack = factory.getHttrackManager();
ProxychainsToolManager proxychains = factory.getProxychainsManager();
TorToolManager tor = factory.getTorManager();

// Or get by string ID
ToolManager manager = factory.getToolManager("aria2");
```

## Backward Compatibility

The migration preserves backward compatibility:

1. **ApplicationContext** methods still work but delegate to the new system
2. **DependencyManager** is marked as `@Deprecated` but still functional
3. **Existing code** continues to work during the transition period

## New Features

The new architecture provides additional capabilities:

### 1. Comprehensive Status Reports
```java
ToolManagerFactory factory = ApplicationContext.getToolManagerFactory();
Map<String, Map<String, Object>> statusReport = factory.getStatusReport();
```

### 2. Embedded Binary Support
```java
Map<String, Boolean> results = factory.initializeEmbeddedBinaries();
```

### 3. Validation with Tool Managers
```java
factory.validateRequiredTools("aria2", "curl");
```

### 4. Individual Tool Configuration
```java
Aria2ToolManager aria2Manager = factory.getAria2Manager();
Map<String, String> recommendedConfig = aria2Manager.getRecommendedConfig();
```

## Best Practices

### 1. Use Specific Tool Managers
Instead of generic string-based APIs, use specific tool managers for better type safety and IDE support.

### 2. Handle Null Checks
```java
ToolManagerFactory factory = ApplicationContext.getToolManagerFactory();
if (factory != null) {
    Aria2ToolManager aria2Manager = factory.getAria2Manager();
    if (aria2Manager != null && aria2Manager.isAvailable()) {
        // Use aria2
    }
}
```

### 3. Leverage Async Operations
Use the new async capabilities for better performance:
```java
CompletableFuture<Boolean> availabilityCheck = aria2Manager.checkAvailabilityAsync();
availabilityCheck.thenAccept(available -> {
    if (available) {
        // Initialize download
    }
});
```

### 4. Use Validation Results
The new validation system provides detailed information:
```java
ValidationResult result = validator.validateAll();
if (!result.isValid()) {
    for (String error : result.getErrors()) {
        System.err.println("Error: " + error);
    }
    for (String warning : result.getWarnings()) {
        System.out.println("Warning: " + warning);
    }
}
```

## Timeline

- **Current**: Both systems work side-by-side
- **Next Release**: DependencyManager will show deprecation warnings
- **Future Release**: DependencyManager will be removed

## Migration Checklist

- [ ] Replace `DependencyManager` imports with `ToolManagerFactory`
- [ ] Update tool availability checks to use new APIs
- [ ] Replace `DependencyValidator` constructor calls
- [ ] Update feature detection code
- [ ] Test async operations work as expected
- [ ] Update any custom tool detection logic
- [ ] Review and update documentation
- [ ] Run tests to ensure compatibility

## Getting Help

If you encounter issues during migration:

1. Check that `ApplicationContext.getToolManagerFactory()` returns a non-null value
2. Verify tool managers are properly initialized
3. Look at the status report: `factory.getStatusReport()`
4. Check logs for deprecation warnings and migration hints

## Example: Complete Migration

**Before:**
```java
public class DownloadService {
    private final DependencyManager dependencyManager;
    
    public DownloadService() {
        this.dependencyManager = ApplicationContext.getDependencyManager();
    }
    
    public boolean canDownload() {
        return dependencyManager.isToolAvailable(DependencyManager.ARIA2) ||
               dependencyManager.isToolAvailable(DependencyManager.CURL);
    }
    
    public String getPreferredTool() {
        if (dependencyManager.isToolAvailable(DependencyManager.ARIA2)) {
            return dependencyManager.getToolPath(DependencyManager.ARIA2);
        }
        return dependencyManager.getToolPath(DependencyManager.CURL);
    }
}
```

**After:**
```java
public class DownloadService {
    private final ToolManagerFactory toolManagerFactory;
    
    public DownloadService() {
        this.toolManagerFactory = ApplicationContext.getToolManagerFactory();
    }
    
    public boolean canDownload() {
        return toolManagerFactory.isToolAvailable("aria2") ||
               toolManagerFactory.isToolAvailable("curl");
    }
    
    public String getPreferredTool() {
        Aria2ToolManager aria2Manager = toolManagerFactory.getAria2Manager();
        if (aria2Manager != null && aria2Manager.isAvailable()) {
            return aria2Manager.getToolPath();
        }
        
        CurlToolManager curlManager = toolManagerFactory.getCurlManager();
        if (curlManager != null && curlManager.isAvailable()) {
            return curlManager.getToolPath();
        }
        
        return null;
    }
}
```

This migration provides better performance, type safety, and maintainability while preserving all existing functionality.