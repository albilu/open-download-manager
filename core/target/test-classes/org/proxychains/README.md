# Proxychains Test Package

This package contains comprehensive tests for the proxychains download functionality in the Open Download Manager. The tests cover unit testing, integration testing, and end-to-end testing scenarios.

## Test Structure

### Unit Tests

#### `ProxychainsConfigTest`

Tests the configuration management functionality:

-   Configuration file creation and parsing
-   Proxy entry management (add, remove, clear)
-   Chain type settings (dynamic, strict, random)
-   Timeout configuration
-   Proxy string parsing
-   Error handling for invalid configurations
-   File I/O operations

#### `ProxychainsClientTest`

Tests the core proxychains client functionality:

-   Process execution and management
-   Command construction for different scenarios
-   Progress parsing from aria2 output
-   Download lifecycle management (start, pause, resume, cancel)
-   Error handling and recovery
-   Concurrent download support
-   Resource cleanup

#### `ProxychainsDownloadHandlerTest`

Tests the download handler integration:

-   Download listener management
-   Options configuration and retrieval
-   Download state tracking
-   Composite listener functionality
-   Lifecycle management (start, pause, resume, cancel)
-   Resource cleanup and shutdown
-   Thread safety for concurrent operations

#### `ProxychainsSettingsTest`

Tests the settings configuration class:

-   Default value initialization
-   Method chaining support
-   Map conversion for option passing
-   Settings copying and inheritance
-   Integration with base DownloadSettings class
-   Edge case handling

### Integration Tests

#### `ProxychainsIntegrationTest`

Tests the interaction between components:

-   Configuration file creation and loading
-   Settings integration with download handler
-   Multiple proxy configuration scenarios
-   Download lifecycle with proper state management
-   Resource management and cleanup
-   Concurrent download handling
-   Error propagation between components

**Environment Variables:**

-   `PROXYCHAINS_AVAILABLE=true` - Run tests requiring actual proxychains installation
-   `SKIP_PROXYCHAINS_INTEGRATION=true` - Skip integration tests

### End-to-End Tests

#### `ProxychainsE2ETest`

Tests complete download workflows:

-   Full download workflow with mock web server
-   Pause and resume functionality
-   Download cancellation
-   Multiple concurrent downloads
-   Different HTTP response codes
-   Custom proxy configurations
-   Network timeout handling
-   Settings integration
-   Real network endpoint testing (optional)

**Environment Variables:**

-   `SKIP_E2E_TESTS=true` - Skip all E2E tests
-   `PROXYCHAINS_AVAILABLE=true` - Run tests requiring proxychains
-   `ENABLE_NETWORK_TESTS=true` - Run tests requiring network access

## Test Dependencies

The tests use the following frameworks and libraries:

-   **JUnit 5** - Core testing framework
-   **Mockito** - Mocking framework for unit tests
-   **Awaitility** - Asynchronous testing support
-   **MockWebServer** - HTTP server mocking for E2E tests
-   **System Lambda** - System property and environment testing
-   **Apache Commons** - Utility libraries for testing

## Running the Tests

### All Tests

```bash
mvn test
```

### Unit Tests Only

```bash
mvn test -Dtest="org.proxychains.*Test"
```

### Integration Tests Only

```bash
mvn test -Dtest="org.proxychains.*IntegrationTest"
```

### E2E Tests Only

```bash
mvn test -Dtest="org.proxychains.*E2ETest"
```

### With Proxychains Available

```bash
PROXYCHAINS_AVAILABLE=true mvn test
```

### Skip Integration Tests

```bash
SKIP_PROXYCHAINS_INTEGRATION=true mvn test
```

### Skip E2E Tests

```bash
SKIP_E2E_TESTS=true mvn test
```

## Test Configuration

### Prerequisites

For full test coverage, the following should be available:

1. **java 21+** - Required for running tests
2. **Maven 3.6+** - Build system
3. **proxychains4** (optional) - For integration and E2E tests

    ```bash
    # Ubuntu/Debian
    sudo apt-get install proxychains4

    # CentOS/RHEL
    sudo yum install proxychains-ng

    # macOS
    brew install proxychains-ng
    ```

### Mock Server Setup

The E2E tests use MockWebServer to simulate HTTP responses. No additional setup is required as the server is started automatically during test execution.

### Environment Variables

| Variable                       | Purpose                            | Default |
| ------------------------------ | ---------------------------------- | ------- |
| `PROXYCHAINS_AVAILABLE`        | Enable tests requiring proxychains | `false` |
| `SKIP_PROXYCHAINS_INTEGRATION` | Skip integration tests             | `false` |
| `SKIP_E2E_TESTS`               | Skip all E2E tests                 | `false` |
| `ENABLE_NETWORK_TESTS`         | Enable real network tests          | `false` |

## Test Scenarios Covered

### Configuration Management

-   ✅ Configuration file creation and parsing
-   ✅ Multiple proxy types (SOCKS4/5, HTTP)
-   ✅ Chain types (dynamic, strict, random)
-   ✅ Authentication handling
-   ✅ Invalid configuration handling
-   ✅ Default path detection

### Download Operations

-   ✅ Download start/stop/pause/resume
-   ✅ Progress tracking and reporting
-   ✅ Error handling and recovery
-   ✅ File cleanup on cancellation
-   ✅ Concurrent download management
-   ✅ Resource cleanup

### Network Scenarios

-   ✅ HTTP success responses (200)
-   ✅ HTTP error responses (4xx, 5xx)
-   ✅ Network timeouts
-   ✅ Connection failures
-   ✅ Large file downloads
-   ✅ Slow responses

### Integration Points

-   ✅ Settings conversion to options
-   ✅ Listener event propagation
-   ✅ State management across components
-   ✅ Thread safety
-   ✅ Resource lifecycle management

## Test Data and Fixtures

### Mock Configurations

-   Basic SOCKS5 proxy configuration
-   Multi-proxy chain configurations
-   Invalid proxy configurations for error testing
-   Authentication-enabled proxy configurations

### Mock Responses

-   Small text files for basic testing
-   Large files for timeout and cancellation testing
-   Various HTTP response codes
-   Delayed responses for timing tests

## Debugging Tests

### Verbose Logging

```bash
mvn test -Dtest="ProxychainsClientTest" -Djava.util.logging.config.file=src/test/resources/logging.properties
```

### Single Test Method

```bash
mvn test -Dtest="ProxychainsConfigTest#shouldCreateConfigWithDefaults"
```

### Test with System Properties

```bash
mvn test -Dproxychains.debug=true -Daria2.debug=true
```

## Known Limitations

1. **Proxychains Dependency**: Some tests require actual proxychains installation
2. **Network Dependencies**: E2E tests may be affected by network conditions
3. **Proxy Availability**: Tests use mock proxies that will fail connection attempts
4. **Platform Differences**: Some tests may behave differently on different operating systems

## Contributing

When adding new tests:

1. Follow the existing naming conventions
2. Use appropriate test categories (Unit/Integration/E2E)
3. Include proper documentation and comments
4. Add environment variable controls for external dependencies
5. Ensure tests are isolated and repeatable
6. Add appropriate timeout values for async operations

## Troubleshooting

### Common Issues

**Tests failing with "proxychains not found"**

-   Install proxychains4 or set `SKIP_PROXYCHAINS_INTEGRATION=true`

**Network-related test failures**

-   Check internet connectivity
-   Consider using `SKIP_E2E_TESTS=true` for offline testing

**Timeout issues**

-   Increase test timeouts if running on slow systems
-   Check for resource leaks in concurrent tests

**Mock server issues**

-   Ensure ports are available for MockWebServer
-   Check for conflicts with other running services
