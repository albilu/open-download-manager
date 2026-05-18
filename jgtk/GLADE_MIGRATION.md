# JGTK Glade Migration Guide

This document outlines the migration of the JGTK library from a widget-creation-focused approach to a **Glade-first development model**.

## Overview of Changes

The JGTK library has been refactored to prioritize Glade-based UI development over programmatic widget creation. This shift provides better separation of concerns, easier UI design, and more maintainable applications.

## Key Philosophy Change

### Before: Widget Creation Focus
```java
// Old approach - programmatic widget creation
GtkWindow window = new GtkWindow(GtkWindow.WindowType.TOPLEVEL);
window.setTitle("My App");
window.setDefaultSize(400, 300);

GtkBox box = new GtkBox(GtkBox.Orientation.VERTICAL, 10);
GtkButton button = new GtkButton("Click Me");
button.setOnClickListener(this::handleClick);

box.packStart(button, false, false, 0);
window.add(box);
window.showAll();
```

### After: Glade-First Approach
```java
// New approach - Glade-based development
GladeLoader loader = GladeUtils.builder()
    .fromFile("my_app.glade")
    .onButtonClick("my_button", this::handleClick)
    .build();
```

## Major Changes Made

### 1. Enhanced GladeLoader

**New Features:**
- Improved signal management with centralized `SignalManager`
- Fluent builder API for configuration
- Automatic widget discovery and caching
- Resource loading from classpath
- Comprehensive error handling and validation

**New Methods:**
- `connectSignal()` - Connect individual signals
- `connectSignals()` - Batch signal connection
- `getSignalManager()` - Access to advanced signal management
- `showMainWindow()` - Automatic main window detection and display
- `clearCache()` - Widget cache management

### 2. New SignalManager Class

**Purpose:** Centralized signal handling system

**Features:**
- Fluent signal connection API
- Support for different signal types (simple, with events, extended)
- Batch signal operations
- Safe disconnection and cleanup
- Connection flags support (after, swapped)

**Usage Example:**
```java
SignalManager signalManager = loader.getSignalManager();

// Simple connection
signalManager.connect()
    .widget(button)
    .signal("clicked")
    .connect(this::handleButtonClick);

// Batch connections
Map<SignalManager.WidgetSignalPair, SignalManager.WidgetSignalHandler> connections = Map.of(
    new SignalManager.WidgetSignalPair(button1, "clicked"), this::handleButton1,
    new SignalManager.WidgetSignalPair(button2, "clicked"), this::handleButton2
);
signalManager.connectMultiple(connections);
```

### 3. New GladeUtils Class

**Purpose:** Utility methods and convenience functions for common Glade patterns

**Key Features:**
- Fluent builder API (`GladeUtils.builder()`)
- Quick-start methods (`quickLoad()`, `quickLoadResource()`)
- Batch signal connection utilities
- Glade file validation and discovery
- Common signal handler factories

**Usage Examples:**
```java
// Quick application setup
GladeLoader loader = GladeUtils.quickLoad("main.glade");

// Fluent configuration
GladeLoader loader = GladeUtils.builder()
    .fromResource("/ui/main.glade")
    .onButtonClick("save_btn", this::save)
    .onButtonClick("quit_btn", this::quit)
    .onWindowClose("main_window", this::quit)
    .build();

// Batch button handlers
Map<String, SignalManager.WidgetSignalHandler> handlers = Map.of(
    "new_btn", this::newDocument,
    "open_btn", this::openDocument,
    "save_btn", this::saveDocument
);
GladeUtils.connectButtonHandlers(loader, handlers);
```

### 4. Enhanced Widget Classes

**New Widget Classes Added:**
- `GtkEntry` - Single-line text input
- `GtkTextView` - Multi-line text display/editing

**Focus Shift:**
- Widget classes now focus on **interaction** rather than **creation**
- Constructors primarily for wrapping native pointers from Glade
- Enhanced signal handling within widgets
- Better error handling and state management

### 5. Updated GtkApplication

**New Features:**
- `registerGladeLoader()` - Register loaders for resource management
- `quickStart()` - One-line application creation from Glade files
- `quickStartFromResource()` - Application creation from classpath resources
- Enhanced `cleanup()` - Automatic resource cleanup

**Usage Example:**
```java
public static void main(String[] args) {
    // One-line application setup
    GladeLoader loader = GtkApplication.quickStart("main.glade", builder -> {
        builder.onButtonClick("quit_btn", widget -> GtkApplication.quit());
    });
    
    GtkApplication.run();
}
```

### 6. Enhanced WidgetFactory

**Updates:**
- Comprehensive widget type registration
- Support for all common GTK widgets
- Automatic widget wrapping from Glade-loaded pointers
- Type-safe widget creation

## Migration Steps

### Step 1: Design UI in Glade

Instead of creating widgets programmatically, design your UI using Glade:

1. Install Glade (GTK UI designer)
2. Create your UI layout
3. Assign meaningful IDs to widgets you'll interact with
4. Save as `.glade` or `.ui` file

### Step 2: Update Application Code

