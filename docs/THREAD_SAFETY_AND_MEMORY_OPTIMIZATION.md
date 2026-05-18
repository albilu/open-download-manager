# Thread Safety and Memory Optimization

This document describes the thread safety and memory optimization features implemented in the Open Download Manager core module.

## Overview

The download manager has been enhanced with comprehensive thread safety mechanisms and memory optimization features to ensure stable operation under high load and efficient resource usage for large-scale download operations.

## Key Features

### 1. Thread Safety Improvements

#### Concurrent Data Structures
- **PaginatedDownloadRepository**: Thread-safe repository with read-write locks for efficient concurrent access
- **ConcurrentHashMap**: Used for GID mappings and status indices
- **CopyOnWriteArraySet**: Used for listener collections to ensure thread-safe iteration
- **AtomicInteger/AtomicBoolean**: Used for counters and flags to prevent race conditions

#### Synchronized Operations
- **Download Status Updates**: All download status changes are properly synchronized
- **Repository Operations**: Add/remove/update operations use appropriate locking mechanisms
- **Cache Management**: Query result caching with thread-safe expiration handling

#### Lock Strategies
- **ReadWriteLock**: Used in repository for optimal read/write performance
- **Object-level locks**: Each Download instance has its own synchronization lock
- **Atomic operations**: Used for counters and flags to minimize lock contention

### 2. Memory Optimization

#### Automatic Cleanup
```java
// Configure automatic cleanup
downloadManager.setAutomaticCleanup(true, Duration.ofHours(6));
downloadManager.setMaxDownloadsInMemory(1000);

// Cleanup settings in GlobalSettings
settings.setAutomaticCleanupEnabled(true);
settings.setCleanupIntervalHours(6);
settings.setCompletedDownloadRetentionDays(30);
settings.setErrorDownloadRetentionDays(7);
settings.setMaxDownloadsInMemory(1000);
settings.setMaxCompletedDownloadsToKeep(500);
```

#### Pagination Support
```java
// Paginated access to downloads
List<Download> page1 = downloadManager.getDownloads(0, 50);  // First 50 downloads
List<Download> page2 = downloadManager.getDownloads(50, 50); // Next 50 downloads

// Paginated access by status
List<Download> queuedPage = downloadManager.getDownloadsByStatus(
    Download.Status.QUEUED, 0, 25);

// Paginated time-range queries
List<Download> recentDownloads = downloadManager.getDownloadsByTimeRange(
    startTime, endTime, 0, 100);
```

#### Query Result Caching
- **TTL-based caching**: Query results cached for 30 seconds by default
- **LRU eviction**: Cache size limited with least-recently-used eviction
- **Automatic invalidation**: Cache cleared when data changes

### 3. Shutdown Coordination

#### Phased Shutdown Process
The `ShutdownCoordinator` manages shutdown in phases:

1. **PREPARE** (Priority 1000): Stop accepting new work
2. **DOWNLOADS** (Priority 900): Pause/complete active downloads
3. **SERVICES** (Priority 800): Shutdown download services
4. **EXECUTORS** (Priority 700): Shutdown thread pools
5. **RESOURCES** (Priority 600): Release system resources
6. **PERSISTENCE** (Priority 500): Save state and cleanup files
7. **CLEANUP** (Priority 400): Final cleanup operations

```java
// Register custom shutdown hooks
shutdownCoordinator.registerShutdownHook(
    ShutdownCoordinator.ShutdownPhase.SERVICES,
    "my-service",
    () -> myService.shutdown(),
    30, // timeout in seconds
    true // essential
);
```

## API Reference

### DownloadManager Interface Additions

#### Pagination Methods
```java
// Get downloads with pagination
List<Download> getDownloads(int offset, int limit);
int getDownloadCount();

// Get downloads by status with pagination
List<Download> getDownloadsByStatus(Download.Status status, int offset, int limit);
int getDownloadCountByStatus(Download.Status status);

// Time-range queries with pagination
List<Download> getDownloadsByTimeRange(Instant from, Instant to, int offset, int limit);
```

#### Cleanup Methods
```java
// Prune completed downloads
CompletableFuture<Integer> pruneCompletedDownloads(Duration olderThan);
CompletableFuture<Integer> pruneCompletedDownloads(int keepCount);

// Prune error downloads
CompletableFuture<Integer> pruneErrorDownloads(Duration olderThan);

// Full cleanup operation
CompletableFuture<Void> performCleanup();
```

#### Configuration Methods
```java
// Configure automatic cleanup
void setAutomaticCleanup(boolean enabled, Duration cleanupInterval);
void setMaxDownloadsInMemory(int maxDownloads);

// Get memory usage statistics
Map<String, Object> getMemoryUsageStats();
```

