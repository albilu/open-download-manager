# Linux Packaging Simplified

## Overview

The Open Download Manager build system has been simplified to focus exclusively on **Linux x86_64** support while maintaining the ability to generate all three major Linux package formats.

## Changes Made

### 1. Removed Multi-Platform Complexity ✅

**Removed Components:**

-   `os-maven-plugin` dependency and configuration
-   OS detection properties (`os.detected.classifier.skip.*`)
-   Conditional execution based on operating system
-   Maven build extensions for OS detection

**Before:**

```xml
<!-- OS Detection for multi-platform packaging -->
<plugin>
    <groupId>kr.motd.maven</groupId>
    <artifactId>os-maven-plugin</artifactId>
    <version>${os.maven.plugin.version}</version>
</plugin>

<!-- OS-specific packaging properties -->
<os.detected.classifier.skip.deb>false</os.detected.classifier.skip.deb>
<os.detected.classifier.skip.rpm>false</os.detected.classifier.skip.rpm>
<os.detected.classifier.skip.arch>false</os.detected.classifier.skip.arch>
```

**After:**

```xml
<!-- Simple Linux packaging using exec plugin -->
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>${exec.maven.plugin.version}</version>
    <inherited>false</inherited>
    <!-- Direct executions without OS detection -->
</plugin>
```

### 2. Simplified Packaging Executions ✅

**Key Improvements:**

-   **No conditional skipping** - All packaging formats run by default
-   **Inheritance disabled** - Packaging only runs in root module (`<inherited>false</inherited>`)
-   **Runs in install phase** - Ensures all JARs are built before packaging
-   **Linux-focused** - No cross-platform complexity

**Packaging Executions:**

1. **`build-deb`** - Generates Debian/Ubuntu `.deb` packages
2. **`build-rpm`** - Generates Red Hat/Fedora `.rpm` packages
3. **`build-arch-pkg`** - Generates Arch Linux `.pkg.tar.xz` packages

### 3. Build Process

**Standard Build:**

```bash
mvn clean package -DskipTests
```

**Full Build with Packaging:**

```bash
mvn clean install -DskipTests
```

**Generated Artifacts:**

```
target/
├── open-download-manager_0.1.0~snapshot_all.deb     # Debian package
├── open-download-manager-0.1.0-SNAPSHOT-1.rpm       # RPM package (if rpmbuild available)
└── open-download-manager-0.1.0-SNAPSHOT-1-any.pkg.tar.xz  # Arch package
```

### 4. Packaging Structure

**Packaging Files Location:**

```
packaging/
├── debian/
│   ├── control      # Debian package metadata
│   ├── postinst     # Post-installation script
│   └── prerm        # Pre-removal script
├── rpm/
│   └── open-download-manager.spec  # RPM spec file
└── arch/
    └── PKGBUILD     # Arch Linux build script
```

**Main Application JAR:**

-   Source: `odm-gtk/target/odm-gtk-*-jar-with-dependencies.jar`
-   Target: `/usr/share/java/open-download-manager.jar`

### 5. Benefits of Simplification

✅ **Reduced Complexity** - No OS detection logic or conditional execution  
✅ **Faster Builds** - No unnecessary plugin overhead  
✅ **Easier Maintenance** - Single platform focus  
✅ **All Formats Available** - Still generates .deb, .rpm, and .pkg packages  
✅ **Linux x86_64 Focus** - Optimized for target platform

### 6. System Requirements

**Build Requirements:**

-   java 21 or higher
-   Maven 3.6+
-   Linux x86_64 system

**Optional Packaging Tools:**

-   `dpkg-deb` - For .deb package generation
-   `rpmbuild` - For .rpm package generation
-   `makepkg` - For Arch .pkg package generation

**Note:** If packaging tools are not available, the build will continue gracefully with fallback mechanisms.

### 7. Future Considerations

-   **Single Platform Focus** - Only Linux x86_64 supported
-   **Simplified Deployment** - Easy to add CI/CD integration
-   **Maintainable Configuration** - Clear and concise Maven setup
-   **Extensible** - Easy to add new Linux distribution support

## Usage

The simplified configuration makes building and packaging straightforward:

```bash
# Build application
mvn clean package -DskipTests

# Build and create Linux packages
mvn clean install -DskipTests

# Skip javadoc generation if needed
mvn clean install -DskipTests -Dmaven.javadoc.skip=true
```

All Linux package formats (.deb, .rpm, .pkg) will be generated automatically in the `target/` directory.
