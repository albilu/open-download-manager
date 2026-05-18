# Version Constraints Implementation

This document explains the maximum version constraints implemented across all packaging formats to ensure compatibility and prevent issues with untested newer tool versions.

## Overview

Open Download Manager now enforces both **minimum** and **maximum** version constraints for all external dependencies. This prevents compatibility issues that can arise when newer versions change:

- Output formats (breaking parsing)
- Command-line interfaces (breaking automation)
- Configuration formats (breaking integration)
- Behavioral changes (breaking workflows)

## Version Ranges Enforced

| Tool | Minimum Version | Maximum Version | Reasoning |
|------|----------------|----------------|-----------|
| **aria2** | 1.34.0 | < 1.37.0 | JSON-RPC format changes in 1.37.0+ |
| **curl** | 7.50.0 | < 8.6.0 | Progress output format changes in 8.6.0+ |
| **yt-dlp** | 2023.01.01 | < 2025.01.01 | JSON schema changes in newer versions |
| **httrack** | 3.49.0 | < 3.50.0 | Directory structure changes in 3.50.0+ |
| **proxychains4** | 4.14.0 | < 4.17.0 | Configuration format changes in 4.17.0+ |

## Implementation Across Package Formats

### Debian (.deb) Packages

**File:** `packaging/debian/control`

```debian
# Version ranges using both Depends and Conflicts
Depends: aria2 (>= 1.34.0), aria2 (<< 1.37.0)
Conflicts: aria2 (<< 1.34.0), aria2 (>= 1.37.0)
```

**Behavior:**
- `<<` means "strictly less than"
- Installation **blocked** if aria2 1.33.0 or 1.37.0+ is present
- Installation **allowed** for aria2 1.34.0 through 1.36.x

### RPM Packages

**File:** `packaging/rpm/open-download-manager.spec`

```rpm
# Version ranges using Requires and Conflicts
Requires: aria2 >= 1.34.0, aria2 < 1.37.0
Conflicts: aria2 < 1.34.0, aria2 >= 1.37.0
```

**Behavior:**
- `<` means "less than"
- Same enforcement as Debian but using RPM syntax

### Arch Linux Packages

**File:** `packaging/arch/PKGBUILD`

```bash
depends=(
    'aria2>=1.34.0' 'aria2<1.37.0'
)
conflicts=(
    'aria2<1.34.0' 'aria2>=1.37.0'
)
```

**Behavior:**
- Uses pacman version comparison syntax
- Same enforcement logic as other formats

## Real-World Impact Examples

### aria2 1.37.0+ Breaking Changes
```json
// Before (1.36.x and earlier)
{
  "status": "active",
  "downloadSpeed": "1048576",
  "totalLength": "104857600"
}

// After (1.37.0+)
{
  "state": "downloading",
  "speed": {"download": 1048576},
  "length": {"total": 104857600}
}
```

### curl 8.6.0+ Progress Format Changes
```bash
# Before (8.5.x and earlier)
  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
                                 Dload  Upload   Total   Spent    Left  Speed
100 1024k  100 1024k    0     0  1024k      0  0:00:01  0:00:01 --:--:-- 1024k

# After (8.6.0+)  
  % Total    % Received    Average Speed   Time    Current
100 1024k          1024k        1024k   0:00:01      1024k
```

### yt-dlp Schema Evolution
```json
// 2023.x versions
{
  "title": "Video Title",
  "url": "https://example.com/video.mp4",
  "filesize": 1048576
}

// 2025.x versions (hypothetical)
{
  "metadata": {
    "title": "Video Title",
    "media": {
      "primary_url": "https://example.com/video.mp4",
      "size_bytes": 1048576
    }
  }
}
```

## Installation Behavior

### Successful Installation Cases
| Scenario | Result |
|----------|---------|
| Fresh system (no tools) | ✅ Installs compatible versions automatically |
| aria2 1.35.0 installed | ✅ Within range, installation proceeds |
| All tools in range | ✅ Installation succeeds |

