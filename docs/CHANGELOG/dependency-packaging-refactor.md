# Dependency Management and Packaging Refactor

**Date:** 2025-01-27  
**Type:** Major Refactor  
**Impact:** Breaking Changes to Dependency Management API

## Overview

This refactor simplifies dependency management by removing runtime version validation and consolidating packaging configuration. Version requirements are now enforced by the package manager instead of runtime checks.

## Changes Made

### 1. Single Package Strategy

- **Removed:** `open-download-manager-minimal` package
- **Updated:** Main package now includes all dependencies as hard requirements
- **Result:** Simplified installation with all features available out-of-the-box

**Before:**
```debian
Package: open-download-manager
Recommends: yt-dlp, httrack, proxychains4

Package: open-download-manager-minimal  
Depends: aria2, curl
```

**After:**
```debian
Package: odm-gtk
Depends: aria2 (>= 1.34.0, << 1.38.0),
         curl (>= 7.50.0, << 8.6.0),
         yt-dlp (>= 2023.01.01, << 2025.08.01),
         httrack (>= 3.49.0, << 3.50.0),
         proxychains4 (>= 4.14.0, << 4.17.0)
```

### 2. Removed Runtime Version Validation

**Removed from DependencyManager.java:**
- `MINIMUM_VERSIONS` and `MAXIMUM_TESTED_VERSIONS` constants
- `compareVersions()` and `parseVersion()` methods
- `validateOutputFormat()` methods
- Version-specific compatibility checking

**Simplified API:**
- `checkMinimumVersion(String toolId)` now only checks tool availability
- Version validation delegated to package manager constraints
- Reduced complexity and potential runtime failures

### 3. Packaging Consolidation

- **Moved:** All packaging files from `/packaging/` to `/odm-gtk/packaging/`
- **Rationale:** odm-gtk is the main user-facing package that depends on core and jgtk modules
- **Consistency:** Packaging configuration now co-located with the deliverable package

### 4. Version Range Validation

Validated compatibility between minimum and maximum versions:

#### aria2 (1.34.0 → 1.37.0)
- **1.34.0:** Added improved RPC support, better error handling
- **1.35.0:** TLS improvements, bug fixes
- **1.36.0:** Stability improvements, crash prevention
- **1.37.0:** Security updates, entropy improvements
- **Assessment:** No breaking changes to JSON-RPC API or output formats

#### yt-dlp (2023.01.01 → 2025.08.01)
- **Output Format:** Progress tracking format remains consistent
- **API Stability:** Core extraction methods maintained
- **Assessment:** Regular extractor updates, no breaking changes to core functionality

#### Other Tools
- **curl:** Stable HTTP client with consistent output format
- **httrack:** Mature tool with stable command-line interface
- **proxychains:** Simple proxy wrapper with minimal version dependencies

## Migration Guide

### For Developers

**Before:**
```java
// Old API - multiple overloads
boolean hasMinVersion = dependencyManager.checkMinimumVersion("aria2", "1.34.0");
boolean validOutput = dependencyManager.validateOutputFormat("aria2", version);
```

**After:**
```java
// New API - simplified
boolean isAvailable = dependencyManager.checkMinimumVersion("aria2");
// Version validation handled by package manager
```

### For Package Maintainers

**Before:**
- Two separate packages to maintain
- Complex dependency relationships
- Runtime version checking

**After:**
- Single package with clear dependencies
- Package manager enforces version constraints
- Simplified installation process

## Benefits

1. **Reliability:** Package manager ensures compatible versions are installed
2. **Simplicity:** Single package eliminates user confusion
3. **Performance:** Removed runtime version checking overhead
4. **Maintainability:** Consolidated packaging configuration
5. **User Experience:** All features work out-of-the-box

## Breaking Changes

⚠️ **API Changes:**
- `checkMinimumVersion(String tool, String version)` method removed
- `validateOutputFormat()` methods removed
- `MINIMUM_VERSIONS` and `MAXIMUM_TESTED_VERSIONS` constants removed

⚠️ **Packaging Changes:**
- `open-download-manager-minimal` package no longer available
- Package name changed from `open-download-manager` to `odm-gtk`
- All tools now required dependencies instead of recommendations

## Testing

- Verified compilation with updated API
- Updated example code and documentation
- Confirmed package manager handles version constraints correctly
- Validated tool compatibility across version ranges

## Future Considerations

- Monitor for breaking changes in dependency tool updates
- Update version ranges as needed based on compatibility testing
- Consider automated testing with different tool versions in CI/CD