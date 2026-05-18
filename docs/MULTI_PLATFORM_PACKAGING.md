# Multi-Platform Packaging Setup

## Overview

This document explains the multi-platform packaging configuration for Open Download Manager, including the purpose of `os-maven-plugin` and the exec plugin setup for building packages across different Linux distributions.

## Purpose of os-maven-plugin

The `os-maven-plugin` is a Maven extension that detects the current operating system and architecture at build time. It provides properties that can be used to conditionally execute platform-specific build steps.

### Key Benefits

1. **Automatic OS Detection**: Detects Linux, Windows, macOS, etc.
2. **Architecture Detection**: Identifies x86_64, ARM64, etc.
3. **Conditional Execution**: Skip platform-specific steps when not applicable
4. **Cross-Platform Builds**: Enable different packaging for different platforms

### Detected Properties

The plugin provides several properties:
- `os.detected.name` - Operating system (linux, windows, osx)
- `os.detected.arch` - Architecture (x86_64, aarch64, etc.)
- `os.detected.classifier` - Combined OS and architecture (linux-x86_64)

## Multi-Platform Packaging Implementation

### Configuration Structure

```xml
<properties>
    <os.maven.plugin.version>1.7.1</os.maven.plugin.version>
    <!-- OS-specific packaging properties -->
    <os.detected.classifier.skip.deb>false</os.detected.classifier.skip.deb>
    <os.detected.classifier.skip.rpm>false</os.detected.classifier.skip.rpm>
    <os.detected.classifier.skip.arch>false</os.detected.classifier.skip.arch>
</properties>

<extensions>
    <extension>
        <groupId>kr.motd.maven</groupId>
        <artifactId>os-maven-plugin</artifactId>
        <version>${os.maven.plugin.version}</version>
    </extension>
</extensions>
```

### Packaging Executions

The setup includes three main packaging targets:

#### 1. Debian Package (.deb) - build-deb

**Target Platforms**: Debian, Ubuntu, Linux Mint, and derivatives

**Execution Process**:
1. Creates proper Debian package structure in `target/deb/`
2. Copies JAR file from `odm-gtk/target/` to `/usr/share/java/`
3. Copies control files from `packaging/debian/`
4. Updates version information in control file
5. Builds package using `dpkg-deb`

**Output**: `target/open-download-manager_VERSION_all.deb`

#### 2. RPM Package (.rpm) - build-rpm

**Target Platforms**: Red Hat, Fedora, CentOS, SUSE, and derivatives

**Execution Process**:
1. Creates RPM build structure with BUILD, RPMS, SOURCES, SPECS, SRPMS
2. Copies JAR file to SOURCES directory
3. Copies and updates spec file from `packaging/rpm/`
4. Builds package using `rpmbuild`
5. Moves built RPM to target directory

**Output**: `target/open-download-manager-VERSION.rpm`

#### 3. Arch Package (.pkg.tar.xz) - build-arch-pkg

**Target Platforms**: Arch Linux, Manjaro, and derivatives

**Execution Process**:
1. Creates Arch package structure
2. Copies JAR file for packaging
3. Uses PKGBUILD from `packaging/arch/`
4. Builds with `makepkg` or creates manual tar.xz
5. Fallback creates basic package structure if makepkg unavailable

**Output**: `target/open-download-manager-VERSION-1-any.pkg.tar.xz`

## Build Commands

### Standard Build
```bash
# Compile and build JAR (no packaging)
mvn clean compile

# Build JAR and attempt all packaging formats
mvn clean package
```

### Platform-Specific Builds
```bash
# Skip specific package formats
mvn clean package -Dos.detected.classifier.skip.rpm=true -Dos.detected.classifier.skip.arch=true

# Build only Debian packages
mvn clean package -Dos.detected.classifier.skip.rpm=true -Dos.detected.classifier.skip.arch=true
```

