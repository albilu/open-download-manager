# Dependency Validation Features

This document describes the new dependency validation features added to the Open Download Manager core module.

## Overview

The dependency validation system provides comprehensive checking of external tool requirements, including:

- **Minimum version validation** for aria2c, curl, yt-dlp, and other dependencies
- **Protocol support detection** for aria2c (HTTP/HTTPS, FTP, BitTorrent, Metalink)
- **Feature capability checking** for curl (SSL/TLS, proxy support, compression, etc.)
- **High-level validation methods** for common use cases
- **Detailed reporting** of dependency status and capabilities

## Key Components

### DependencyManager (Enhanced)

The existing `DependencyManager` class has been enhanced with new methods:

#### Version Checking Methods
- `checkMinimumVersion(String toolId)` - Check against built-in minimum requirements
- `checkMinimumVersion(String toolId, String minimumVersion)` - Check against custom version
- `compareVersions(String version1, String version2)` - Internal semantic version comparison

#### Feature Detection Methods
- `checkAria2Features()` - Detect aria2c protocol and feature support
- `checkCurlFeatures()` - Detect curl protocol and feature support
- `checkAria2Requirements(List<String> protocols, List<String> features)` - Validate requirements
- `checkCurlRequirements(List<String> protocols, List<String> features)` - Validate requirements

#### Reporting Methods
- `getDependencyReport()` - Generate comprehensive dependency status report

### DependencyValidator (New)

A new utility class that provides high-level validation methods:

#### Core Validation Methods
- `validateCoreRequirements()` - Check essential dependencies for basic functionality
- `validateAll()` - Comprehensive validation of all dependencies
- `meetsMinimumRequirements()` - Quick check for minimum system requirements

#### Use Case Validation Methods
- `validateBitTorrentSupport()` - Check BitTorrent download capabilities
- `validateSecureDownloads()` - Check HTTPS/SSL download capabilities
- `validateVideoDownloading()` - Check yt-dlp video download capabilities
- `validateWebsiteScraping()` - Check httrack website scraping capabilities
- `validateProxySupport()` - Check proxychains proxy capabilities

#### Protocol-Specific Validation
- `validateAria2Protocols(String... protocols)` - Check aria2c protocol support
- `validateCurlProtocols(String... protocols)` - Check curl protocol support

### ValidationResult (New)

A structured result class containing:
- `isValid()` - Overall validation status
- `getErrors()` - List of critical errors
- `getWarnings()` - List of warnings
- `getDetails()` - Detailed validation information
- `toString()` - Human-readable summary

## Built-in Requirements

The system enforces these minimum version requirements:

| Tool    | Minimum Version | Reason                              |
|---------|----------------|-------------------------------------|
| aria2c  | 1.34.0         | RPC and WebSocket support          |
| curl    | 7.50.0         | Modern protocol and security features |
| yt-dlp  | 2023.01.01     | Up-to-date video site compatibility |

## Supported Protocols and Features

### aria2c
- **Protocols**: HTTP, HTTPS, FTP, BitTorrent, Metalink
- **Features**: RPC, WebSocket, checksum verification, proxy support, resume downloads

### curl  
- **Protocols**: HTTP, HTTPS, FTP, FTPS, SFTP, and many others
- **Features**: SSL/TLS, proxy support, compression (gzip, brotli), IPv6, resume downloads

## Usage Examples

### Basic Validation

```java
// Create dependency manager and validator
GlobalSettings settings = new GlobalSettings();
DependencyManager dependencyManager = new DependencyManager(settings);
DependencyValidator validator = new DependencyValidator(dependencyManager);

// Check if system meets minimum requirements
if (validator.meetsMinimumRequirements()) {
    System.out.println("✓ System ready for downloads");
} else {
    System.out.println("✗ System missing required dependencies");
}
```

### Detailed Validation

```java
// Perform comprehensive validation
DependencyValidator.ValidationResult result = validator.validateAll();

if (result.isValid()) {
    System.out.println("✓ All validations passed");
} else {
    System.out.println("✗ Validation issues found:");
    
    for (String error : result.getErrors()) {
        System.out.println("  Error: " + error);
    }
    
    for (String warning : result.getWarnings()) {
        System.out.println("  Warning: " + warning);
    }
}

// Get formatted summary
String summary = DependencyValidator.getSummary(result);
System.out.println(summary);
```

### Feature-Specific Validation

```java
// Check BitTorrent support
ValidationResult torrentResult = validator.validateBitTorrentSupport();
if (torrentResult.isValid()) {
    // Enable BitTorrent downloads
    enableBitTorrentFeature();
}

// Check secure download support
ValidationResult secureResult = validator.validateSecureDownloads();
if (secureResult.isValid()) {
    // Enable HTTPS downloads
    enableSecureDownloads();
}

// Check specific protocols
ValidationResult protocolResult = validator.validateAria2Protocols("http", "https", "bittorrent");
if (protocolResult.isValid()) {
    // All required protocols supported
    System.out.println("✓ All protocols supported");
}
```