**Before:**
```java
public class MyApp {
    public static void main(String[] args) {
        GtkApplication.initialize();
        
        GtkWindow window = new GtkWindow(GtkWindow.WindowType.TOPLEVEL);
        window.setTitle("My Application");
        
        GtkButton button = new GtkButton("Click Me");
        button.setOnClickListener(btn -> System.out.println("Clicked!"));
        
        window.add(button);
        window.showAll();
        
        GtkApplication.run();
    }
}
```

**After:**
```java
public class MyApp {
    public static void main(String[] args) {
        GtkApplication.initialize();
        
        GladeLoader loader = GladeUtils.builder()
            .fromFile("my_app.glade")
            .onButtonClick("my_button", widget -> System.out.println("Clicked!"))
            .build();
        
        GtkApplication.run();
    }
}
```

### Step 3: Update Build Process

1. Include `.glade` files in your build
2. For resources, place files in `src/main/resources/ui/`
3. Use `fromResource()` for packaged applications

### Step 4: Update Signal Handling

**Before:**
```java
button.setOnClickListener(this::handleClick);
window.setOnCloseRequest(this::handleClose);
```

**After:**
```java
loader.connectSignal("button_id", "clicked", this::handleClick);
loader.connectSignal("window_id", "delete-event", this::handleClose);

// Or using fluent API
GladeUtils.builder()
    .onButtonClick("button_id", this::handleClick)
    .onWindowClose("window_id", this::handleClose)
    .build();
```

## Benefits of Migration

### 1. Separation of Concerns
- UI design in Glade (visual tool)
- Application logic in Java code
- Clean separation between presentation and business logic

### 2. Easier UI Maintenance
- Visual UI design without code changes
- Rapid prototyping and iteration
- Designer-developer collaboration

### 3. Reduced Boilerplate
- Less widget creation code
- Simplified application setup
- Fluent APIs for common patterns

### 4. Better Resource Management
- Automatic cleanup through `GladeLoader`
- Centralized signal management
- Memory leak prevention

### 5. Enhanced Testability
- UI logic separated from widget creation
- Easier mocking of UI components
- Better unit test isolation

## Best Practices

### 1. Glade File Organization
```
src/main/resources/ui/
├── main.glade          # Main application window
├── dialogs/
│   ├── about.glade     # About dialog
│   └── preferences.glade # Settings dialog
└── components/
    └── toolbar.glade   # Reusable components
```

### 2. Widget ID Naming
- Use descriptive, consistent IDs: `save_button`, `file_menu`, `status_label`
- Group related widgets: `toolbar_*`, `menu_*`, `dialog_*`
- Avoid generic names: `button1`, `widget2`

### 3. Signal Connection Patterns
```java
// For simple applications
GladeUtils.builder()
    .fromFile("app.glade")
    .onButtonClick("save", this::save)
    .onButtonClick("quit", this::quit)
    .build();

// For complex applications
SignalManager signals = loader.getSignalManager();
signals.connect().widget(button).signal("clicked").after().connect(handler);
```

### 4. Resource Loading
```java
// Development (file-based)
GladeLoader loader = GladeUtils.builder()
    .fromFile("ui/main.glade")
    .build();

// Production (resource-based)
GladeLoader loader = GladeUtils.builder()
    .fromResource("/ui/main.glade")
    .build();
```

### 5. Error Handling
```java
// Validate Glade files
if (!GladeUtils.validateGladeFile("main.glade")) {
    System.err.println("Invalid Glade file");
    return;
}

// Handle missing widgets gracefully
GtkButton button = loader.getWidget("save_button", GtkButton.class);
if (button == null) {
    System.err.println("Save button not found - feature disabled");
} else {
    loader.connectSignal(button, "clicked", this::save);
}
```

## Common Migration Issues

### 1. Widget Not Found
**Problem:** `getWidget()` returns null
**Solution:** 
- Check widget ID in Glade file
- Verify widget type matches expected class
- Use `validateGladeFile()` to check file integrity

### 2. Signal Connection Failures
**Problem:** Signals not firing
**Solution:**
- Verify signal names (case-sensitive)
- Check widget supports the signal
- Use `SignalManager` for debugging

### 3. Resource Loading Issues
**Problem:** Glade file not found in JAR
**Solution:**
- Verify resource path starts with `/`
- Check file is included in build
- Use `validateGladeResource()` for testing

## Example Applications

See the `examples/` directory for complete sample applications:

- `simple-app/` - Basic Glade integration
- `complex-app/` - Multi-window application
- `custom-signals/` - Advanced signal handling

## Backward Compatibility

- Existing widget classes remain functional
- Old programmatic creation APIs still work
- Migration can be done incrementally
- New and old approaches can coexist

## Future Enhancements

- GTK4 Builder templates support
- Automatic signal connection via annotations
- Enhanced Glade validation tools
- Visual signal connection debugging
- Integration with popular IDEs

## Conclusion

The migration to Glade-first development represents a significant improvement in the JGTK library's usability and maintainability. By separating UI design from application logic, developers can create more professional GTK applications with less code and better organization.

The new fluent APIs and utility classes make common tasks simpler while still providing the flexibility to handle complex scenarios through the enhanced signal management system.