### Manual Packaging
```bash
# Build specific formats manually
mvn exec:exec@build-deb        # Debian only
mvn exec:exec@build-rpm        # RPM only  
mvn exec:exec@build-arch-pkg   # Arch only
```

## Package Structure

### Generated Files Location
```
target/
├── open-download-manager_VERSION_all.deb          # Debian package
├── open-download-manager-VERSION.rpm              # RPM package
├── open-download-manager-VERSION-1-any.pkg.tar.xz # Arch package
├── deb/                                            # Debian build structure
├── rpm/                                            # RPM build structure
└── arch/                                           # Arch build structure
```

### Package Contents
All packages install:
- **JAR File**: `/usr/share/java/open-download-manager.jar`
- **Launcher Script**: `/usr/bin/open-download-manager`
- **Desktop Entry**: `/usr/share/applications/open-download-manager.desktop`
- **Configuration**: `/etc/odm/odm.conf`

## Packaging Files Structure

The packaging configuration files are organized by distribution:

```
packaging/
├── debian/
│   ├── control      # Package metadata and dependencies
│   ├── postinst     # Post-installation script
│   └── prerm        # Pre-removal script
├── rpm/
│   └── open-download-manager.spec  # RPM specification file
└── arch/
    └── PKGBUILD     # Arch package build script
```

## Error Handling

### Graceful Degradation
- **Missing Tools**: If packaging tools aren't available, the build continues with warnings
- **Platform Compatibility**: Non-Linux platforms skip all packaging steps
- **Fallback Options**: Arch packaging creates manual archives if `makepkg` unavailable

### Common Issues and Solutions

**dpkg-deb not found**:
```bash
# Install Debian packaging tools
sudo apt-get install dpkg-dev
```

**rpmbuild not found**:
```bash
# Install RPM development tools
sudo dnf install rpm-build  # Fedora
sudo yum install rpm-build  # RHEL/CentOS
```

**makepkg not found**:
```bash
# Install Arch development tools
sudo pacman -S base-devel
```

## Advantages of This Approach

### 1. Native Package Integration
- Proper dependency management through package managers
- System service integration
- Standard installation/removal procedures

### 2. Distribution Compatibility
- Follows each distribution's packaging standards
- Appropriate file locations and permissions
- Compatible with package managers (apt, dnf, pacman)

### 3. Automated Versioning
- Handles SNAPSHOT versions appropriately
- Updates package metadata automatically
- Maintains version consistency across formats

### 4. Cross-Platform Development
- Build packages on any Linux distribution
- OS detection prevents incompatible operations
- Conditional execution based on available tools

## Migration from odm-gtk

The packaging configuration was moved from `odm-gtk/pom.xml` to the root `pom.xml` for better organization:

### Before (odm-gtk/pom.xml)
- Packaging tied to GUI module
- JAR path: `target/${project.build.finalName}-jar-with-dependencies.jar`
- Relative paths to packaging files

### After (root pom.xml)
- Centralized packaging configuration
- JAR path: `odm-gtk/target/odm-gtk-*-jar-with-dependencies.jar`
- Absolute paths from project root

## Future Enhancements

### Potential Improvements
1. **AppImage Support**: Universal Linux application format
2. **Flatpak/Snap**: Modern Linux application packaging
3. **Multi-Architecture**: ARM64 and other architecture support
4. **Windows/macOS**: Cross-platform packaging support
5. **Repository Integration**: Automatic repository uploads

### Configuration Extensibility
The current setup can be extended by:
- Adding new packaging formats
- Supporting additional architectures
- Implementing signing and verification
- Adding metadata for software centers

## Conclusion

The multi-platform packaging setup provides comprehensive Linux distribution support while maintaining build simplicity. The `os-maven-plugin` enables intelligent platform detection, ensuring packages are built appropriately for the target environment.

This approach balances automation with flexibility, allowing developers to build packages for multiple distributions from a single build command while providing manual control when needed.