# Folder Monitoring Tests

This directory contains comprehensive tests for the folder monitoring functionality of the Open Download Manager. The tests are organized into three categories: unit tests, integration tests, and end-to-end tests.

## Test Structure

### Unit Tests
- **`FolderMonitorSettingsTest`** - Tests configuration settings for folder monitoring
- **`FolderMonitorServiceImplTest`** - Tests the core folder monitoring service with mocked dependencies
- **`TorrentFolderMonitorTest`** - Tests specialized torrent folder monitoring with mocked components

### Integration Tests
- **`FolderMonitorIntegrationTest`** - Tests components working together with real file system operations
- **`TorrentFolderMonitorIntegrationTest`** - Tests torrent monitoring integration with real FolderMonitorService

### End-to-End Tests
- **`FolderMonitorE2ETest`** - Tests complete workflows from file detection to download processing

## Test Coverage

The tests cover the following functionality:

### Core Features
- File system monitoring and event detection
- File filtering by extension, size, and patterns
- Recursive directory monitoring
- Batch processing of multiple files
- Debounce handling for rapid file changes
- Multiple folder monitoring simultaneously

### Torrent-Specific Features
- Automatic torrent file detection and processing
- Integration with DownloadManager for torrent downloads
- Torrent file validation
- Custom settings for torrent monitoring
- Download destination management

### Error Handling
- Invalid file handling
- IO exception recovery
- Temporary system error recovery
- Listener exception handling
- Filesystem monitoring interruptions

### Real-World Scenarios
- Browser download simulation
- Network drive monitoring
- High-volume automated processing
- Progressive file downloads
- Concurrent file creation

## Running the Tests

### Run All Folder Monitoring Tests
```bash
mvn test -Dtest="org.manager.folder.**"
```

### Run Specific Test Categories

#### Unit Tests Only
```bash
mvn test -Dtest="org.manager.folder.*Test"
```

#### Integration Tests Only
```bash
mvn test -Dtest="org.manager.folder.*IntegrationTest"
```

#### End-to-End Tests Only
```bash
mvn test -Dtest="org.manager.folder.*E2ETest"
```

### Run Test Suite
```bash
mvn test -Dtest="org.manager.folder.FolderMonitorTestSuite"
```

### Run Individual Test Classes
```bash
mvn test -Dtest="FolderMonitorSettingsTest"
mvn test -Dtest="FolderMonitorServiceImplTest"
mvn test -Dtest="TorrentFolderMonitorTest"
mvn test -Dtest="FolderMonitorIntegrationTest"
mvn test -Dtest="TorrentFolderMonitorIntegrationTest"
mvn test -Dtest="FolderMonitorE2ETest"
```

## Test Environment Requirements

The tests are designed to run in a Docker container environment with all dependencies available. They use:

- **JUnit 5** for test framework
- **Mockito** for mocking dependencies
- **Awaitility** for asynchronous testing
- **Temporary directories** for file system operations
- **Real file system operations** (no unnecessary mocking)

## Test Design Principles

### 1. Close to Real-World Testing
Tests avoid unnecessary mocking and use real file system operations where possible to test as close to actual usage as possible.

### 2. Comprehensive Coverage
Tests cover normal operation, edge cases, error conditions, and real-world scenarios that users might encounter.

### 3. Performance Awareness
Tests include scenarios for high-volume processing and batch operations to ensure the system can handle realistic loads.

### 4. Async Operation Testing
Proper handling of asynchronous operations using `CompletableFuture` and `Awaitility` for reliable testing.

### 5. Resource Cleanup
All tests properly clean up resources (file system, threads, services) to prevent interference between tests.

## Key Test Scenarios

### File Detection and Processing
- New file detection in monitored directories
- Existing file processing when monitoring starts
- File filtering by extension, size, and exclude patterns
- Recursive directory monitoring
- Case-sensitive/insensitive file matching

### Batch Processing
- Multiple file processing in configurable batches
- High-volume file processing performance
- Concurrent file creation handling

### Error Handling and Recovery
- Invalid file handling (corrupted, empty, wrong format)
- Temporary system errors and recovery
- Filesystem monitoring interruptions
- Listener exception isolation

### Real-World Scenarios
- Browser download folder monitoring
- Network drive monitoring with latency
- Automated system integration
- Progressive file downloads (files being written)

### Configuration and Lifecycle
- Dynamic settings updates
- Multiple folder monitoring
- Service startup and shutdown
- Resource management

## Debugging Tests

### Enable Debug Logging
Add to your test run:
```bash
-Djava.util.logging.config.file=src/test/resources/logging.properties
```

### Common Issues
1. **Timing Issues**: Tests use `Awaitility` with appropriate timeouts. If tests are flaky, check timeout values.
2. **File System Permissions**: Ensure test environment has proper file system access.
3. **Resource Cleanup**: If tests fail with resource conflicts, ensure proper cleanup in `@AfterEach` methods.

## Contributing

When adding new tests:

1. Follow the existing test structure and naming conventions
2. Use appropriate test categories (Unit/Integration/E2E)
3. Include proper resource cleanup
4. Test both success and failure scenarios
5. Use realistic file content and scenarios
6. Include performance considerations for batch operations
7. Document any special test requirements

## Dependencies

The tests require these main dependencies (defined in `pom.xml`):

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter-api</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

All tests are designed to run in the specialized Docker container environment with all system dependencies available.