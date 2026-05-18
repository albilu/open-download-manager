# Enhanced Dependency Management System

This document describes the comprehensive dependency management system implemented for Open Download Manager, which ensures reliable operation through strict version control, auto-discovery, and feature detection.

## Overview

The Open Download Manager relies on several external tools for its functionality:

- **aria2** - Multi-connection downloads, BitTorrent, and magnet links
- **curl** - HTTP/HTTPS/FTP downloads with proxy support
- **yt-dlp** - Video downloads from YouTube and other platforms
- **httrack** - Website mirroring capabilities
- **proxychains4** - Proxy and Tor routing

The dependency management system addresses the critical challenge of **output parsing fragility** by enforcing specific version ranges and validating output format compatibility.

## System Components

### 1. Enhanced DependencyManager (`core/src/main/java/org/manager/DependencyManager.java`)

**Key Features:**
- **Auto-discovery**: Searches common paths and PATH environment
- **Version validation**: Enforces minimum and maximum tested versions
- **Output format validation**: Tests parsing compatibility
- **Feature detection**: Probes actual tool capabilities
- **Runtime monitoring**: Tracks tool availability during execution

**Version Requirements:**
```java
// Minimum versions (must be met)
aria2 >= 1.34.0
curl >= 7.50.0
yt-dlp >= 2023.01.01
httrack >= 3.49.0
proxychains4 >= 4.14.0

// Maximum tested versions (warnings if exceeded)
aria2 <= 1.37.0
curl <= 8.5.0
yt-dlp <= 2024.01.01
httrack <= 3.49.2
proxychains4 <= 4.16.0
```

### 2. Package Manager Integration

#### Debian/Ubuntu (`packaging/debian/`)
- **control**: Strict version dependencies with conflicts
- **postinst**: Post-installation validation script
- Automatic dependency installation via `apt`

#### RPM/Fedora (`packaging/rpm/`)
- **spec file**: Version requirements and conflicts
- **%post section**: Post-installation validation
- Support for `dnf`/`yum` package managers

#### Arch Linux (`packaging/arch/`)
- **PKGBUILD**: Dependency specifications
- **post_install()**: Validation hook
- Integration with `pacman`

### 3. Development Tools

#### Validation Script (`scripts/validate-dependencies.sh`)
Comprehensive dependency validation for development use:

```bash
# Validate all dependencies
./scripts/validate-dependencies.sh

# Validate specific tool
./scripts/validate-dependencies.sh --tool aria2

# Verbose mode
./scripts/validate-dependencies.sh --verbose
```

## Critical Design Decisions

### Why System Dependencies Instead of Embedded Binaries?

**Decision**: Use system dependencies with strict version control

**Reasoning**:
1. **Smaller package size** (20MB vs 40-80MB)
2. **Automatic security updates** from distribution
3. **Linux ecosystem compatibility**
4. **Repository inclusion friendly**

**Risk Mitigation**:
- Strict version ranges prevent breaking changes
- Output format validation catches incompatibilities
- Package manager conflicts prevent problematic versions
- Post-installation validation ensures compatibility

### Output Parsing Fragility Solution

**Problem**: External tools frequently change output formats, breaking progress tracking

**Solution**: Multi-layered protection
1. **Version ranges**: Test and lock compatible versions
2. **Output validation**: Test parsing on installation
3. **Breaking version detection**: Block known problematic versions
4. **Runtime monitoring**: Detect tool changes during execution

## Version Strategy

### Minimum Versions
- Based on **first stable release** with required features
- **Progress tracking compatibility** verified
- **Critical bug fixes** included

### Maximum Tested Versions
- **Latest version tested** with current parsing code
- Versions above this generate **warnings** but don't block
- Updated with each release cycle

### Breaking Version Detection
```java
// Example: yt-dlp versions with output format changes
String[] breakingVersions = {
    "2023.12.30", // Changed progress format
    "2023.11.14", // Modified download output  
    "2023.10.13"  // ETA format changes
};
```

## Installation Workflow

### Package Installation
1. **Package manager installs** ODM with dependencies
2. **Version conflicts** prevent incompatible tools
3. **Post-install script** validates all dependencies
4. **Feature detection** probes capabilities
5. **Setup completion** with success/failure report

### Development Setup
1. **Clone repository**
2. **Run validation script**: `./scripts/validate-dependencies.sh`
3. **Install missing tools** using provided instructions
4. **Verify compatibility** before development