### GlobalSettings Configuration

#### Memory Management Settings
```java
// Maximum downloads to keep in memory (0 = unlimited)
settings.setMaxDownloadsInMemory(1000);

// Maximum completed downloads to keep
settings.setMaxCompletedDownloadsToKeep(500);

// Cleanup interval in hours
settings.setCleanupIntervalHours(24);

// Retention periods in days
settings.setCompletedDownloadRetentionDays(30);
settings.setErrorDownloadRetentionDays(7);

// Enable/disable automatic cleanup
settings.setAutomaticCleanupEnabled(true);

// Pagination settings
settings.setPaginationDefaultSize(50);
settings.setEnableLazyLoading(true);
```

### PaginatedDownloadRepository

#### Core Operations
```java
// Thread-safe repository operations
repository.addDownload(download);
repository.removeDownload(downloadId);
repository.updateDownloadStatus(download, newStatus);

// Paginated queries
DownloadPage getAllDownloads(int pageNumber, int pageSize);
DownloadPage getDownloadsByStatus(Download.Status status, int pageNumber, int pageSize);
DownloadPage getDownloadsByTimeRange(Instant from, Instant to, int pageNumber, int pageSize);

// Statistics
int getTotalCount();
int getCountByStatus(Download.Status status);
Map<Download.Status, Integer> getStatusBreakdown();
```

#### DownloadPage Result
```java
public class DownloadPage {
    List<Download> getDownloads();    // Downloads in this page
    int getTotalCount();              // Total downloads across all pages
    int getPageNumber();              // Current page number (0-based)
    int getPageSize();                // Page size
    boolean hasNext();                // True if more pages available
    boolean hasPrevious();            // True if previous pages exist
    int getTotalPages();              // Total number of pages
}
```

### DownloadCleanupManager

#### Cleanup Operations
```java
// Age-based cleanup
int pruneCompletedDownloadsByAge(Duration olderThan);
int pruneErrorDownloadsByAge(Duration olderThan);

// Count-based cleanup
int pruneCompletedDownloadsByCount(int keepCount);

// Full cleanup based on settings
CompletableFuture<Void> performFullCleanup();

// Memory statistics
Map<String, Object> getMemoryUsageStats();
```

## Usage Examples

### Basic Thread-Safe Operations

```java
// Create download manager with optimized settings
DownloadManager downloadManager = new DownloadManagerImpl();
GlobalSettings settings = downloadManager.getGlobalSettings();

// Configure memory optimization
settings.setMaxDownloadsInMemory(1000);
settings.setAutomaticCleanupEnabled(true);
settings.setCleanupIntervalHours(6);

// Initialize
downloadManager.initialize().get();

// Thread-safe concurrent operations
CompletableFuture.runAsync(() -> {
    // Thread 1: Create downloads
    for (int i = 0; i < 100; i++) {
        URI uri = URI.create("https://example.com/file" + i + ".zip");
        Download download = downloadManager.createDownload(uri, null);
        downloadManager.queueDownload(download);
    }
});

CompletableFuture.runAsync(() -> {
    // Thread 2: Monitor status
    while (true) {
        int total = downloadManager.getDownloadCount();
        int downloading = downloadManager.getDownloadCountByStatus(Download.Status.DOWNLOADING);
        System.out.println("Total: " + total + ", Downloading: " + downloading);
        Thread.sleep(1000);
    }
});
```

### Pagination for Large Datasets

```java
// Efficient pagination through large download lists
int pageSize = 50;
int currentPage = 0;
boolean hasMore = true;

while (hasMore) {
    List<Download> downloads = downloadManager.getDownloads(
        currentPage * pageSize, pageSize);
    
    if (downloads.isEmpty()) {
        hasMore = false;
    } else {
        // Process downloads in this page
        processDownloads(downloads);
        currentPage++;
    }
}
```

### Memory Management

```java
// Monitor memory usage
Map<String, Object> stats = downloadManager.getMemoryUsageStats();
System.out.println("Total downloads: " + stats.get("totalDownloads"));
System.out.println("Memory usage: " + stats.get("estimatedMemoryUsageBytes"));

// Manual cleanup operations
downloadManager.pruneCompletedDownloads(Duration.ofDays(30))
    .thenCompose(count -> {
        System.out.println("Removed " + count + " old completed downloads");
        return downloadManager.pruneErrorDownloads(Duration.ofDays(7));
    })
    .thenAccept(count -> {
        System.out.println("Removed " + count + " old error downloads");
    });

// Full cleanup
downloadManager.performCleanup().thenRun(() -> {
    System.out.println("Full cleanup completed");
});
```

