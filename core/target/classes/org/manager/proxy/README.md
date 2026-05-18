# Proxy Rotation System

This package implements an automatic proxy rotation mechanism that retries downloads (up to 5 times by default) when encountering server restrictions, rate limiting, or IP blocking during downloads.

## Overview

The proxy rotation system consists of several key components:

1. **`Proxy`** - Represents a proxy server with health tracking
2. **`ProxyRotationManager`** - Manages a pool of proxies and handles rotation logic
3. **`ProxyRetrySettings`** - Configuration for retry behavior and error detection
4. **`RetryableDownloadHandler`** - Wraps existing download handlers with retry functionality
5. **`ProxyRotationHandlerFactory`** - Factory for creating retryable handlers
6. **`ProxyAwareDownloadSettings`** - Download settings with proxy rotation configuration

## Quick Start

### 1. Basic Setup

```java
// Create proxy rotation manager
ProxyRotationManager proxyManager = new ProxyRotationManager();

// Add proxies
proxyManager.addProxy(new Proxy("proxy1.example.com", 8080, Proxy.Type.HTTP));
proxyManager.addProxy(Proxy.fromUrl("http://user:pass@proxy2.example.com:8080"));
proxyManager.addProxy(Proxy.fromUrl("socks5://proxy3.example.com:1080"));

// Load proxies from file
proxyManager.loadProxiesFromFile(Paths.get("proxy-list.txt"));

// Create executors
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
ExecutorService executor = Executors.newFixedThreadPool(4);

// Create handler factory with proxy rotation
ProxyRotationHandlerFactory factory = new ProxyRotationHandlerFactory.Builder()
    .baseHandlerFactory(baseHandlerFactory)
    .proxyManager(proxyManager)
    .scheduler(scheduler)
    .executor(executor)
    .build();

// Create download with automatic proxy rotation
URI uri = new URI("https://example.com/file.zip");
Download download = new Download(uri);
download.setDestination(Paths.get("Downloads", "file.zip"));

// Get retryable handler and start download
var handler = factory.createHandler(download.getType());
handler.startDownload(download);
```

### 2. Custom Retry Configuration

```java
// Create custom retry settings
ProxyRetrySettings retrySettings = ProxyRetrySettings.builder()
    .maxRetries(3)                              // 3 retries instead of default 5
    .initialRetryDelay(Duration.ofSeconds(2))   // 2 second initial delay
    .maxRetryDelay(Duration.ofMinutes(1))       // Max 1 minute delay
    .backoffMultiplier(1.5)                     // Exponential backoff
    .enableProxyRotation(true)                  // Enable proxy rotation
    .rotateOnFirstError(false)                  // Don't rotate on first error
    .addRetryStatusCode(408)                    // Retry on timeout
    .addRetryErrorKeyword("connection reset")   // Retry on connection reset
    .build();

// Create handler with custom settings
var customHandler = factory.createHandler(Download.Type.ARIA2, retrySettings);
```

### 3. Per-Download Proxy Configuration

```java
// Create download with proxy-aware settings
Download download = new Download(uri);

ProxyAwareDownloadSettings proxySettings = new ProxyAwareDownloadSettings();
proxySettings.setMaxRetries(2)
    .setEnableProxyRotationForThisDownload(true)
    .setPreferredProxyType("HTTP")
    .setFallbackToDirectConnection(false)
    .addRetryErrorKeyword("rate limit exceeded");

download.setSettings(proxySettings);
```

## Error Detection

The system automatically detects errors that indicate server restrictions and triggers proxy rotation:

### HTTP Status Codes
- **403** - Forbidden
- **429** - Too Many Requests
- **503** - Service Unavailable
- **502** - Bad Gateway
- **504** - Gateway Timeout
- **401** - Unauthorized (IP-based blocking)

### Error Keywords
- "too many requests"
- "rate limit" / "rate-limit"
- "blocked" / "ip blocked" / "ip banned"
- "forbidden" / "access denied"
- "connection timeout" / "connection refused"
- "proxy error" / "proxy timeout"

## Proxy Management

### Adding Proxies

```java
// Add individual proxy
proxyManager.addProxy(new Proxy("host", 8080, Proxy.Type.HTTP));

// Add with authentication
proxyManager.addProxy(new Proxy("host", 8080, Proxy.Type.HTTP, "user", "pass"));

// Add from URL
proxyManager.addProxy(Proxy.fromUrl("http://user:pass@host:8080"));

// Load from file
proxyManager.loadProxiesFromFile(Paths.get("proxy-list.txt"));
```

### Proxy Health Tracking

The system automatically tracks proxy health:

- **Success Rate** - Based on successful vs failed requests
- **Response Time** - Average response time tracking
- **Failure Count** - Number of consecutive failures
- **Status** - UNKNOWN, HEALTHY, UNHEALTHY, BLOCKED

