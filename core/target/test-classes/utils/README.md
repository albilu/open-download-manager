# TestUtils Usage Guide

The `TestUtils` class provides comprehensive MockWebServer functionality for testing download operations of various file sizes and scenarios.

## Quick Start

```java
@BeforeAll
static void setupClass() throws IOException {
    TestUtils.setupMockWebServer();
}

@AfterAll
static void teardownClass() throws IOException {
    TestUtils.teardownMockWebServer();
}

@Test
void testDownload() throws IOException {
    // Get a URL that serves a 10MB file
    String url = TestUtils.getMockUrl(10);
    
    // Use the URL for your download testing
    // Your download code here...
}
```

## Core Methods

### Basic File Downloads
- `getMockUrl(int sizeMB)` - Returns URL serving a file of specified size in MB
- `getMockUrl(int sizeMB, String contentType, String filename)` - Custom content type and filename

### Advanced Scenarios
- `getMockUrlWithRange(int sizeMB, long rangeStart, long rangeEnd)` - Partial content (HTTP 206) for resume testing
- `getMockUrlWithThrottling(int sizeMB, int bytesPerSecond)` - Throttled downloads for speed testing
- `getMockErrorUrl(int errorCode)` - Error responses (404, 500, etc.)

### Server Management
- `setupMockWebServer()` - Initialize server (call in @BeforeAll/@BeforeEach)
- `teardownMockWebServer()` - Cleanup server (call in @AfterAll/@AfterEach)
- `getMockWebServer()` - Access server instance for advanced configuration
- `getBaseUrl()` - Get server base URL

### Request Inspection
- `getLastRequest()` - Get the last request made to the server
- Useful for verifying headers, request method, etc.

### Utilities
- `formatBytes(long bytes)` - Convert bytes to human-readable format

## Example Test Scenarios

### Testing Different File Sizes
```java
@Test
void testVariousFileSizes() throws IOException {
    int[] sizes = {1, 5, 10, 50, 100}; // MB
    
    for (int sizeMB : sizes) {
        String url = TestUtils.getMockUrl(sizeMB);
        // Test download of each size...
    }
}
```

### Testing Resume Functionality
```java
@Test
void testResumeDownload() throws IOException {
    // Download first 1MB of a 10MB file
    String url = TestUtils.getMockUrlWithRange(10, 0, 1024*1024-1);
    // Test partial download...
    
    // Resume from 1MB to end
    String resumeUrl = TestUtils.getMockUrlWithRange(10, 1024*1024, 10*1024*1024-1);
    // Test resume functionality...
}
```

### Testing Error Handling
```java
@Test
void testErrorHandling() throws IOException {
    String url404 = TestUtils.getMockErrorUrl(404);
    String url500 = TestUtils.getMockErrorUrl(500);
    // Test error scenarios...
}
```

### Testing Slow Downloads
```java
@Test
void testSlowDownload() throws IOException {
    // Limit to 1KB/sec for a 1MB file
    String url = TestUtils.getMockUrlWithThrottling(1, 1024);
    // Test timeout handling, progress updates, etc...
}
```

## Thread Safety

The TestUtils class manages a single static MockWebServer instance. For concurrent test execution:

1. Use `@BeforeAll/@AfterAll` for class-level setup/teardown
2. Or use `@BeforeEach/@AfterEach` for test-level isolation
3. Consider using separate TestUtils instances for parallel test classes

## Memory Considerations

The MockWebServer generates test data in memory. For very large file tests (>100MB), consider:

1. Using smaller test files where possible
2. Testing with throttling to simulate large downloads without memory overhead
3. Using range requests to test specific portions of large files

## Integration with Download Managers

This TestUtils is specifically designed for testing download managers that support:
- HTTP/HTTPS downloads
- Range requests (resume functionality)
- Progress tracking
- Error handling
- Speed limiting
- Multiple file sizes

The generated test data uses a predictable pattern for validation and debugging.
