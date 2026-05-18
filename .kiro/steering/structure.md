# Project Structure

## Module Organization

### Root Level

-   **Multi-module Maven project** with parent POM
-   **Docker-based development** environment
-   **Makefile** for common development tasks

### Core Modules

#### `core/` - Business Logic Module

```
core/src/main/java/org/
├── aria2/          # Aria2 RPC client and integration
├── curl/           # cURL wrapper and utilities
├── httrack/        # HTTrack website scraping
├── manager/        # Core application framework
│   ├── clipboard/  # Clipboard monitoring
│   ├── download/   # Download management
│   ├── folder/     # Folder monitoring
│   ├── proxy/      # Proxy management
│   └── schedule/   # Download scheduling
├── proxychains/    # Proxychains integration
├── tor/            # Tor service integration
└── ytdlp/          # yt-dlp video downloads
```

#### `jgtk/` - GTK Bindings Library

```
jgtk/src/main/java/org/jgtk/
├── core/           # GTK initialization and native libraries
├── service/        # Resource loading, signal management, widgets
└── widget/         # Specialized widget services
```

#### `odm-gtk/` - GTK User Interface

```
odm-gtk/src/main/java/org/odm/
└── ui/             # GTK UI controllers and dialogs

odm-gtk/src/main/resources/
├── glade/          # Glade UI definition files
│   ├── main-window/
│   ├── settings/
│   ├── new/
│   └── import-from/
└── images/         # Application icons and assets
```

## Architecture Patterns

### Factory Pattern

-   `ApplicationFactory`: Singleton factory for core services
-   `DownloadManagerFactory`: Download manager creation
-   `ToolManagerFactory`: External tool management

### Service Layer

-   Clear separation between UI and business logic
-   Services registered with `ApplicationFactory` for lifecycle management
-   Dependency injection through factory pattern

### Settings Management

-   `GlobalSettings`: Application-wide configuration
-   Tool-specific settings classes (e.g., `Aria2Settings`, `YtDlpSettings`)
-   Settings persistence and validation

### Resource Management

-   Embedded binaries in `src/main/resources/`
-   Glade UI files organized by dialog/window
-   Proper cleanup and shutdown coordination

## File Naming Conventions

### Java Classes

-   **Interfaces**: Descriptive names (e.g., `DownloadManager`, `ClipboardService`)
-   **Implementations**: Interface name + `Impl` suffix
-   **Factories**: Service name + `Factory` suffix
-   **Settings**: Service name + `Settings` suffix

### Glade Files

-   **Pattern**: `feature-name.glade` (e.g., `main-window.glade`, `settings.glade`)
-   **Location**: `odm-gtk/src/main/resources/glade/feature-name/`
-   **Backup files**: `.glade~` suffix (should be gitignored)

### Test Files

-   **Unit tests**: `ClassNameTest.java`
-   **Integration tests**: `ClassNameIntegrationTest.java`
-   **E2E tests**: `ClassNameE2ETest.java`

## Package Organization

### Core Packages

-   `org.manager`: Core application framework
-   `org.aria2`, `org.curl`, etc.: External tool integrations
-   Each tool package contains: Client, Settings, ToolManager classes

### UI Packages

-   `org.odm.ui`: GTK UI implementation
-   `org.jgtk`: GTK bindings library

### Resource Structure

-   Configuration files in `src/main/resources/`
-   Embedded binaries in tool-specific subdirectories
-   UI resources (Glade, images) in `odm-gtk` module only
