# Proxy Rotation System Integration Guide

This guide shows how to integrate the automatic proxy rotation mechanism into the existing Open Download Manager.

## Quick Implementation

### Step 1: Initialize Proxy Manager

```java
// In your main application or DownloadManager initialization
ProxyRotationManager proxyManager = new ProxyRotationManager();

// Load proxies from file
try {
    proxyManager.loadProxiesFromFile(Paths.get("config/proxy-list.txt"));
} catch (IOException e) {
    logger.warning("Could not load proxy list: " + e.getMessage());
    // Fall back to default proxies or disable proxy rotation
}

// Or add proxies programmatically
proxyManager.addProxy(new Proxy("proxy1.example.com", 8080, Proxy.Type.HTTP));
proxyManager.addProxy(Proxy.fromUrl("http://user:pass@proxy2.example.com:8080"));
```

### Step 2: Wrap Existing Handlers

```java
// In your DownloadHandlerFactory or similar initialization code
public class EnhancedDownloadHandlerFactory {
    
    private final DownloadHandlerFactory originalFactory;
    private final ProxyRotationManager proxyManager;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;
    
    public DownloadHandler createHandler(Download.Type type) {
        // Get the original handler
        DownloadHandler originalHandler = originalFactory.getHandler(type);
        
        if (originalHandler == null) {
            return null;
        }
        
        // Wrap with proxy rotation if enabled
        if (proxyManager != null && !proxyManager.isEmpty()) {
            ProxyRetrySettings retrySettings = ProxyRetrySettings.builder()
                .maxRetries(5)  // 5 retries as requested
                .enableProxyRotation(true)
                .build();
                
            return new RetryableDownloadHandler(
                originalHandler,
                proxyManager,
                retrySettings,
                scheduler,
                executor
            );
        }
        
        return originalHandler;
    }
}
```

### Step 3: Simple DownloadManager Integration

```java
public class DownloadManagerImpl implements DownloadManager {
    
    private final ProxyRotationManager proxyManager;
    private final Map<Download.Type, DownloadHandler> handlers;
    
    @Override
    public CompletableFuture<String> startDownload(Download download) {
        DownloadHandler handler = handlers.get(download.getType());
        
        if (handler instanceof RetryableDownloadHandler) {
            // Handler already has proxy rotation
            return handler.startDownload(download);
        } else {
            // Wrap handler with proxy rotation for this download
            if (shouldUseProxyRotation(download)) {
                RetryableDownloadHandler retryableHandler = wrapWithProxyRotation(handler);
                return retryableHandler.startDownload(download);
            } else {
                return handler.startDownload(download);
            }
        }
    }
    
    private boolean shouldUseProxyRotation(Download download) {
        // Check if download settings enable proxy rotation
        if (download.getSettings() instanceof ProxyAwareDownloadSettings) {
            ProxyAwareDownloadSettings settings = (ProxyAwareDownloadSettings) download.getSettings();
            return settings.isEnableProxyRotationForThisDownload();
        }
        
        // Check global settings or default behavior
        return proxyManager != null && !proxyManager.isEmpty();
    }
}
```

## Minimal Working Example

Here's a complete minimal example:

```java
public class ProxyRotationIntegration {
    
    public static void main(String[] args) throws Exception {
        // 1. Set up proxy manager
        ProxyRotationManager proxyManager = new ProxyRotationManager();
        proxyManager.addProxy(new Proxy("proxy1.example.com", 8080, Proxy.Type.HTTP));
        proxyManager.addProxy(new Proxy("proxy2.example.com", 8080, Proxy.Type.HTTP));
        
        // 2. Set up executors
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        
        // 3. Get existing handler (from your current system)
        DownloadHandler originalHandler = getExistingHandler(); // Your existing code
        
        // 4. Wrap with proxy rotation
        ProxyRetrySettings retrySettings = new ProxyRetrySettings(); // Uses defaults
        RetryableDownloadHandler retryableHandler = new RetryableDownloadHandler(
            originalHandler,
            proxyManager,
            retrySettings,
            scheduler,
            executor
        );
        
        // 5. Use the retryable handler
        Download download = new Download(new URI("https://example.com/file.zip"));
        download.setDestination(Paths.get("Downloads", "file.zip"));
        
        // Add listener to see retry attempts
        retryableHandler.addDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadError(Download download, String errorMessage) {
                System.out.println("Download error (will retry with different proxy): " + errorMessage);
            }
            
            @Override
            public void onDownloadStart(Download download) {
                System.out.println("Download started: " + download.getName());
            }
            
            @Override
            public void onDownloadComplete(Download download) {
                System.out.println("Download completed: " + download.getName());
            }
            
            // ... implement other methods
        });
        
        // Start download with automatic proxy rotation
        CompletableFuture<String> future = retryableHandler.startDownload(download);
        
        future.whenComplete((gid, throwable) -> {
            if (throwable != null) {
                System.err.println("Download failed after all retries: " + throwable.getMessage());
            } else {
                System.out.println("Download started successfully with GID: " + gid);
            }
        });
        
        // Wait for completion (in real app, you wouldn't block like this)
        Thread.sleep(10000);
        
        // Cleanup
        scheduler.shutdown();
        executor.shutdown();
    }
    
    private static DownloadHandler getExistingHandler() {
        // Return your existing aria2, curl, or other handler
        // This is just a placeholder
        return null;
    }
}
```

## Configuration

### Proxy List File Format

Create a `proxy-list.txt` file:

```
# HTTP Proxies
http://proxy1.example.com:8080
http://user:pass@proxy2.example.com:8080

# SOCKS Proxies
socks5://proxy3.example.com:1080

# Comments and empty lines are ignored
```

### Custom Retry Settings

```java
ProxyRetrySettings customSettings = ProxyRetrySettings.builder()
    .maxRetries(3)                              // Only 3 retries
    .initialRetryDelay(Duration.ofSeconds(1))   // Start with 1 second delay
    .backoffMultiplier(2.0)                     // Double delay each time
    .enableProxyRotation(true)                  // Enable rotation
    .rotateOnFirstError(true)                   // Rotate immediately on error
    .addRetryStatusCode(429)                    // Retry on rate limiting
    .addRetryErrorKeyword("blocked")            // Retry on blocking
    .build();
```

## Error Detection

The system automatically retries on these conditions:

### HTTP Status Codes
- 403 (Forbidden)
- 429 (Too Many Requests) 
- 503 (Service Unavailable)
- 502 (Bad Gateway)
- 504 (Gateway Timeout)

### Error Keywords
- "too many requests"
- "rate limit"
- "blocked"
- "forbidden"
- "connection timeout"
- "proxy error"

## Monitoring

```java
// Get proxy statistics
Map<String, Object> stats = proxyManager.getStatistics();
System.out.println("Total proxies: " + stats.get("totalProxies"));
System.out.println("Healthy proxies: " + stats.get("healthyProxies"));
System.out.println("Average response time: " + stats.get("averageResponseTime"));

// Monitor individual proxy health
Proxy proxy = proxyManager.getRandomProxy();
System.out.println("Proxy health score: " + proxy.getHealthScore());
System.out.println("Success rate: " + proxy.getSuccessCount() + "/" + 
                   (proxy.getSuccessCount() + proxy.getFailureCount()));
```

## Best Practices

1. **Start Simple**: Begin with a few reliable proxies
2. **Monitor Health**: Check proxy statistics regularly
3. **Reasonable Retries**: Don't set too many retries (3-5 is good)
4. **Error Logging**: Log retry attempts for debugging
5. **Fallback**: Always have a fallback plan when all proxies fail

## Thread Safety

All classes are thread-safe and can be used from multiple threads concurrently. The proxy manager handles concurrent access safely.

## Performance Impact

- Minimal overhead when no errors occur
- Retry delays use exponential backoff to avoid overwhelming servers
- Proxy selection is fast (O(1) average case)
- Memory usage scales with number of proxies (typically <1MB for 1000 proxies)