### Version Checking

```java
// Check minimum versions
boolean aria2Ok = dependencyManager.checkMinimumVersion(DependencyManager.ARIA2);
boolean curlOk = dependencyManager.checkMinimumVersion(DependencyManager.CURL);

// Check custom version requirements
boolean hasRpcSupport = dependencyManager.checkMinimumVersion(DependencyManager.ARIA2, "1.34.0");
boolean hasModernCurl = dependencyManager.checkMinimumVersion(DependencyManager.CURL, "7.60.0");
```

### Feature Detection

```java
// Get aria2c capabilities
Map<String, Boolean> aria2Features = dependencyManager.checkAria2Features();
boolean supportsBitTorrent = aria2Features.getOrDefault("bittorrent", false);
boolean supportsRpc = aria2Features.getOrDefault("rpc", false);

// Get curl capabilities  
Map<String, Boolean> curlFeatures = dependencyManager.checkCurlFeatures();
boolean supportsHttps = curlFeatures.getOrDefault("https_supported", false);
boolean supportsSsl = curlFeatures.getOrDefault("ssl_supported", false);
```

### Dependency Reporting

```java
// Generate comprehensive report
Map<String, Object> report = dependencyManager.getDependencyReport();

// Check overall status
boolean allRequirementsMet = (Boolean) report.get("all_requirements_met");

// Get detailed tool information
@SuppressWarnings("unchecked")
Map<String, Map<String, Object>> tools = 
    (Map<String, Map<String, Object>>) report.get("tools");

for (Map.Entry<String, Map<String, Object>> entry : tools.entrySet()) {
    String toolId = entry.getKey();
    Map<String, Object> toolInfo = entry.getValue();
    
    System.out.printf("%s: available=%s, version=%s%n",
        toolId,
        toolInfo.get("available"),
        toolInfo.get("version")
    );
}
```

## Error Handling

The validation system is designed to handle errors gracefully:

- Methods return `false` or `null` for missing tools rather than throwing exceptions
- Validation results include detailed error and warning information
- Internal errors are logged but don't interrupt the validation process
- Timeouts prevent hanging on unresponsive tools

## Integration Points

### Download Manager Integration

```java
// In DownloadManager initialization
DependencyValidator validator = new DependencyValidator(dependencyManager);

if (!validator.meetsMinimumRequirements()) {
    throw new IllegalStateException("System does not meet minimum requirements");
}

// Enable features based on capabilities
if (validator.validateBitTorrentSupport().isValid()) {
    enableBitTorrentDownloads();
}

if (validator.validateVideoDownloading().isValid()) {
    enableVideoDownloads();
}
```

### UI Integration

```java
// In UI setup
ValidationResult result = validator.validateAll();

// Show status in UI
updateDependencyStatus(result.isValid());

// Display warnings to user
if (result.hasWarnings()) {
    showWarningsDialog(result.getWarnings());
}

// Generate capability report for settings dialog
Map<String, Object> report = dependencyManager.getDependencyReport();
populateCapabilityTable(report);
```

## Testing

The system includes comprehensive tests in `DependencyValidationTest.java` that verify:

- Basic dependency availability checking
- Version requirement validation
- Feature detection for aria2c and curl
- Protocol requirement validation
- Error handling with invalid inputs
- Validation result formatting

## Performance Considerations

- Tool availability and version information is cached to avoid repeated system calls
- Feature detection results are cached per session
- Validation operations include timeouts to prevent hanging
- Async validation methods are available for UI responsiveness

## Future Enhancements

Potential improvements for future versions:

1. **Auto-discovery**: Automatically find tools in common installation locations
2. **Installation guidance**: Provide specific installation instructions for missing tools
3. **Feature fallbacks**: Automatically configure fallback options when features are missing
4. **Real-time monitoring**: Monitor for tool installation/removal during runtime
5. **Custom validation rules**: Allow applications to define custom validation requirements

## Related Files

- `DependencyManager.java` - Core dependency management (enhanced)
- `DependencyValidator.java` - High-level validation utilities (new)
- `DependencyValidationExample.java` - Usage examples and demonstrations
- `DependencyValidationTest.java` - Comprehensive test suite
- `docs/DEPENDENCY_VALIDATION.md` - Detailed usage guide

## Migration Notes

For existing code using `DependencyManager`:

- All existing methods remain unchanged and fully compatible
- New validation methods are additive and optional
- No breaking changes to existing APIs
- Enhanced error handling improves robustness

Applications can gradually adopt the new validation features without modifying existing dependency checking code.