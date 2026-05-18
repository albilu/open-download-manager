# Embedded Binaries Strategy

This document explains how Open Download Manager handles embedded binaries with system fallback.

## Overview

ODM uses a hybrid approach for managing external tool dependencies:
- **Embedded binaries**: Critical tools are bundled with the application
- **System fallback**: Falls back to system-installed versions when embedded binaries fail
- **System-only tools**: Some tools are always used from the system

## Strategy by Tool

Based on the analysis in `deps-management`, each tool follows a specific strategy:

| Tool        | Strategy                    | Rationale                                           |
|-------------|-----------------------------|----------------------------------------------------|
| **aria2**   | System-only                 | Stable JSON-RPC API, frequent updates needed      |
| **curl**    | Embedded + System fallback  | Universal availability, fragile version parsing   |
| **yt-dlp**  | Embedded + System fallback  | Critical updates, fragile output parsing          |
| **httrack** | System-only                 | Stable tool, rarely updates                       |
| **proxychains** | System-only             | System integration required                        |
| **tor**     | System-only                 | Service lifecycle management, system integration  |

## Embedded Binary Locations

Embedded binaries are stored in the JAR resources:

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

## How It Works

### 1. Initialization

When `DependencyManager` starts:
1. Extracts embedded binaries to temporary directory
2. Validates each binary with `--version` check
3. Stores paths to validated binaries in `embeddedBinaryPaths`

### 2. Tool Path Resolution

When a tool path is requested via `getToolPath(toolId)`:

1. **Check embedded binary** (if strategy allows):
   - Look for validated embedded binary path
   - If found and executable, return embedded path
   
2. **System fallback**:
   - Check configured system path in settings
   - If valid and executable, return system path
   
3. **Discovery fallback**:
   - Search common system locations
   - Update settings if found
   
4. **Failure**:
   - Throw `DependencyException` if no valid binary found

### 3. Validation

Each embedded binary is validated during initialization:
- **Executable check**: File exists and has execute permissions
- **Version check**: Binary responds to `--version` command
- **Timeout protection**: Version check times out after 5 seconds

## Java Implementation

### Key Classes

- `DependencyManager`: Main class handling binary management
- `DependencyValidator`: Validates tool requirements and features
- `GlobalSettings`: Stores tool paths and availability

### Configuration

The embedded binary strategy is defined in `DependencyManager`:

```java
// Tools that use embedded binaries with system fallback
private static final Map<String, Boolean> USE_EMBEDDED_BINARY;
static {
    Map<String, Boolean> useEmbedded = new HashMap<>();
    useEmbedded.put(CURL, true);        // Embedded with fallback
    useEmbedded.put(YT_DLP, true);      // Embedded with fallback
    useEmbedded.put(ARIA2, false);      // System-only
    useEmbedded.put(HTTRACK, false);    // System-only
    useEmbedded.put(PROXYCHAINS, false); // System-only
    useEmbedded.put(TOR, false);        // System-only
    USE_EMBEDDED_BINARY = useEmbedded;
}
```

## Benefits

### Reliability
- **Guaranteed availability**: Critical tools are always available
- **Version consistency**: Known working versions bundled
- **Reduced user setup**: No manual tool installation required

### Flexibility
- **System integration**: Can use newer system versions when available
- **User customization**: Users can specify custom tool paths
- **Graceful degradation**: Falls back to system tools if embedded fails

### Maintenance
- **Controlled updates**: Embedded binaries updated with releases
- **Feature validation**: Existing feature checking validates all binaries
- **Simple management**: No complex multi-platform binary management needed

## Packaging Impact

### Debian/Ubuntu Packages
- Dependencies in `debian/control` remain as recommendations
- Embedded binaries provide fallback when system packages unavailable
- Package size increases but provides better user experience

### Build Process
- Embedded binaries included in JAR during Maven build
- Binary validation happens at runtime, not build time
- Packaging uses existing debian/rpm structures in `packaging/` directory

## Troubleshooting

### Common Issues

**Embedded binary validation fails**:
- Check if binary files exist in resources
- Verify execute permissions after extraction
- Check temporary directory creation permissions

**System fallback not working**:
- Verify system tool is installed and in PATH
- Check tool version compatibility
- Review `GlobalSettings` configuration

### Debugging

Enable debug logging to see binary selection process:
```java
Logger.getLogger(DependencyManager.class.getName()).setLevel(Level.FINE);
```

### Manual Override

Users can force system tool usage by setting paths in configuration:
```properties
# Force system curl usage
curl.path=/usr/bin/curl

# Force system yt-dlp usage  
yt-dlp.path=/usr/local/bin/yt-dlp

# Force system tor usage
tor.path=/usr/bin/tor
```

## Future Considerations

### Multi-Architecture Support
- Current embedded binaries are Linux x86_64 only
- Could be extended to support ARM64, other architectures
- Would require architecture detection and multiple binary sets

### Automatic Updates
- Embedded binaries are static until application update
- Future versions could include update mechanisms
- Would need careful validation and rollback capabilities

### Binary Verification
- Current validation is basic (version check only)
- Could add cryptographic signature verification
- Would improve security but increase complexity