### Blocked Installation Cases
| Scenario | Result | Solution |
|----------|---------|----------|
| aria2 1.33.0 installed | ❌ **BLOCKED** - Too old | `apt upgrade aria2` |
| aria2 1.37.0 installed | ❌ **BLOCKED** - Too new | `apt install aria2=1.36.*` |
| curl 8.6.0 installed | ❌ **BLOCKED** - Too new | `apt install curl=8.5.*` |

## Package Manager Messages

### Debian/Ubuntu Example
```bash
$ sudo apt install ./open-download-manager_0.1.0_all.deb

Reading package lists... Done
Building dependency tree... Done
Some packages could not be installed. This may mean that you have
requested an impossible situation or if you are using the unstable
distribution that some required packages have not yet been created
or been moved out of Incoming.
The following information may help to resolve the situation:

The following packages have unmet dependencies:
 open-download-manager : Depends: aria2 (>= 1.34.0) but 1.33.0-1 is to be installed
                         Depends: aria2 (<< 1.37.0) but 1.37.0-1 is to be installed
                         Conflicts: aria2 (>= 1.37.0) but 1.37.0-1 is to be installed
E: Unable to correct problems, you have held broken packages.

# Solution:
$ sudo apt install aria2=1.36.*
$ sudo apt install ./open-download-manager_0.1.0_all.deb
```

### Fedora/RHEL Example
```bash
$ sudo dnf install open-download-manager-0.1.0-1.noarch.rpm

Error: 
 Problem: conflicting requests
  - nothing provides aria2 >= 1.34.0, aria2 < 1.37.0 needed by open-download-manager-0.1.0-1.noarch
  - package aria2-1.37.0-1.fc39.x86_64 conflicts with open-download-manager-0.1.0-1.noarch

# Solution:
$ sudo dnf downgrade aria2
$ sudo dnf install open-download-manager-0.1.0-1.noarch.rpm
```

## Validation Scripts

All packages include post-installation validation scripts that:

1. **Check tool availability** (informational)
2. **Verify versions are in supported range** (warning for edge cases)
3. **Provide upgrade/downgrade instructions** if needed

### Example Validation Output
```bash
=== Open Download Manager Dependency Validation ===
✅ aria2: Version 1.35.0 (OK)
✅ curl: Version 8.4.0 (OK)  
✅ yt-dlp: Version 2024.01.15 (OK)
⚠️  httrack: Version 3.49.5 > tested 3.49.2 (may cause compatibility issues)
✅ proxychains4: Version 4.15.0 (OK)

✅ All dependencies validated successfully
🚀 Open Download Manager is ready to use!
```

## Benefits

### For Users
- **Predictable behavior** - No surprises from tool updates
- **Clear error messages** - Explicit version conflicts
- **Automatic resolution** - Package manager handles compatible versions
- **Reduced support issues** - Fewer "works on my machine" problems

### For Developers
- **Controlled testing surface** - Known tool version combinations
- **Easier debugging** - Consistent tool behavior across installations
- **Simpler maintenance** - No need to chase every tool update
- **Quality assurance** - Only ship with tested combinations

## Updating Version Constraints

When tool versions need updating:

1. **Test new versions thoroughly**
2. **Update all packaging files consistently:**
   - `packaging/debian/control`
   - `packaging/rpm/open-download-manager.spec` 
   - `packaging/arch/PKGBUILD`
3. **Update validation scripts** with new ranges
4. **Document any breaking changes** in release notes
5. **Test installation/upgrade scenarios**

## Philosophy

This approach prioritizes **stability and predictability** over having the latest tool versions. Users who need cutting-edge features can:

- Install ODM in containers with specific tool versions
- Use ODM's plugin system for custom tool integration
- Wait for ODM releases that support newer tool versions

The version constraints ensure that ODM works reliably out-of-the-box for the majority of users.