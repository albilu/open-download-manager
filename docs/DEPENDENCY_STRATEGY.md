# Open Download Manager - Dependency Strategy

## Overview

Open Download Manager uses a sophisticated dependency management strategy that balances reliability, flexibility, and maintainability. This document provides the complete strategy for all external tool dependencies.

## Strategy Summary

| Tool            | Strategy            | Risk Level  | Embedded | System Fallback | Rationale                                                           |
| --------------- | ------------------- | ----------- | -------- | --------------- | ------------------------------------------------------------------- |
| **aria2**       | System-only         | 🟢 LOW      | ❌       | ✅              | Core dependency, stable JSON-RPC API, frequent security updates     |
| **curl**        | Embedded + Fallback | 🔴 HIGH     | ✅       | ✅              | Universally available, fragile version parsing                      |
| **yt-dlp**      | Embedded + Fallback | 🔴 CRITICAL | ✅       | ✅              | Rapid updates, fragile output parsing, critical for video downloads |
| **httrack**     | System-only         | 🟡 MEDIUM   | ❌       | ✅              | Stable tool, rarely updates, text-based parsing                     |
| **proxychains** | System-only         | 🟡 MEDIUM   | ❌       | ✅              | System integration required for proxy functionality                 |
| **tor**         | System-only         | 🟢 LOW      | ❌       | ✅              | Service lifecycle management, system integration required           |

## Strategy Details

### System-Only Tools

**Tools**: aria2, httrack, proxychains, tor

**Characteristics**:

-   Rely entirely on system-installed versions
-   No embedded binaries provided
-   Discovered through standard system paths
-   Updated via system package manager

**Benefits**:

-   Smaller application size
-   Automatic security updates via system
-   Better system integration
-   Reduced maintenance burden

**Discovery Order**:

1. User-configured path in settings
2. Standard system locations (`/usr/bin`, `/usr/local/bin`, etc.)
3. PATH environment variable search

### Embedded + System Fallback Tools

**Tools**: curl, yt-dlp

**Characteristics**:

-   Embedded binaries bundled in application JAR
-   Falls back to system binaries if embedded validation fails
-   Critical for application functionality

**Benefits**:

-   Guaranteed availability regardless of system state
-   Known working versions
-   Graceful degradation to system versions
-   Reduced setup complexity for users

**Resolution Order**:

1. Validated embedded binary (if available)
2. User-configured system path
3. Discovered system binary
4. Error if none found

## Implementation Architecture

### DependencyManager Class

**Core Responsibilities**:

-   Binary path resolution with fallback logic
-   Embedded binary extraction and validation
-   System binary discovery
-   Feature validation for all tools
-   Lifecycle management and cleanup

**Key Configuration**:

```java
// Strategy definition
private static final Map<String, Boolean> USE_EMBEDDED_BINARY;
static {
    Map<String, Boolean> useEmbedded = new HashMap<>();
    useEmbedded.put(ARIA2, false);      // System-only
    useEmbedded.put(CURL, true);        // Embedded + fallback
    useEmbedded.put(YT_DLP, true);      // Embedded + fallback
    useEmbedded.put(HTTRACK, false);    // System-only
    useEmbedded.put(PROXYCHAINS, false); // System-only
    useEmbedded.put(TOR, false);        // System-only
    USE_EMBEDDED_BINARY = useEmbedded;
}
```

### Embedded Binary Structure

```
core/src/main/resources/
├── curl/
│   └── bin/
│       ├── curl              # curl binary (Linux x86_64)
│       ├── trurl            # URL parsing utility
│       └── SHA256SUMS       # Checksums for verification
└── ytdlp/
    └── bin/
        └── yt-dlp_linux     # yt-dlp binary (Linux x86_64)
```

### Binary Validation Process

**Embedded Binaries**:

1. Extract to temporary directory during startup
2. Validate with `--version` command (5-second timeout)
3. Store validated paths in memory
4. Log failures and continue with system fallback

**System Binaries**:

1. Check user-configured paths first
2. Search standard system locations
3. Validate executability and basic functionality
4. Cache successful discoveries

## Tool-Specific Details

### aria2 (System-Only)

-   **Path**: `/usr/bin/aria2c`, `/usr/local/bin/aria2c`
-   **Validation**: JSON-RPC API connection test
-   **Features**: HTTP/HTTPS/FTP, BitTorrent, Metalink support
-   **Why System-Only**: Stable API, frequent security updates needed

### curl (Embedded + Fallback)

-   **Embedded Path**: `/curl/bin/curl` (extracted to temp)
-   **System Paths**: `/usr/bin/curl`, `/usr/local/bin/curl`
-   **Validation**: `curl --version` command
-   **Features**: Protocol support, SSL/TLS, proxy capabilities
-   **Why Embedded**: Universal availability, fragile version parsing

### yt-dlp (Embedded + Fallback)

-   **Embedded Path**: `/ytdlp/bin/yt-dlp_linux` (extracted to temp)
-   **System Paths**: `/usr/bin/yt-dlp`, `/usr/local/bin/yt-dlp`
-   **Validation**: `yt-dlp --version` command
-   **Features**: Video download, playlist support, format selection
-   **Why Embedded**: Critical for video downloads, rapid update cycle

