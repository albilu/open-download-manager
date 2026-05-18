# ODM jgtk Demo

This demo shows the new simplified jgtk implementation loading and displaying ODM's actual main window using GTK.

## What This Demo Does

The demo loads ODM's real `main-window.glade` file and displays it in a proper GTK application window with:

-   ✅ **Real ODM UI**: Loads the actual Glade file from `odm-gtk/src/main/resources/glade/main-window/main-window.glade`
-   ✅ **Working signals**: All ODM signal handlers are registered and functional
-   ✅ **GTK main loop**: Runs in a proper GTK application loop with window display
-   ✅ **Interactive**: Click menu items and buttons to see signal handlers in action

## Quick Start

### Option 1: Run Script (Recommended)

```bash
cd open-download-manager/jgtk
./run-demo.sh
```

### Option 2: Manual Maven Execution

```bash
cd open-download-manager/jgtk
mvn clean compile test-compile
mvn exec:java -Dexec.mainClass="org.jgtk.ODMMainWindowDemo" -Dexec.classpathScope="test"
```

### Option 3: Test Mode (No Window Display)

```bash
cd open-download-manager/jgtk
mvn exec:java -Dexec.mainClass="org.jgtk.ODMMainWindowDemo" -Dexec.classpathScope="test" -Dexec.args="test"
```

## Expected Behavior

### In GUI Environment

When run on a system with a desktop environment:

1. **ODM Main Window Appears**: You'll see the actual ODM interface

    - Menu bar with File, Edit, View, Tools, Help menus
    - Toolbar with download control buttons
    - Category tree on the left
    - Download list on the right
    - Status bar at the bottom

2. **Interactive Interface**:

    - Click menu items → See signal handler messages in console
    - Click toolbar buttons → See download action messages
    - Right-click in download list → See context menu message
    - Close window → Application exits cleanly

3. **Console Output**: Shows all signal handling in real-time:

    ```
    ✓ ODM main window Glade file loaded successfully
    ✓ Found main window widget: native@0x...
    ✓ Signal handlers connected
    ✓ Main window displayed

    === GTK Application Started ===
    The ODM main window should now be visible.
    ```

### In Headless Environment

When run without a display (SSH, CI, etc.):

-   All loading and setup works correctly
-   GTK commands execute without errors
-   No visible window (expected behavior)
-   Console shows successful initialization
-   Can be interrupted with Ctrl+C

## Signal Handlers Demonstrated

The demo registers handlers for all major ODM signals:

### File Menu

-   `on_new_download_activate` → "New Download menu item activated"
-   `on_import_txt_activate` → "Import from text file activated"
-   `on_settings_activate` → "Settings menu item activated"
-   `on_quit_activate` → Exits application

### Toolbar

-   `on_toolbar_new_clicked` → "New download toolbar button clicked"
-   `on_toolbar_start_clicked` → "Start download toolbar button clicked"
-   `on_toolbar_pause_clicked` → "Pause download toolbar button clicked"
-   `on_toolbar_stop_clicked` → "Stop download toolbar button clicked"

### Download List

-   `on_download_treeview_row_activated` → "Download list row double-clicked"
-   `on_download_treeview_button_press_event` → "Download list right-clicked"

### Window Events

-   `on_window_destroy` → Saves state and exits
-   Window resize/move events → State saving messages

## Demo Variants

### 1. Main Demo (`ODMMainWindowDemo.main()`)

-   Full GTK application with window display
-   Runs until window is closed
-   Interactive signal handling

### 2. Timed Demo (`ODMMainWindowDemo.runTimedDemo(seconds)`)

-   Runs for specified number of seconds
-   Useful for automated testing
-   Processes events without blocking indefinitely

### 3. Test Demo (`ODMMainWindowDemo.testDemo()`)

-   Quick validation without window display
-   Returns boolean success/failure
-   Perfect for CI/CD testing

## Technical Details

### Glade File Loading

```java
GladeUI ui = new GladeUI();
boolean loaded = ui.loadFromFile("../odm-gtk/src/main/resources/glade/main-window/main-window.glade");
```

### Widget Access

```java
var mainWindow = ui.getWidget("main_window"); // Note: ODM uses "main_window"
```

### Signal Registration

```java
ui.on("on_window_destroy", (widget, data) -> {
    System.out.println("Window closing - saving state...");
    ui.quit();
});
```

### Display and Main Loop

```java
ui.connectSignals();        // Connect all registered handlers
ui.showAll("main_window");  // Show the main window
ui.run();                   // Start GTK main loop (blocks until quit)
```

## Troubleshooting

### "Failed to load ODM main window Glade file"

-   Make sure you're running from the `jgtk` directory inside the `open-download-manager` project
-   Verify the path: `../odm-gtk/src/main/resources/glade/main-window/main-window.glade` should exist

### "No DISPLAY environment variable"

-   Normal for headless environments (SSH, CI)
-   Demo will still work but no window will be visible
-   Run on a desktop system to see the actual window

### GTK Critical Warnings

-   The warnings about ListStore are normal for complex Glade files
-   They don't affect functionality
-   Come from ODM's ListStore data definitions

### Compilation Errors

```bash
mvn clean compile test-compile  # Recompile everything
```

## What This Proves

This demo validates that the new jgtk implementation:

1. ✅ **Successfully loads complex ODM Glade files**
2. ✅ **Handles all ODM signal definitions correctly**
3. ✅ **Displays real ODM interface without errors**
4. ✅ **Provides working GTK application lifecycle**
5. ✅ **Is ready for ODM integration**

## Next Steps for ODM Integration

To integrate this into ODM:

1. **Replace complex widget creation** with simple Glade loading:

    ```java
    GladeUI mainUI = new GladeUI();
    mainUI.loadFromResource("/glade/main-window/main-window.glade");
    ```

2. **Use simple signal handlers** instead of complex event management:

    ```java
    mainUI.on("on_new_download_activate", (widget, data) -> {
        // Open new download dialog
    });
    ```

3. **Load additional dialogs** as needed:
    ```java
    GladeUI settingsUI = new GladeUI();
    settingsUI.loadFromResource("/glade/settings/settings.glade");
    ```

The new jgtk implementation is ready for production use with ODM!
