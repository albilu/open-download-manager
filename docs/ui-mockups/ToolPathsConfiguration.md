# Tool Paths Configuration UI

This document outlines the design for a configuration user interface that allows users to customize the paths to external tools used by the download manager.

## Overview

The Tool Paths Configuration UI provides a centralized place for users to:

1. View the current status (availability and version) of all external tools
2. Customize the paths to these tools
3. Test if a specified path is valid
4. Get suggestions for installing missing tools

## UI Design

### Main Tool Paths Tab

```
+---------------------------------------------------------------------------------+
| Tool Paths Configuration                                                [×] [_] |
+---------------------------------------------------------------------------------+
| Required Tools                                                                  |
+----------------+---------------------+---------------+------------+-------------+
| Tool           | Current Path        | Status        | Version    | Actions     |
+----------------+---------------------+---------------+------------+-------------+
| aria2c         | /usr/bin/aria2c     | ✅ Available  | 1.36.0     | [Browse] [Test] |
+----------------+---------------------+---------------+------------+-------------+
|                                                                                 |
| Optional Tools                                                                  |
+----------------+---------------------+---------------+------------+-------------+
| Tool           | Current Path        | Status        | Version    | Actions     |
+----------------+---------------------+---------------+------------+-------------+
| yt-dlp         | /usr/bin/yt-dlp     | ✅ Available  | 2023.03.04 | [Browse] [Test] |
| httrack        | /usr/bin/httrack    | ✅ Available  | 3.49.2     | [Browse] [Test] |
| curl           | /usr/bin/curl       | ✅ Available  | 7.81.0     | [Browse] [Test] |
| proxychains    | /usr/bin/proxychains| ❌ Not Found  | -          | [Browse] [Test] |
+----------------+---------------------+---------------+------------+-------------+
|                                                                                 |
| [Auto-Detect] [Restore Defaults]                          [Cancel] [Save]       |
+---------------------------------------------------------------------------------+
```

### Tool Details Dialog

When a user clicks on a tool row or a dedicated "Details" button, a detailed dialog appears:

```
+---------------------------------------------------------------------------------+
| Tool Details: aria2c                                                  [×] [_]   |
+---------------------------------------------------------------------------------+
|                                                                                 |
| Name: aria2c                                                                    |
| Description: A lightweight multi-protocol & multi-source command-line download  |
|              utility. Used as the primary download engine.                      |
|                                                                                 |
| Current Path: /usr/bin/aria2c                                                   |
| Status: ✅ Available                                                            |
| Version: 1.36.0                                                                 |
|                                                                                 |
| Supported Features:                                                             |
| - HTTP/HTTPS/FTP downloads                                                      |
| - BitTorrent                                                                    |
| - Metalink                                                                      |
| - Multi-connection downloads                                                    |
|                                                                                 |
| Custom Path:                                                                    |
| [/usr/bin/aria2c                                              ] [Browse] [Test] |
|                                                                                 |
| ⚠️ This is a required tool. The download manager cannot function without it.    |
|                                                                                 |
| Installation Instructions:                                                      |
| - Debian/Ubuntu: sudo apt install aria2                                         |
| - Fedora: sudo dnf install aria2                                                |
| - Arch Linux: sudo pacman -S aria2                                              |
| - Or download from: https://aria2.github.io/                                    |
|                                                                                 |
|                                                       [Cancel] [Apply]          |
+---------------------------------------------------------------------------------+
```

### "Test" Functionality

When a user clicks the "Test" button, the system attempts to verify the tool:

```
+---------------------------------------------------+
| Testing aria2c...                                 |
+---------------------------------------------------+
|                                                   |
| Command: /usr/bin/aria2c --version                |
|                                                   |
| Output:                                           |
| aria2 version 1.36.0                              |
| Copyright (C) 2006, 2015 Tatsuhiro Tsujikawa     |
|                                                   |
| This program is free software; you can            |
| redistribute it and/or modify it under the        |
| terms of the GNU General Public License...        |
|                                                   |
| Result: ✅ Tool is working correctly!             |
|                                                   |
|                                [Close]            |
+---------------------------------------------------+
```

### Auto-Detect Functionality

When a user clicks the "Auto-Detect" button, the system scans common locations:

```
+---------------------------------------------------+
| Auto-Detecting Tools...                           |
+---------------------------------------------------+
|                                                   |
| Scanning common installation locations...         |
|                                                   |
| ✅ Found aria2c at /usr/bin/aria2c                |
| ✅ Found yt-dlp at /usr/bin/yt-dlp                |
| ✅ Found httrack at /usr/bin/httrack              |
| ✅ Found curl at /usr/bin/curl                    |
| ❌ Could not find proxychains                     |
|                                                   |
| Apply these paths?                                |
|                                                   |
|                          [Cancel] [Apply]         |
+---------------------------------------------------+
```

## Implementation Notes

1. The UI should save path configurations to the `GlobalSettings` object through the `DependencyManager`.
2. The "Test" functionality should use the `DependencyManager.isToolAvailable()` and `DependencyManager.getToolVersion()` methods.
3. The "Auto-Detect" functionality should use `DependencyManager.findToolInCommonLocations()` for each tool.
4. The UI should update tool status in real-time when paths are changed and tested.
5. A system notification should appear if a required tool becomes unavailable due to path changes.

## User Experience Considerations

1. **Error Handling**: Clear messages when a tool cannot be found or executed
2. **Installation Help**: Links to installation instructions for missing tools
3. **Path Validation**: Real-time validation of paths to ensure they are valid executables
4. **Default Restoration**: Easy way to restore default paths
5. **Status Indicators**: Clear visual indicators of tool availability status

## Accessibility

1. All UI elements should be keyboard navigable
2. Color is not the only indicator of status (icons and text are used as well)
3. Proper contrast ratios for all text elements
4. Tool tips for all controls with descriptive text