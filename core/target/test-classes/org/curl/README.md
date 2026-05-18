# Curl Package Tests

This directory contains comprehensive tests for the curl package in the Open Download Manager. The tests are organized into three categories: unit tests, integration tests, and end-to-end (E2E) tests.

## Test Structure

### Unit Tests
- **CurlSettingsTest.java** - Tests the `CurlSettings` configuration class
- **CurlUtilsTest.java** - Tests utility functions in `CurlUtils` 
- **CurlClientTest.java** - Tests the core `CurlClient` with mocked processes
- **CurlDownloadHandlerTest.java** - Tests the `CurlDownloadHandler` with mocked dependencies

### Integration Tests
- **CurlIntegrationTest.java** - Tests component interactions with minimal mocking

### End-to-End Tests
- **CurlE2ETest.java** - Tests complete download flows with real curl (when available)

## Test Categories

### Unit Tests
Focus on testing individual classes and methods in isolation using extensive mocking:
- Configuration validation and serialization
- Command building logic
- Process management
- Error handling
- Listener notification patterns

### Integration Tests
Test interactions between components with minimal mocking:
- Settings integration with client
- Handler coordination with client
- Multi-listener event forwarding
- Concurrent download management
- Error propagation across components

### End-to-End Tests
Test complete download scenarios using real HTTP servers:
- Actual file downloads with curl
- Progress tracking
- Server error handling
- Redirect following
- Pause/resume functionality
- Concurrent downloads
- Custom headers and user agents

## Running Tests

### All Tests
```bash
mvn test
```

### Unit Tests Only
```bash
mvn test -Dtest="*Test"
```

### Integration Tests Only
```bash
mvn test -Dtest="*IntegrationTest"
```

### E2E Tests Only
```bash
mvn test -Dtest="*E2ETest"
```

## Test Dependencies

The tests use the following key dependencies:
- **JUnit 5** - Main testing framework
- **Mockito** - Mocking framework for unit tests
- **Awaitility** - Async testing utilities
- **MockWebServer** - HTTP server mocking for E2E tests

## Curl Availability

Some tests require curl to be installed on the system:
- Unit and integration tests use mocking and don't require curl
- E2E tests are conditional and only run when curl is available
- Tests use `@EnabledIf("isCurlAvailable")` to check curl availability

## Test Patterns

### Mocking Process Execution
Unit tests mock `ProcessBuilder` and `Process` to simulate curl execution:

```java
try (MockedStatic<ProcessBuilder> processBuilderMock = mockStatic(ProcessBuilder.class)) {
    processBuilderMock.when(() -> new ProcessBuilder(anyList()))
            .thenReturn(mockProcessBuilder);
    when(mockProcessBuilder.start()).thenReturn(mockProcess);
    when(mockProcess.waitFor()).thenReturn(0);
}
```

### Async Testing
Tests use CompletableFuture and Awaitility for async operations:

```java
CompletableFuture<Void> downloadComplete = new CompletableFuture<>();
DownloadListener listener = new TestDownloadListener() {
    @Override
    public void onDownloadComplete(Download download) {
        downloadComplete.complete(null);
    }
};
```

### HTTP Server Mocking
E2E tests use MockWebServer for realistic HTTP scenarios:

```java
mockWebServer.enqueue(new MockResponse()
        .setBody(fileContent)
        .setHeader("Content-Type", "text/plain"));
```

## Key Test Scenarios

### Settings Configuration
- Default values validation
- Method chaining
- Serialization to command options
- Settings copying and independence

### Download Lifecycle
- Start → Progress → Complete flow
- Error handling and status transitions
- Pause/resume functionality
- Cancellation with file cleanup

### Process Management
- Command building with various options
- Process execution and monitoring
- Progress parsing from curl output
- Process termination handling

### Listener Management
- Multiple listener registration
- Event forwarding and filtering
- Exception handling in listeners
- Async event delivery

### Concurrent Operations
- Multiple simultaneous downloads
- Task coordination and cleanup
- Resource management
- Thread safety

## Testing Best Practices

1. **Isolation** - Unit tests use extensive mocking to test components in isolation
2. **Real Scenarios** - Integration and E2E tests minimize mocking for realistic testing
3. **Async Handling** - Proper async testing with timeouts and completion futures
4. **Resource Cleanup** - All tests properly clean up resources in @AfterEach methods
5. **Conditional Execution** - E2E tests only run when required tools are available
6. **Comprehensive Coverage** - Tests cover success paths, error conditions, and edge cases

## Troubleshooting

### Tests Failing Due to Missing Curl
- Unit and integration tests should pass without curl
- E2E tests will be skipped if curl is not available
- Install curl system-wide to enable E2E tests

### Timeout Issues
- Increase test timeouts if running on slow systems
- Check for proper resource cleanup in failed tests
- Ensure mock servers are properly started/stopped

### Flaky Tests
- Tests use proper synchronization with Awaitility
- Check for race conditions in async operations
- Verify proper cleanup of background threads