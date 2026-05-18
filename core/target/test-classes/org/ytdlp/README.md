# YtDlp Package Test Suite

This directory contains comprehensive tests for the YtDlp package, following the project's principle of **avoiding mocks on critical components**. The test suite is structured to validate real yt-dlp integration while appropriately testing component interactions.

## Test Architecture Overview

The YtDlp test suite is organized into three distinct layers, each serving a specific purpose:

### 1. Unit Tests (`YtDlpSimpleTest`, `YtDlpClientTest`, `YtDlpUrlUtilsTest`)
- **Purpose**: Test individual class behavior and configuration logic
- **Scope**: Pure logic, data models, utility functions, settings management
- **Dependencies**: No external processes or network calls
- **Mocking**: Minimal - only for test setup helpers

### 2. Integration Tests (`YtDlpIntegrationTest`)
- **Purpose**: Test component interactions and real command building
- **Scope**: Factory patterns, settings integration, real yt-dlp availability checks
- **Dependencies**: Real yt-dlp binary for command validation
- **Mocking**: External services only, never the yt-dlp binary

### 3. End-to-End Tests (`YtDlpE2ETest`)
- **Purpose**: Complete workflow validation with real yt-dlp processes
- **Scope**: Full download lifecycles, process management, error handling
- **Dependencies**: Real yt-dlp binary, test video URLs, actual downloads
- **Mocking**: Only test video servers (when necessary)

## Testing Philosophy

### ✅ What We DON'T Mock (Critical Components)
- **yt-dlp binary execution** - Real ProcessBuilder and Process management
- **Command line argument building** - Actual yt-dlp command construction
- **JSON output parsing** - Real yt-dlp response processing
- **Process lifecycle management** - Start, monitor, cancel real processes
- **File system operations** - Actual download file creation
- **aria2c integration** - Real external downloader testing

### ✅ What We DO Mock (External Dependencies)
- **Video hosting servers** - For network isolation in unit tests
- **Progress callbacks** - For testing callback interface contracts
- **Global settings** - When testing factory patterns
- **File system errors** - For error scenario testing only

### ❌ Previous Problematic Approach (Fixed)
The original `YtDlpClientTest` heavily mocked critical infrastructure:
```java
// ❌ WRONG: Mocking the core yt-dlp process execution
try (MockedStatic<Runtime> runtimeMock = Mockito.mockStatic(Runtime.class)) {
    when(mockRuntime.exec(any(String[].class))).thenReturn(mockProcess);
    // This bypasses the actual yt-dlp integration!
}
```

## Test Structure and Coverage

### Unit Tests

#### `YtDlpSimpleTest.java`
**Purpose**: Basic functionality and configuration testing
- ✅ Settings creation and validation
- ✅ Method chaining patterns
- ✅ URL analysis and platform detection
- ✅ Factory pattern basic functionality
- ✅ Settings copying and independence

#### `YtDlpClientTest.java` (Refactored)
**Purpose**: Client interface and basic method testing
- ✅ Constructor variations and path handling
- ✅ Method signatures and null handling
- ✅ Basic availability checks (without process mocking)
- ✅ Concurrent request handling logic
- ✅ Byte conversion calculations

#### `YtDlpUrlUtilsTest.java`
**Purpose**: URL validation and platform detection utilities
- ✅ Platform identification (YouTube, Vimeo, etc.)
- ✅ URL format validation
- ✅ Playlist detection logic
- ✅ Format suggestions per platform

### Integration Tests

#### `YtDlpIntegrationTest.java`
**Purpose**: Component integration with real yt-dlp binary
- ✅ Factory and client integration
- ✅ Settings integration with real command building
- ✅ Real yt-dlp availability and version checking
- ✅ aria2c integration testing
- ✅ Error handling across component boundaries
- ✅ Concurrent operations coordination
- ✅ Resource cleanup and lifecycle management

**Key Features**:
- Uses real yt-dlp binary for command validation
- Tests actual JSON parsing from yt-dlp info extraction
- Validates settings are properly applied to commands
- Ensures component interactions work with real processes

### End-to-End Tests

#### `YtDlpE2ETest.java`
**Purpose**: Complete workflow testing with real downloads
- ✅ Real video information extraction
- ✅ Small file downloads with progress monitoring
- ✅ Audio extraction workflows
- ✅ Download cancellation with real processes
- ✅ Error handling with actual yt-dlp errors
- ✅ aria2c external downloader integration
- ✅ Concurrent download management
- ✅ Task lifecycle management

**Key Features**:
- Uses real archive.org test videos (non-copyrighted)
- Downloads actual files to verify functionality
- Tests real process cancellation and cleanup
- Validates progress callbacks with real yt-dlp output
- Ensures aria2c integration works when available

## Running Tests

### Prerequisites
Tests are designed to run in Docker containers with dependencies:
```bash
# Required for all tests
apt-get install yt-dlp

# Optional for enhanced testing
apt-get install aria2
```

### Test Execution

#### Run All Tests
```bash
mvn test -Dtest="org.ytdlp.**"
```

#### Run Only Unit Tests (Fast)
```bash
mvn test -Dtest="org.ytdlp.*Test" -Dexcludes="**/*IntegrationTest,**/*E2ETest"
```

#### Run Integration Tests
```bash
mvn test -Dtest="org.ytdlp.YtDlpIntegrationTest"
```

#### Run E2E Tests (Requires yt-dlp)
```bash
mvn test -Dtest="org.ytdlp.YtDlpE2ETest"
```

### Conditional Test Execution

Tests use `@EnabledIf("isYtDlpAvailable")` to automatically skip when yt-dlp is not installed:

```java
@EnabledIf("isYtDlpAvailable")
void shouldExtractRealVideoInformation() {
    // Test only runs if yt-dlp is available
}

static boolean isYtDlpAvailable() {
    // Real availability check without mocking
    ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--version");
    // ... actual process execution
}
```

## Test Data and Resources

### Test URLs
Using reliable, non-copyrighted sources:
- **Video**: `https://archive.org/details/BigBuckBunny_328`
- **Audio**: `https://archive.org/details/testmp3testfile`
- **Playlist**: `https://archive.org/details/test_playlist`

### Test Settings
Optimized for CI/CD environments:
```java
YtDlpSettings testSettings = new YtDlpSettings()
    .setFormat("worst")        // Fastest download
    .setMaxFilesize("5M")      // Limit size for CI
    .setNoPlaylist(true);      // Avoid large playlists
```

### Progress Monitoring
Real progress callback testing:
```java
private static class TestProgressCallback implements YtDlpClient.ProgressCallback {
    private final AtomicBoolean progressCalled = new AtomicBoolean(false);
    private final AtomicBoolean completionCalled = new AtomicBoolean(false);
    
    @Override
    public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
        progressCalled.set(true);
        // Validate real progress data from yt-dlp
    }
    
    @Override
    public void onComplete(String filename) {
        completionCalled.set(true);
        // Verify actual file was created
    }
}
```

## Error Handling and Edge Cases

### Real Error Scenarios
Tests validate actual yt-dlp error conditions:
- Invalid URLs with real yt-dlp error responses
- Unsupported formats with actual format validation
- Network timeouts with real process behavior
- Process cancellation with actual signal handling

### Resource Management
All tests properly manage resources:
```java
@AfterEach
void tearDown() {
    if (client != null) {
        client.shutdown();  // Real process cleanup
    }
    if (factory != null) {
        factory.shutdown(); // Real resource cleanup
    }
}
```

## Performance Considerations

### Test Timeouts
Appropriate timeouts for different test types:
- **Unit Tests**: 10 seconds max
- **Integration Tests**: 60 seconds max  
- **E2E Tests**: 120 seconds max (real downloads)

### CI/CD Optimization
- Small file sizes for faster execution
- Conditional execution based on tool availability
- Parallel test execution where possible
- Proper resource cleanup to prevent CI pollution

## Debugging and Troubleshooting

### Common Issues

1. **yt-dlp Not Available**
   - Tests automatically skip with `@EnabledIf`
   - Check PATH or install yt-dlp in test environment

2. **Network Connectivity**
   - E2E tests require internet access
   - Use reliable archive.org URLs for consistency

3. **Process Cleanup**
   - Real processes require proper shutdown
   - Use `@AfterEach` for guaranteed cleanup

4. **aria2c Integration**
   - Optional dependency - tests adapt automatically
   - Install aria2 for complete test coverage

### Debug Mode
Enable verbose logging for troubleshooting:
```java
Logger.getLogger("org.ytdlp").setLevel(Level.FINE);
```

### Test Environment Variables
```bash
# Enable debug output
export YTDLP_DEBUG=true

# Use specific yt-dlp path
export YTDLP_PATH=/custom/path/yt-dlp

# Enable aria2c testing
export ARIA2C_PATH=/usr/bin/aria2c
```

## Best Practices for Contributors

### When Adding New Tests

1. **Choose Appropriate Test Layer**
   - Unit: Pure logic, no external processes
   - Integration: Component interactions with real tools
   - E2E: Complete workflows with real downloads

2. **Follow Naming Conventions**
   - Use descriptive `shouldXxxWhenYyy` method names
   - Add `@DisplayName` with clear descriptions
   - Group related tests with `@Nested` classes

3. **Resource Management**
   - Always use `@TempDir` for file operations
   - Implement proper cleanup in `@AfterEach`
   - Set appropriate `@Timeout` values

4. **Real vs Mock Decision Matrix**
   ```
   Component Type          | Mock? | Reason
   ------------------------|-------|---------------------------
   yt-dlp binary          | NEVER | Critical infrastructure
   Process execution      | NEVER | Core functionality
   Command building       | NEVER | Must validate real commands
   JSON parsing           | NEVER | Must handle real responses
   Video hosting servers  | YES   | External dependency
   File system errors     | YES   | For error testing only
   Progress callbacks     | YES   | Interface contract testing
   ```

5. **Test Data Guidelines**
   - Use reliable, permanent test URLs
   - Keep file sizes small for CI performance
   - Use non-copyrighted content only
   - Document expected behavior changes

## Coverage Goals

Target coverage levels by test type:
- **Unit Tests**: >90% line coverage for utility classes
- **Integration Tests**: All component interaction paths
- **E2E Tests**: Complete user workflow scenarios

Priority areas:
1. Real yt-dlp command execution and parsing
2. Process lifecycle management  
3. Error handling with real error conditions
4. Settings integration with actual commands
5. Concurrent operation safety

## Migration from Previous Approach

### What Changed
- **Removed**: Heavy mocking of Runtime and Process
- **Added**: Real yt-dlp binary integration tests
- **Enhanced**: Process management and lifecycle testing
- **Improved**: Error handling with real error scenarios

### Benefits of New Approach
- **Higher Confidence**: Tests validate actual yt-dlp integration
- **Real Error Detection**: Catches command building and parsing errors
- **Better Coverage**: Tests complete workflows end-to-end
- **CI/CD Ready**: Designed for containerized execution environments

### Backwards Compatibility
The new test structure maintains the same public API testing while providing much more thorough validation of the underlying yt-dlp integration.

---

This test suite ensures that the YtDlp package works correctly with real yt-dlp processes while maintaining fast execution for CI/CD pipelines. The layered approach provides comprehensive coverage without over-mocking critical infrastructure components.