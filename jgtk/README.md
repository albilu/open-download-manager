# JGTK - Simple GTK JNA Bindings

A simplified GTK JNA binding library designed specifically for the ODM (Open Download Manager) application. This implementation is based on the proven pattern from the working gladedemo and provides a clean, simple API for loading Glade files and handling GTK signals.

## Overview

This library provides a minimal, focused implementation for:

-   Loading Glade UI files from resources or filesystem
-   Connecting signal handlers to GTK widgets
-   Basic widget visibility control
-   GTK main loop management

## Key Features

-   **Simple API**: Clean, easy-to-use interface based on the working gladedemo pattern
-   **Resource Loading**: Load Glade files directly from classpath resources
-   **Signal Handling**: Simple callback mechanism for GTK signals
-   **Lightweight**: Minimal dependencies, just JNA for native GTK access
-   **ODM-Ready**: Designed specifically to handle ODM's Glade files

## Usage

### Basic Example (gladedemo style)

```java
import org.jgtk.GladeUI;

// Create UI and load Glade content
GladeUI ui = new GladeUI();
ui.loadFromString(gladeXmlContent);

// Register signal handlers
ui.on("on_button_clicked", (widget, data) -> {
    System.out.println("Button clicked!");
});

ui.on("on_window_destroy", (widget, data) -> {
    ui.quit();
});

// Connect signals and show the window
ui.connectSignals();
ui.showAll("main_window");

// Run the main loop
ui.run();
```

### ODM Usage Pattern

```java
import org.jgtk.GladeUI;

// Load ODM main window from resources
GladeUI ui = new GladeUI();
if (ui.loadFromResource("/glade/main-window/main-window.glade")) {

    // Register ODM-specific handlers
    ui.on("on_window_destroy", (widget, data) -> {
        // Save application state
        ui.quit();
    });

    ui.on("on_new_download_clicked", (widget, data) -> {
        // Open new download dialog
    });

    ui.on("on_settings_clicked", (widget, data) -> {
        // Open settings dialog
    });

    // Connect all signals and show the main window
    ui.connectSignals();
    ui.showAll("main_window"); // Note: ODM uses "main_window" not "main_window"

    // Run the main loop
    ui.run();
}
```

## API Reference

### Core Methods

-   `GladeUI()` - Create new instance and initialize GTK
-   `loadFromFile(String filename)` - Load Glade file from filesystem
-   `loadFromString(String content)` - Load Glade content from string
-   `loadFromResource(String path)` - Load Glade file from classpath resources
-   `on(String handlerName, GCallback callback)` - Register signal handler
-   `connectSignals()` - Connect all registered signal handlers
-   `getWidget(String id)` - Get widget pointer by ID
-   `showAll(String widgetId)` - Show widget and all children
-   `show(String widgetId)` - Show specific widget
-   `hide(String widgetId)` - Hide specific widget
-   `run()` - Start GTK main loop (blocks until quit)
-   `quit()` - Exit GTK main loop
-   `processEvents()` - Process pending GTK events without blocking
-   `destroy()` - Clean up resources

### Direct Signal Connection

```java
// Connect signal directly to widget
Pointer button = ui.getWidget("my_button");
ui.connectSignal(button, "clicked", (widget, data) -> {
    System.out.println("Button clicked directly!");
});

// Or by widget ID
ui.connectSignal("my_button", "clicked", (widget, data) -> {
    System.out.println("Button clicked by ID!");
});

### ODM Widget IDs

ODM uses specific widget IDs in its Glade files:
- Main window: `"main_window"` (not "main_window")
- Category tree: `"category_treeview"`
- Download tree: `"download_treeview"`
- Main layout: `"main_paned"`
```

## Differences from Complex Implementation

This simplified implementation removes:

-   Complex widget class hierarchy
-   Factory patterns and widget wrapping
-   GTK3/GTK4 detection complexity
-   Extensive configuration options
-   Complex resource management

Instead, it provides:

-   Direct JNA interface to GTK functions
-   Simple Pointer-based widget access
-   Straightforward signal handling
-   Minimal API surface
-   Focus on Glade file loading

## Requirements

-   java 21+
-   GTK 3.x installed on the system
-   JNA library (automatically handled by Maven)

## Dependencies

```xml
<dependency>
    <groupId>net.java.dev.jna</groupId>
    <artifactId>jna</artifactId>
    <version>5.14.0</version>
</dependency>
```

## Building

```bash
mvn clean compile
mvn test
```

## Testing

The module includes comprehensive tests that verify:

-   Basic Glade loading functionality
-   Signal handler registration
-   ODM-style usage patterns
-   Widget visibility controls
-   Resource loading
-   Lifecycle management

Run tests with:

```bash
mvn test
```

## Integration with ODM

This library is specifically designed for ODM's needs:

1. **Glade File Loading**: ODM's Glade files in `odm-gtk/src/main/resources/glade/` can be loaded using `loadFromResource()`
2. **Signal Handling**: All ODM signals defined in Glade files can be handled with the `on()` method
3. **Resource Management**: Simple lifecycle with proper cleanup
4. **Performance**: Minimal overhead, direct GTK access

### Important Notes

-   **Widget IDs**: Use ODM's actual widget IDs like `"main_window"` instead of `"main_window"`
-   **Display Environment**: Windows will only be visible in GUI environments; headless testing works but doesn't show windows
-   **GTK Warnings**: Complex Glade files may produce GTK warnings about ListStore data - these are harmless
-   **Testing**: All functionality can be tested without visible windows using the API methods

## License

This library is part of the ODM project and follows the same license terms.
