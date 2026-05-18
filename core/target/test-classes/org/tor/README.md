# Tor Package Test Suite

This directory contains comprehensive tests for the Tor package in the Open Download Manager project.

## Overview

The test suite covers all major components of the Tor integration:

- **TorService**: Service lifecycle management and configuration
- **TorController**: Control interface operations and circuit management  
- **TorBootstrapMonitor**: Bootstrap process monitoring and progress tracking
- **TorLeakChecker**: Network security and leak detection
- **TorToolManager**: Tool discovery, validation, and feature detection
- **TorUtilityFactory**: Factory pattern and utility coordination

## Test Categories

### Integration Tests
Tests that require an actual Tor process and test real functionality:

- `TorServiceIntegrationTest` - Tests Tor service lifecycle, configuration, and health monitoring
- `TorControllerIntegrationTest` - Tests control interface operations, IP changes, and circuit management
- `TorBootstrapMonitorIntegrationTest` - Tests bootstrap monitoring and progress tracking
- `TorUtilityFactoryIntegrationTest` - Tests factory coordination and utility management

### Unit Tests
Tests that focus on individual component behavior with mocked dependencies:

- `TorLeakCheckerTest` - Tests leak detection with mocked external services
- `TorToolManagerTest` - Tests tool discovery and feature detection

### Test Suite
- `TorTestSuite` - Comprehensive test runner for all Tor tests
- `TorTestUtils` - Common utilities and helpers for test setup

## Prerequisites

### System Requirements
- **Tor executable** must be available in the system (default: `/usr/bin/tor`)
- Tests are designed to run in a Docker container with all dependencies
- Sufficient network permissions for Tor to operate

### Environment Setup
The tests are configured to run in a specialized Docker container containing:
- Tor binary and dependencies
- Network isolation for secure testing
- Sufficient resources for concurrent operations

## Configuration

### System Properties
- `test.tor.executable` - Path to Tor executable (default: `/usr/bin/tor`)
- `test.tor.stress` - Enable stress testing (default: `false`)
- `test.tor.stress.iterations` - Number of stress test iterations (default: `50`)

### Example Usage
```bash
# Run with custom Tor path
mvn test -Dtest.tor.executable=/opt/tor/bin/tor

# Enable stress testing
mvn test -Dtest.tor.stress=true -Dtest.tor.stress.iterations=100
```

## Test Design Principles

### No Core Process Mocking
As per project requirements, tests **do not mock** the core Tor processes:
- Tor service lifecycle is tested with real processes
- Control interface operations use actual Tor control connections
- Bootstrap monitoring connects to real Tor instances

### External Service Mocking
External services may be mocked when necessary:
- HTTP endpoints for leak checking use MockWebServer
- IP detection services are mocked for controlled testing
- DNS resolution tests may use test doubles

### Resource Management
- Each test uses unique ports to avoid conflicts
- Temporary directories are created and cleaned up automatically
- Tor processes are properly started and stopped
- Resource cleanup is guaranteed in tearDown methods

### Performance Considerations
- Tests use reasonable timeouts for Tor operations
- Concurrent execution is tested but controlled
- Stress tests are optional and configurable
- Network operations have appropriate retry logic

## Test Structure

### Test Lifecycle
1. **Setup**: Create temporary directories, find available ports, configure Tor
2. **Execution**: Start Tor processes, perform operations, verify results
3. **Teardown**: Stop processes, clean up resources, verify cleanup

### Error Handling
- Tests gracefully handle missing Tor executable (via `Assumptions`)
- Network timeouts are handled appropriately
- Invalid configurations are tested for graceful failure
- Resource cleanup continues even if tests fail

### Test Coverage Areas

#### Core Functionality
- Service startup and shutdown
- Configuration management
- Health monitoring
- Control interface operations

#### Edge Cases
- Invalid configurations
- Network failures
- Timeout conditions
- Resource exhaustion

#### Concurrency
- Multiple simultaneous operations
- Thread safety verification
- Resource contention handling

#### Security
- Leak detection and prevention
- IP anonymization verification
- DNS leak testing
- Network isolation validation

## Running Tests

### Full Test Suite
```bash
mvn test -Dtest=TorTestSuite
```

### Individual Test Classes
```bash
# Service tests
mvn test -Dtest=TorServiceIntegrationTest

# Controller tests  
mvn test -Dtest=TorControllerIntegrationTest

# Leak checker tests
mvn test -Dtest=TorLeakCheckerTest
```

### Filtered Tests
```bash
# Only integration tests
mvn test -Dtest="*IntegrationTest"

# Only unit tests
mvn test -Dtest="TorLeakCheckerTest,TorToolManagerTest"
```

## Troubleshooting

### Common Issues

#### Tor Executable Not Found
```
Assumption failed: Tor executable not found at: /usr/bin/tor
```
**Solution**: Install Tor or set custom path via `test.tor.executable`

#### Port Conflicts
```
Address already in use
```
**Solution**: Tests use unique ports, but system conflicts may occur. Restart or check for lingering processes.

#### Network Timeouts
```
Bootstrap failed within timeout
```
**Solution**: Increase timeouts or check network connectivity. Some tests may fail in restricted network environments.

#### Permission Issues
```
Failed to create data directory
```
**Solution**: Ensure write permissions to temporary directory and sufficient disk space.

### Debug Mode
Enable debug logging for troubleshooting:
```bash
mvn test -Dtest.tor.debug=true -Dorg.slf4j.simpleLogger.defaultLogLevel=debug
```

## Test Data and Artifacts

### Temporary Files
- Tor configuration files in `/tmp/tor-test-*`
- Data directories in `/tmp/tor-*-test-*`
- Log files (when debug enabled)

### Cleanup
All temporary files and directories are automatically cleaned up after tests complete. Manual cleanup may be needed if tests are interrupted:
```bash
rm -rf /tmp/tor-test-* /tmp/tor-*-test-*
```

## Contributing

When adding new tests:

1. Follow existing naming conventions
2. Use `TorTestUtils` for common functionality
3. Ensure proper resource cleanup in `@AfterEach`
4. Add appropriate timeouts for async operations
5. Document any special requirements or assumptions
6. Test both success and failure scenarios

### Test Categories
- **Integration tests**: Require real Tor process, suffix with `IntegrationTest`
- **Unit tests**: Mock dependencies, suffix with `Test`
- **Performance tests**: Enable with system properties
- **Security tests**: Focus on anonymity and leak prevention