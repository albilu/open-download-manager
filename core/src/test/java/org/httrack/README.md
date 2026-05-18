# Httrack Package Tests

This directory contains comprehensive tests for the httrack package, which provides website mirroring functionality using the httrack tool.

## Test Structure

### Unit Tests
Unit tests focus on testing individual classes in isolation with mocked dependencies:

- **`HttrackSettingsTest.java`**: Tests configuration settings, validation, command building, and settings copying
- **`HttrackJobTest.java`**: Tests job lifecycle, status transitions, progress tracking, and timing calculations
- **`HttrackClientTest.java`**: Tests client operations with mocked processes (no actual httrack execution)

### Integration Tests
Integration tests verify real httrack functionality with actual process execution:

- **`HttrackIntegrationTest.java`**: Tests real httrack operations, process monitoring, and file system interactions

### End-to-End Tests
End-to-end tests simulate complete workflows from start to finish:

- **`HttrackE2ETest.java`**: Tests complete mirror workflows, job lifecycle management, and resource cleanup

## Test Categories

### 1. Configuration Testing
- Settings validation and error handling
- Command line building from settings
- Settings copying and isolation
- Parameter validation (depth, connections, rates)

### 2. Job Management Testing
- Job creation and ID generation
- Status transitions (PENDING → RUNNING → COMPLETED/ERROR/CANCELED)
- Progress tracking and statistics
- Error handling and recovery

### 3. Process Management Testing
- httrack process spawning and monitoring
- Output parsing and progress extraction
- Process termination and cleanup
- Concurrent job execution

### 4. Notification Testing
- Job lifecycle event notifications
- Progress update notifications
- Error and completion notifications
- Listener management

### 5. File System Testing
- Output directory creation
- File filtering and exclusion patterns
- Cleanup operations
- Permission handling

## Running Tests

### Prerequisites
- **Unit Tests**: No external dependencies required
- **Integration/E2E Tests**: Require httrack to be installed and available in PATH

### Execution Commands

```bash
# Run all httrack tests
mvn test -Dtest=HttrackTestSuite

# Run only unit tests (no httrack required)
mvn test -Dtest=Httrack*Test -Dtest="!*Integration*,!*E2E*"

# Run only tests that require httrack
mvn test -Dtest="*Integration*,*E2E*"

# Run specific test class
mvn test -Dtest=HttrackSettingsTest

# Run with coverage report
mvn test jacoco:report -Dtest=HttrackTestSuite
```

### Test Environment

#### Docker Environment
Tests are designed to run in a Docker container with httrack pre-installed:
- Integration and E2E tests use `@EnabledIf("isHttrackAvailable")` to conditionally run
- Mock web server used for controlled testing scenarios
- Temporary directories for isolated file operations

#### Local Development
- Unit tests run without httrack installation
- Integration tests require: `sudo apt-get install httrack` (Ubuntu/Debian)
- Mock dependencies avoid unnecessary external calls

## Test Data and Fixtures

### Mock Web Server
E2E tests use OkHttp MockWebServer to provide controlled test scenarios:
- Simulated websites with various content types
- Configurable response delays for testing cancellation
- Multiple pages for depth testing

### Temporary Directories
All tests use JUnit 5's `@TempDir` for isolated file operations:
- Automatic cleanup after test completion
- No interference between test executions
- Safe parallel test execution

## Key Testing Patterns

### 1. Conditional Test Execution
```java
@EnabledIf("isHttrackAvailable")
@Test
void testWithRealHttrack() {
    // Only runs if httrack is installed
}
```

### 2. Async Operation Testing
```java
CompletableFuture<String> future = client.startMirror(settings);
String jobId = future.get(10, TimeUnit.SECONDS);

await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
    assertTrue(job.isCompleted());
});
```

### 3. Process Mocking
```java
@Mock
private Process mockProcess;

try (MockedStatic<ProcessBuilder> processBuilderMock = mockStatic(ProcessBuilder.class)) {
    // Test process interactions without real execution
}
```

### 4. Event Listener Testing
```java
private class TestNotificationListener implements HttrackNotificationListener {
    private final AtomicBoolean jobStarted = new AtomicBoolean(false);
    
    @Override
    public void onJobStarted(HttrackJob job) {
        jobStarted.set(true);
    }
}
```

## Test Coverage Goals

- **Unit Tests**: 95%+ coverage of business logic
- **Integration Tests**: Cover all major httrack operations
- **E2E Tests**: Cover complete user workflows
- **Error Scenarios**: All error paths and edge cases
- **Concurrency**: Thread safety and concurrent operations

## Troubleshooting

### Common Issues

1. **httrack not found**: Install httrack or run only unit tests
2. **Port conflicts**: MockWebServer uses random ports to avoid conflicts
3. **Timeout failures**: Increase timeout values for slow CI environments
4. **Permission errors**: Ensure write permissions in temp directories

### Debugging Tips

1. **Enable verbose logging**: Add `-Dlogger.level=DEBUG` to see detailed output
2. **Isolate tests**: Run individual test methods to identify specific failures
3. **Check httrack version**: Some tests may require specific httrack versions
4. **Monitor resources**: Ensure adequate memory and disk space for large downloads

## Contributing

When adding new tests:

1. **Follow naming conventions**: `testMethodName_WhenCondition_ThenExpectedResult`
2. **Use descriptive display names**: `@DisplayName("Should handle invalid URL gracefully")`
3. **Group related tests**: Use nested test classes or parameterized tests
4. **Add timeout annotations**: Prevent hanging tests with `@Timeout`
5. **Clean up resources**: Ensure proper cleanup in `@AfterEach` methods
6. **Document complex scenarios**: Add comments for non-obvious test logic

## Performance Considerations

- Tests use small test files to minimize execution time
- Concurrent execution enabled where thread-safe
- Resource cleanup prevents memory leaks
- Mock servers avoid network dependencies
- Parameterized tests reduce code duplication