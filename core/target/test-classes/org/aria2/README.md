# Aria2 Package Tests

This directory contains comprehensive tests for the Aria2 package, covering all components and functionality with unit tests, integration tests, and end-to-end tests.

## Test Structure

### Unit Tests
- **`Aria2SettingsTest.java`** - Tests the `Aria2Settings` class in isolation
  - Configuration option validation
  - Method chaining
  - Serialization to maps and RPC options
  - Settings copying and independence
  - Edge cases and boundary conditions

- **`Aria2RpcExceptionTest.java`** - Tests the `Aria2RpcException` class
  - Exception creation and properties
  - Error code and message handling
  - Exception inheritance and throwability
  - Edge cases with null/empty values

- **`Aria2ClientTest.java`** - Tests the `Aria2Client` class with mocked dependencies
  - Process lifecycle management
  - RPC communication logic
  - WebSocket connection handling
  - Notification listener management
  - Error handling and recovery
  - Concurrent operations

### Integration Tests
- **`Aria2IntegrationTest.java`** - Tests components working together
  - Settings integration with RPC requests
  - Error handling workflows
  - Multiple concurrent operations
  - Configuration file handling
  - Proxy settings integration
  - Complete download workflows

### End-to-End Tests
- **`Aria2E2ETest.java`** - Tests complete workflows with minimal mocking
  - Full download lifecycle scenarios
  - Notification system throughout operations
  - Error recovery and retry scenarios
  - Concurrent downloads with different settings
  - WebSocket mode switching
  - Stress testing with rapid operations
  - Configuration file and proxy integration

## Test Coverage

### Aria2Settings
- ✅ Default value initialization
- ✅ All setter/getter pairs
- ✅ Method chaining
- ✅ Map serialization (`toMap()`)
- ✅ RPC options serialization (`toRpcOptions()`)
- ✅ Settings copying and independence
- ✅ Proxy configuration
- ✅ BitTorrent settings
- ✅ Edge cases and validation

### Aria2RpcException
- ✅ Exception creation with code and message
- ✅ Various error codes and messages
- ✅ Null/empty message handling
- ✅ Exception inheritance
- ✅ Throwability and catching
- ✅ toString() formatting
- ✅ Common Aria2 error codes

### Aria2Client
- ✅ Client initialization
- ✅ Process management (start/stop/restart)
- ✅ RPC communication (HTTP and WebSocket)
- ✅ Notification listener management
- ✅ Download operations (add/pause/resume/remove/status)
- ✅ Configuration handling
- ✅ Proxy settings
- ✅ Error handling and recovery
- ✅ Concurrent operations
- ✅ Resource cleanup

### Integration Scenarios
- ✅ Settings-RPC integration
- ✅ Error handling workflows
- ✅ Multiple concurrent requests
- ✅ Configuration file integration
- ✅ Download lifecycle management
- ✅ Notification system integration

### End-to-End Scenarios
- ✅ Complete download workflows
- ✅ Error recovery scenarios
- ✅ Concurrent downloads
- ✅ WebSocket connection lifecycle
- ✅ Configuration management
- ✅ Stress testing
- ✅ Various download types (HTTP/HTTPS/FTP/BitTorrent)

## Running Tests

### Prerequisites
All tests are designed to run in a Docker container with necessary dependencies. They avoid external dependencies by using:
- MockWebServer for HTTP communication testing
- Mocked processes for aria2c process management
- Temporary directories for file operations

### Unit Tests
```bash
mvn test -Dtest="org.aria2.*Test"
```

### Integration Tests
```bash
mvn test -Dtest="org.aria2.Aria2IntegrationTest"
```

### End-to-End Tests
```bash
mvn test -Dtest="org.aria2.Aria2E2ETest"
```

### All Aria2 Tests
```bash
mvn test -Dtest="org.aria2.**"
```

### With Coverage
```bash
mvn clean test jacoco:report -Dtest="org.aria2.**"
```

## Test Design Principles

### Minimal Mocking
- Unit tests mock only external dependencies (processes, network)
- Integration tests use real HTTP communication with MockWebServer
- E2E tests simulate complete workflows with minimal mocking

### Real-World Scenarios
- Tests focus on actual usage patterns
- Error conditions mirror real aria2c behavior
- Settings validation matches aria2c requirements

### Performance Considerations
- Tests use timeouts to prevent hanging
- Concurrent operations are tested for thread safety
- Resource cleanup is verified

### Edge Case Coverage
- Null/empty parameter handling
- Boundary value testing
- Error recovery scenarios
- Resource exhaustion scenarios

## Test Data and Fixtures

### Mock Responses
Tests use realistic JSON-RPC responses that match aria2c's actual format:
- Success responses with proper GID format
- Error responses with realistic error codes
- Status responses with complete download information

### Configuration Files
Tests create temporary configuration files with valid aria2 settings:
- Standard download options
- BitTorrent-specific settings
- Proxy configurations
- Custom user agents

### Test URLs
Tests use example.com URLs that are safe and won't cause actual network requests:
- Various protocols (HTTP/HTTPS/FTP)
- Different file types
- Magnet links for BitTorrent testing

## Debugging Tests

### Logging
Tests capture and can display:
- RPC request/response bodies
- Process start/stop events
- WebSocket connection lifecycle
- Notification events

### Timeouts
All long-running tests have timeouts to prevent CI/CD pipeline hanging:
- Unit tests: 5 seconds max
- Integration tests: 10 seconds max
- E2E tests: 30 seconds max

### Error Information
Failed tests provide detailed information:
- Expected vs actual values
- Mock server request/response logs
- Exception stack traces
- Test execution timeline

## Contributing

When adding new tests:

1. **Follow the existing patterns** - Use similar setup/teardown, naming conventions
2. **Add appropriate timeouts** - Prevent hanging tests
3. **Clean up resources** - Use @AfterEach for cleanup
4. **Test edge cases** - Include null/empty/boundary value tests
5. **Document complex scenarios** - Add clear test descriptions
6. **Verify independence** - Tests should not depend on execution order

### Test Categories

Use appropriate test annotations:
- `@Test` - Standard unit/integration tests
- `@ParameterizedTest` - Tests with multiple input values
- `@Timeout` - Tests that might hang
- `@DisplayName` - Clear test descriptions

### Assertions

Prefer specific assertions:
- `assertEquals(expected, actual, message)` over `assertTrue()`
- `assertThrows(Exception.class, executable)` for exception testing
- `assertDoesNotThrow()` for operations that should succeed
- `assertNotNull()` and `assertNull()` for null checks

## Known Limitations

1. **Process Testing** - Tests mock aria2c processes since actual aria2c isn't available in all test environments
2. **WebSocket Testing** - WebSocket connections are tested for lifecycle but not actual message passing
3. **Network Dependencies** - Tests avoid real network calls to ensure reliability
4. **Platform Specific** - Some process management features are tested generically

These limitations are by design to ensure tests run reliably in CI/CD environments while still providing comprehensive coverage of the aria2 package functionality.