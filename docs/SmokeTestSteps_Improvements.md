# SmokeTestSteps.java Improvements

## Overview

The `SmokeTestSteps.java` file has been refactored to reduce unnecessary mocking and use real implementations where possible, following the principle that tests should run as close to real use cases as possible since they execute in a Docker container with all dependencies available.

## Key Improvements Made

### 1. Reduced Mocking Dependencies

**Before:**
- Heavy use of `@Mock` and `when().thenReturn()` patterns
- Mocked core components like `DownloadManager`, `GlobalSettings`, `ClipboardService`
- Artificial test behavior that didn't reflect real usage

**After:**
- Uses real `ComponentFactory` to create actual instances
- Real `GlobalSettings` with test-appropriate configurations
- Real `UrlDetector` static methods (no mocking needed)
- Real `ToolManagerFactory` and its managers

### 2. Proper Resource Management

**Improvements:**
- Added proper cleanup in `@After` method
- Calls `ComponentFactory.resetInstance()` to prevent singleton pollution
- Graceful shutdown of created components
- Separated state reset into dedicated `resetState()` method

### 3. Real Component Integration

**Component Factory:**
```java
// Before: Mock-based
componentFactory = mock(ComponentFactory.class);

// After: Real instance with test settings
globalSettings = createTestGlobalSettings();
componentFactory = ComponentFactory.create(globalSettings);
downloadManager = componentFactory.createDownloadManager();
```

**Global Settings:**
```java
// Before: Mocked returns
when(globalSettings.getMaxConcurrentDownloads()).thenReturn(3);

// After: Real configuration
globalSettings.setMaxConcurrentDownloads(maxDownloads);
globalSettings.setGlobalSpeedLimit(speedLimit);
```

### 4. Improved Test Data Setup

**Test Settings Helper:**
```java
private GlobalSettings createTestGlobalSettings() {
    GlobalSettings settings = new GlobalSettings();
    Path testDownloadDir = Paths.get(System.getProperty("java.io.tmpdir"), "odm-test-downloads");
    settings.setDefaultDownloadDirectory(testDownloadDir);
    settings.setMaxConcurrentDownloads(3);
    settings.setGlobalSpeedLimit(0); // No limit for tests
    settings.setSaveDownloadHistory(false); // Don't save history in tests
    return settings;
}
```

### 5. Simplified Error Handling for Smoke Tests

**Philosophy:**
- Smoke tests verify basic functionality, not detailed error scenarios
- Removed complex error handler mocking
- Use simple assertions for basic error handling capability

### 6. Real URL Detection

**Before:**
```java
// Mocked URL detection with artificial results
```

**After:**
```java
// Real UrlDetector usage
detectedUrls = UrlDetector.extractUrls(text);
```

### 7. Authentic Download Creation

**Before:**
```java
createdDownload = mock(Download.class);
when(createdDownload.getUri()).thenReturn(URI.create(testUrl));
when(downloadManager.createDownload(...)).thenReturn(createdDownload);
```

**After:**
```java
URI uri = URI.create(testUrl);
createdDownload = downloadManager.createDownload(uri, null);
```

## Benefits of These Changes

### 1. **More Realistic Testing**
- Tests actual component interactions
- Catches real integration issues
- Better reflects production behavior

### 2. **Easier Maintenance**
- Less mock setup and configuration
- Fewer brittle test dependencies
- Clearer test intentions

### 3. **Better Docker Utilization**
- Leverages available dependencies in container
- Tests real file system interactions (with temp directories)
- Validates actual component initialization

### 4. **Improved Debugging**
- Real stack traces instead of mock interactions
- Actual component state can be inspected
- More meaningful test failures

## Still Uses Mocking Where Appropriate

### Clipboard Operations
- Real clipboard access requires X11/GUI environment
- Simulates clipboard content processing without system clipboard
- Tests URL detection logic with real `UrlDetector`

### Tool Health Checks
- External tool availability varies by environment
- Status report structure is tested, not tool installation
- Focuses on API contracts rather than tool presence

## Test Execution Considerations

### Environment Requirements
- Docker container provides consistent environment
- Temp directories used for test isolation
- No persistent state between test runs

### Performance
- Real component creation has minimal overhead
- Proper cleanup prevents resource leaks
- Singleton reset ensures test isolation

## Migration Path for Other Test Classes

This refactoring approach can be applied to other test classes:

1. **Identify over-mocked components** that have real implementations
2. **Create test-specific configurations** instead of mocked returns
3. **Use real factories and builders** where available
4. **Reserve mocking for external dependencies** or complex setup scenarios
5. **Ensure proper cleanup** of real resources

## Conclusion

The refactored `SmokeTestSteps.java` provides more reliable smoke testing by using real component implementations while maintaining test isolation and performance. This approach better validates the actual system behavior and catches integration issues that mocked tests might miss.