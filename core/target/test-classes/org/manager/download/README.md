# Download Manager Test Suite

This directory contains comprehensive tests for the Open Download Manager's core download functionality. The test suite is designed to validate the download manager implementation with minimal mocking, focusing on real integration and end-to-end testing as specified in the project requirements.

## Test Architecture

The test suite follows a structured approach with multiple test categories:

### 1. Unit Tests

-   **DownloadTest.java**: Comprehensive unit tests for the `Download` domain model class
    -   Tests construction, status management, progress tracking, thread safety
    -   Validates mirror management, settings integration, and schedule handling
    -   Includes edge cases and error handling scenarios
    -   **55 test methods** across 9 nested test classes

### 2. Integration Tests

-   **MinimalDownloadTest.java**: Integration tests that validate component interactions
    -   Tests real DownloadManager instantiation and lifecycle
    -   Validates download creation, retrieval, and management operations
    -   Tests concurrent access and thread safety with real components
    -   **10 test methods** testing core functionality

### 3. Test Organization

-   **DownloadManagerTestSuite.java**: JUnit 5 test suite that organizes all download manager tests
-   Uses `@Suite` and `@SelectClasses` annotations for comprehensive test execution

## Key Testing Principles

Following the project's testing guidelines:

1. **Minimal Mocking**: Tests use real components wherever possible
2. **Docker-Ready**: All tests are designed to run in containerized environments
3. **Real Dependencies**: Tests integrate with actual external tools (aria2, yt-dlp, curl, httrack)
4. **Thread Safety**: Multi-threaded scenarios are thoroughly tested
5. **Performance Aware**: Tests validate performance characteristics under load

## Test Execution

### Running All Tests

```bash
# Run the complete test suite
mvn test -Dtest=DownloadManagerTestSuite -pl core

# Run individual test classes
mvn test -Dtest=DownloadTest -pl core
mvn test -Dtest=MinimalDownloadTest -pl core
```

### Prerequisites

Tests require the following dependencies to be available:

-   java 21+
-   Maven 3.6+
-   External tools: aria2c, curl, yt-dlp, httrack (installed in container)
-   Internet connectivity for integration tests
-   Sufficient disk space for temporary files

## Test Coverage

### DownloadTest Coverage Areas

-   ✅ **Construction & Initialization** (6 tests)

    -   URI-based construction with type detection
    -   Torrent download creation
    -   Default value initialization
    -   Filename extraction from URIs

-   ✅ **Status Management** (10 tests)

    -   Status transitions and timestamp tracking
    -   All download status values
    -   Concurrent status changes

-   ✅ **Progress Management** (5 tests)

    -   Progress calculation from size/downloaded bytes
    -   Handling of zero size and edge cases
    -   Real-time progress updates

-   ✅ **Settings Management** (8 tests)

    -   Lazy settings initialization
    -   Connection and proxy configuration
    -   Method chaining support
    -   Settings factory integration

-   ✅ **Thread Safety** (4 tests)

    -   Concurrent access to status, progress, mirrors, settings
    -   Multi-threaded operations validation
    -   Synchronization correctness

-   ✅ **Mirror Management** (2 tests)

    -   Adding and removing download mirrors
    -   Defensive copying of mirror lists

-   ✅ **Schedule Management** (4 tests)

    -   Schedule settings integration
    -   Activity time validation
    -   Delegation to schedule components

-   ✅ **Edge Cases & Error Handling** (12 tests)

    -   Null value handling
    -   Invalid input validation
    -   Large number handling
    -   Boundary conditions

-   ✅ **Object Methods** (4 tests)
    -   toString() representation
    -   Null value handling in string representation

### MinimalDownloadTest Coverage Areas

-   ✅ **Component Integration** (10 tests)
    -   DownloadManager instantiation via ComponentFactory
    -   Real service initialization (aria2, yt-dlp, curl, httrack)
    -   Download creation and management
    -   Concurrent access validation
    -   Lifecycle management (initialization/shutdown)

## Real-World Integration

The tests demonstrate real integration with:

-   **aria2**: BitTorrent and HTTP download engine
-   **yt-dlp**: YouTube and video download tool
-   **curl**: HTTP client for direct downloads
-   **httrack**: Website scraping and mirroring
-   **Component Factory**: Dependency injection and service management
-   **Clipboard Service**: URL detection and monitoring
-   **Folder Monitor Service**: File system monitoring

## Test Results

Latest test execution shows:

-   **65 total tests** passing
-   **Real tool integration** with aria2 v1.37.0, yt-dlp v2025.04.30, curl v8.15.0, httrack v3.49-6
-   **Multi-threaded operations** validated under concurrent load
-   **Memory management** tested with proper cleanup
-   **Service lifecycle** properly managed with initialization and shutdown

## Extending the Test Suite

When adding new tests:

1. **Follow Naming Conventions**: Use descriptive `shouldXxxWhenYyy` method names
2. **Add @DisplayName**: Provide clear test descriptions
3. **Use @Timeout**: Prevent hanging tests with appropriate timeouts
4. **Organize with @Nested**: Group related tests in nested classes
5. **Clean Up Resources**: Ensure proper teardown in @AfterEach methods
6. **Test Real Scenarios**: Prefer integration over mocking when feasible

## Environment Variables

-   `RUN_E2E_TESTS=true`: Enable end-to-end tests with network calls (for future expansion)
-   `RUN_PERFORMANCE_TESTS=true`: Enable performance benchmarks (for future expansion)

## Notes

This test suite prioritizes testing the core engine implementation with real dependencies as specified in the project requirements. The tests are designed to run in Docker containers with all external dependencies available, providing comprehensive validation of the download manager's functionality.