### httrack (System-Only)

-   **Path**: `/usr/bin/httrack`, `/usr/local/bin/httrack`
-   **Validation**: `httrack --version` command
-   **Features**: Website mirroring, recursive download
-   **Why System-Only**: Stable tool, rarely updates

### proxychains (System-Only)

-   **Path**: `/usr/bin/proxychains4`, `/usr/bin/proxychains`
-   **Validation**: Basic execution test
-   **Features**: SOCKS4/5, HTTP proxy support
-   **Why System-Only**: Requires system integration

### tor (System-Only)

-   **Path**: `/usr/bin/tor`, `/usr/local/bin/tor`
-   **Validation**: Service availability check
-   **Features**: Anonymity network, SOCKS proxy
-   **Why System-Only**: Service lifecycle management required

## Error Handling & Recovery

### Initialization Failures

-   **Embedded Binary Issues**: Log warning, continue with system fallback
-   **System Binary Missing**: Mark tool as unavailable, continue startup
-   **Validation Timeout**: Treat as failed, try next option

### Runtime Failures

-   **Path Resolution**: Try all configured paths before failing
-   **Execution Errors**: Report to user with suggested solutions
-   **Feature Detection**: Graceful degradation for unsupported features

### Recovery Mechanisms

-   **Binary Re-discovery**: Periodic checks for newly installed tools
-   **Configuration Override**: User can specify custom paths
-   **Fallback Chains**: Multiple fallback options per tool

## Configuration Management

### User Configuration

```properties
# Override default paths
aria2.path=/opt/aria2/bin/aria2c
curl.path=/usr/local/bin/curl
yt-dlp.path=/home/user/.local/bin/yt-dlp
httrack.path=/usr/bin/httrack
proxychains.path=/usr/bin/proxychains4
tor.path=/usr/local/bin/tor

# Force system-only mode (disable embedded binaries)
use.embedded.binaries=false
```

### System Integration

-   **Package Dependencies**: All tools listed in package dependencies
-   **Service Management**: tor service handled by system init
-   **Path Discovery**: Follows FHS (Filesystem Hierarchy Standard)

## Testing & Validation

### Build-Time Validation

-   Embedded binaries included in JAR resources
-   Resource paths validated during compilation
-   Package dependencies verified in packaging files

### Runtime Validation

-   Binary existence and executability
-   Version compatibility checks
-   Feature support validation
-   Timeout protection for hanging processes

### Integration Testing

-   All tool combinations tested
-   Fallback scenarios validated
-   Error conditions handled gracefully

## Performance Considerations

### Memory Usage

-   Embedded binaries extracted to temporary directory
-   Paths cached after first discovery
-   Feature maps cached to avoid repeated validation

### Startup Time

-   Parallel validation of multiple tools
-   Cached results from previous runs
-   Lazy loading for non-critical features

### Resource Cleanup

-   Temporary files cleaned on shutdown
-   Thread pools properly terminated
-   Resource handles closed correctly

## Security Considerations

### Embedded Binaries

-   Static binaries from trusted sources
-   Checksum verification (SHA256SUMS)
-   No dynamic loading or modification

### System Binaries

-   Path validation to prevent injection
-   Execution with limited privileges
-   Timeout protection against hanging

### Network Security

-   tor integration for anonymity
-   Proxy support for network isolation
-   SSL/TLS validation for secure downloads

## Future Enhancements

### Multi-Architecture Support

-   ARM64 embedded binaries
-   Architecture detection and selection
-   Cross-platform binary management

### Automatic Updates

-   Embedded binary update mechanism
-   Version compatibility checking
-   Rollback capabilities for failed updates

### Advanced Validation

-   Cryptographic signature verification
-   Feature compatibility matrix
-   Performance benchmarking

## Troubleshooting Guide

### Common Issues

**Tool Not Found**:

1. Check system installation: `which <tool>`
2. Verify PATH environment variable
3. Set custom path in configuration

**Embedded Binary Fails**:

1. Check temporary directory permissions
2. Verify resource extraction
3. Force system fallback mode

**Version Incompatibility**:

1. Update system packages
2. Check version requirements
3. Report compatibility issues

### Debug Information

```bash
# Enable debug logging
java -Dlog.level=DEBUG -jar open-download-manager.jar

# Check embedded binaries are present
ls -la core/src/main/resources/curl/bin/curl
ls -la core/src/main/resources/ytdlp/bin/yt-dlp_linux
```

## Conclusion

The dependency strategy provides a robust foundation for external tool management while maintaining flexibility for different deployment scenarios. The combination of embedded binaries for critical tools and system integration for stable tools ensures reliable operation across various Linux distributions and configurations.

This strategy balances:

-   **Reliability**: Critical tools always available via embedded binaries
-   **Security**: System tools receive automatic security updates
-   **Flexibility**: Users can override any tool path
-   **Maintenance**: Clear separation of embedded vs system tools
-   **Performance**: Efficient discovery and caching mechanisms

The implementation is production-ready and provides a solid foundation for future enhancements and multi-platform support.