```java
// Record successful usage
long responseTime = 250; // milliseconds
proxyManager.recordSuccess(proxy, responseTime);

// Record failure
proxyManager.recordFailure(proxy, "HTTP 429 Too Many Requests");

// Get proxy statistics
Map<String, Object> stats = proxyManager.getStatistics();
System.out.println("Total proxies: " + stats.get("totalProxies"));
System.out.println("Healthy proxies: " + stats.get("healthyProxies"));
```

### Proxy Selection

Proxies are selected using weighted random selection based on health scores:

- Higher success rate = higher selection probability
- Faster response time = higher selection probability
- Recent failures = lower selection probability
- Blocked proxies are automatically excluded

## Retry Logic

### Exponential Backoff

Retry delays increase exponentially with each attempt:

```
Attempt 1: 2 seconds
Attempt 2: 3 seconds (2 * 1.5)
Attempt 3: 4.5 seconds (3 * 1.5)
Attempt 4: 6.75 seconds (4.5 * 1.5)
Attempt 5: 10.125 seconds (6.75 * 1.5, capped at maxRetryDelay)
```

### Jitter

Random jitter (±10%) is added to retry delays to prevent thundering herd problems when multiple downloads retry simultaneously.

### Proxy Rotation Strategy

1. **First attempt**: Use random healthy proxy
2. **Retry attempts**: Use different proxy than previous attempt
3. **Health-based selection**: Prefer proxies with better health scores 
4. **Fallback**: If no healthy proxies available, try all proxies

## Configuration Files

### Proxy List Format

Create a `proxy-list.txt` file with one proxy per line:

```
# Comments start with #
http://proxy1.example.com:8080
http://user:pass@proxy2.example.com:8080
https://secure-proxy.example.com:8443
socks4://socks-proxy.example.com:1080
socks5://user:pass@socks5-proxy.example.com:1080
```

## Monitoring and Statistics

### Proxy Pool Statistics

```java
Map<String, Object> stats = proxyManager.getStatistics();
// Available keys:
// - totalProxies: Total number of proxies in pool
// - proxiesInUse: Number of proxies currently being used
// - healthyProxies: Number of healthy proxies
// - unhealthyProxies: Number of unhealthy proxies
// - blockedProxies: Number of blocked proxies
// - averageResponseTime: Average response time across all proxies
```

### Individual Proxy Health

```java
Proxy proxy = proxyManager.getRandomProxy();
System.out.println("Health Score: " + proxy.getHealthScore());
System.out.println("Status: " + proxy.getStatus());
System.out.println("Success Count: " + proxy.getSuccessCount());
System.out.println("Failure Count: " + proxy.getFailureCount());
System.out.println("Average Response Time: " + proxy.getAverageResponseTime());
```

## Integration with Download Handlers

The proxy rotation system integrates seamlessly with existing download handlers:

```java
// Wrap any existing handler with retry functionality
DownloadHandler originalHandler = // ... get original handler
RetryableDownloadHandler retryableHandler = new RetryableDownloadHandler(
    originalHandler, 
    proxyManager, 
    retrySettings, 
    scheduler, 
    executor
);

// Use the retryable handler like any other handler
retryableHandler.startDownload(download);
```

## Best Practices

### 1. Proxy Pool Management
- Use a mix of HTTP and SOCKS proxies for better coverage
- Regularly update your proxy list to remove dead proxies
- Monitor proxy health and remove consistently failing proxies
- Consider using paid proxy services for better reliability

### 2. Retry Configuration
- Set reasonable retry limits (3-5 retries) to avoid excessive delays
- Use exponential backoff to avoid overwhelming servers
- Enable jitter to prevent synchronized retry storms
- Adjust error keywords based on the sites you're downloading from

### 3. Performance Optimization
- Use separate proxy pools for different types of downloads
- Implement proxy rotation at the application level for better control
- Monitor proxy response times and prefer faster proxies
- Consider geographic proximity when selecting proxies

### 4. Error Handling
- Log proxy rotation events for debugging
- Implement fallback mechanisms for when all proxies fail
- Handle proxy authentication errors gracefully
- Monitor for patterns in proxy failures

## Troubleshooting

### Common Issues

1. **No proxies available**
   - Check proxy list file exists and is readable
   - Verify proxy URLs are in correct format
   - Check if all proxies have been marked as blocked

2. **Retries not working**
   - Verify error messages match configured retry keywords
   - Check if HTTP status codes are in retry list
   - Ensure proxy rotation is enabled in settings

3. **Poor performance**
   - Check proxy response times
   - Reduce number of concurrent downloads
   - Use geographically closer proxies
   - Consider using faster proxy services

### Debug Logging

Enable debug logging to troubleshoot issues:

```java
Logger.getLogger("org.manager.proxy").setLevel(Level.FINE);
```

## Examples

See `ProxyRotationExample.java` for complete working examples demonstrating:
- Basic proxy rotation setup
- Custom retry settings
- Per-download configuration
- Statistics monitoring

## Thread Safety

All classes in this package are thread-safe and can be used concurrently from multiple threads. The `ProxyRotationManager` uses concurrent data structures and appropriate synchronization to ensure safe concurrent access.