### Graceful Shutdown

```java
// Proper shutdown sequence
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    try {
        downloadManager.shutdown().get(60, TimeUnit.SECONDS);
        System.out.println("Download manager shut down gracefully");
    } catch (Exception e) {
        System.err.println("Shutdown failed: " + e.getMessage());
    }
}));
```

## Performance Considerations

### Thread Safety
- **Lock granularity**: Uses appropriate lock granularity to minimize contention
- **Read-write locks**: Optimizes for read-heavy workloads common in download monitoring
- **Atomic operations**: Uses lock-free atomic operations where possible
- **Copy-on-write**: Uses copy-on-write collections for rarely-modified data

### Memory Optimization
- **Lazy loading**: Loads download data on-demand when enabled
- **Query caching**: Caches frequently accessed query results
- **Automatic cleanup**: Prevents memory leaks from accumulating old downloads
- **Pagination**: Reduces memory footprint for large datasets

### Shutdown Performance
- **Parallel shutdown**: Shuts down components in parallel within each phase
- **Timeout handling**: Prevents hanging during shutdown with configurable timeouts
- **Essential vs non-essential**: Distinguishes between critical and optional shutdown tasks

## Configuration Best Practices

### Memory Settings
```java
// For high-volume environments
settings.setMaxDownloadsInMemory(5000);
settings.setMaxCompletedDownloadsToKeep(1000);
settings.setCleanupIntervalHours(2);
settings.setCompletedDownloadRetentionDays(7);

// For resource-constrained environments
settings.setMaxDownloadsInMemory(500);
settings.setMaxCompletedDownloadsToKeep(100);
settings.setCleanupIntervalHours(12);
settings.setCompletedDownloadRetentionDays(3);
```

### Pagination Settings
```java
// For UI applications
settings.setPaginationDefaultSize(25);

// For batch processing
settings.setPaginationDefaultSize(100);

// For memory-constrained environments
settings.setPaginationDefaultSize(10);
settings.setEnableLazyLoading(true);
```

## Monitoring and Debugging

### Memory Usage Monitoring
```java
// Get detailed memory statistics
Map<String, Object> stats = downloadManager.getMemoryUsageStats();

// Repository cache statistics
Map<String, Object> cacheStats = stats.get("repositoryCacheStats");
System.out.println("Cache size: " + cacheStats.get("cacheSize"));
System.out.println("Cache hit ratio: " + cacheStats.get("hitRatio"));

// Status breakdown
Map<Download.Status, Integer> breakdown = 
    (Map<Download.Status, Integer>) stats.get("statusBreakdown");
breakdown.forEach((status, count) -> 
    System.out.println(status + ": " + count));
```

### Performance Metrics
```java
// Track cleanup performance
long startTime = System.currentTimeMillis();
downloadManager.performCleanup().thenRun(() -> {
    long duration = System.currentTimeMillis() - startTime;
    System.out.println("Cleanup took: " + duration + "ms");
});

// Monitor pagination performance
long queryStart = System.currentTimeMillis();
List<Download> results = downloadManager.getDownloads(0, 100);
long queryDuration = System.currentTimeMillis() - queryStart;
System.out.println("Query took: " + queryDuration + "ms for " + results.size() + " results");
```

## Migration Guide

### From Previous Versions

1. **Update GlobalSettings configuration**:
   ```java
   // Add new memory management settings
   settings.setMaxDownloadsInMemory(1000);
   settings.setAutomaticCleanupEnabled(true);
   ```

2. **Replace direct download access with pagination**:
   ```java
   // Old way
   List<Download> allDownloads = downloadManager.getAllDownloads();
   
   // New way (for large datasets)
   List<Download> firstPage = downloadManager.getDownloads(0, 50);
   ```

3. **Add proper shutdown handling**:
   ```java
   // Ensure graceful shutdown
   downloadManager.shutdown().get(60, TimeUnit.SECONDS);
   ```

4. **Enable automatic cleanup**:
   ```java
   downloadManager.setAutomaticCleanup(true, Duration.ofHours(6));
   ```

### Configuration Migration
```java
// Migrate existing settings to include new memory management options
GlobalSettings existingSettings = loadExistingSettings();

// Add default values for new settings
if (existingSettings.getMaxDownloadsInMemory() == 0) {
    existingSettings.setMaxDownloadsInMemory(1000);
}
if (!existingSettings.isAutomaticCleanupEnabled()) {
    existingSettings.setAutomaticCleanupEnabled(true);
    existingSettings.setCleanupIntervalHours(24);
}

downloadManager.setGlobalSettings(existingSettings);
```