## Feature Detection

### Aria2 Features
- **RPC support**: Required for progress tracking
- **WebSocket support**: Enhanced real-time communication
- **BitTorrent support**: Torrent and magnet downloads
- **Protocol support**: HTTP/HTTPS/FTP validation

### Curl Features
- **Protocol support**: HTTPS, FTP, FTPS detection
- **SSL/TLS support**: Secure connection capability
- **Progress format**: Compatible output validation
- **Proxy support**: SOCKS/HTTP proxy detection

### YT-DLP Features
- **Format selection**: Video quality options
- **Playlist support**: Batch video downloads
- **Extractor support**: Platform compatibility
- **Progress tracking**: Output format validation

### HTTrack Features
- **Recursive download**: Website mirroring depth
- **Robots.txt support**: Ethical crawling
- **HTTPS support**: Secure site mirroring

### Proxychains Features
- **SOCKS4/5 support**: Proxy protocol compatibility
- **HTTP proxy support**: Web proxy routing
- **DNS through proxy**: Privacy protection
- **Version detection**: proxychains4 vs legacy

## Error Handling and Recovery

### Validation Failures
- **Clear error messages** with specific requirements
- **Installation instructions** per distribution
- **Fallback suggestions** for missing optional tools
- **Partial functionality** when possible

### Runtime Issues
- **Graceful degradation** for optional tools
- **Error recovery** for temporary failures
- **User notifications** for tool unavailability
- **Fallback mechanisms** between tools

## Monitoring and Maintenance

### Version Monitoring
- **Changelog review** for new tool versions
- **Output format testing** before version updates  
- **Breaking change detection** in tool updates
- **Compatibility matrix** maintenance

### Update Process
1. **New tool version released**
2. **Download and test** with current parsing code
3. **Update maximum tested version** if compatible
4. **Add to breaking versions** if incompatible
5. **Release ODM update** with new version ranges

## Usage Examples

### Basic Dependency Check
```java
DependencyManager manager = new DependencyManager(settings);
boolean ready = manager.checkRequiredDependencies();

if (!ready) {
    // Show installation instructions
    Map<String, Object> report = manager.getDependencyReport();
    displayInstallationGuide(report);
}
```

### Feature-Based Validation
```java
// Check if video downloads are available
if (manager.isToolAvailable("yt-dlp")) {
    Map<String, Boolean> features = manager.checkYtDlpFeatures();
    boolean canDownloadVideos = features.get("video_download");
    
    if (!canDownloadVideos) {
        disableVideoDownloads();
    }
}
```

### Auto-Discovery
```java
// Find tools in common locations
String aria2Path = manager.discoverToolPath("aria2");
if (aria2Path != null) {
    logger.info("Found aria2 at: " + aria2Path);
    manager.setToolPath("aria2", aria2Path);
}
```

## Troubleshooting

### Common Issues

**Tool not found despite being installed**
- Check PATH environment variable
- Verify tool in common locations
- Use `--tool` flag for specific validation

**Version too old**
- Update distribution packages
- Install from upstream sources
- Check minimum version requirements

**Output parsing broken**
- Version may be too new (above tested range)
- Check for known breaking versions
- File issue with tool version details

### Debug Information

**Enable verbose logging**:
```bash
./scripts/validate-dependencies.sh --verbose
```

**Check specific tool**:
```bash
./scripts/validate-dependencies.sh --tool yt-dlp
```

**View validation log**:
```bash
tail -f /tmp/odm-dependency-validation.log
```

## Future Enhancements

### Planned Features
- **Automatic tool updates** with compatibility testing
- **Tool version notifications** for new releases
- **Custom validation rules** for specific use cases
- **Integration testing** with CI/CD pipelines

### Monitoring Improvements
- **Real-time tool monitoring** during runtime
- **Performance impact assessment** of tool versions
- **User feedback collection** on tool compatibility
- **Automated breaking change detection**

## Contributing

When adding new external tool dependencies:

1. **Add version requirements** to `DependencyManager.java`
2. **Create feature detection** methods
3. **Add output validation** for parsing-critical tools
4. **Update package specifications** (deb/rpm/arch)
5. **Test across distributions** and versions
6. **Document breaking changes** and compatibility

See the development guide for detailed contribution instructions.