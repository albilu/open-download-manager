# Enhanced GTK Signal Handling System

## Overview

The JGTK library has been enhanced to support full GTK signal parameters automatically while maintaining complete backward compatibility with existing code. This system provides both legacy 2-parameter callbacks and enhanced callbacks that receive the complete set of signal parameters.

## Architecture

### Core Components

1. **Enhanced Callback Interfaces** (`GtkCallbacks`)

    - Extended to include enhanced callback interfaces for specific signal types
    - All enhanced callbacks extend the base `GtkCallback` interface for compatibility
    - Automatically receive proper GTK signal parameters

2. **Signal Registry** (`GtkSignalRegistry`)

    - Maps signal names to their appropriate callback types
    - Provides signal type detection and parameter descriptions
    - Extensible for adding new signal types

3. **Enhanced Signal Management** (`SignalManagementService`)

    - Supports both legacy and enhanced callback registration
    - Automatic signal type detection and parameter routing
    - Priority system: enhanced handlers are tried first, then legacy handlers

4. **Enhanced GladeUI API**
    - Provides specialized methods for common enhanced signals
    - Maintains backward compatibility with existing `ui.on()` methods
    - Type-safe callback registration

## Usage Examples

### Legacy Approach (Backward Compatible)

```java
// Current approach - still works perfectly
ui.on("on_options_notebook_switch_page", (widget, data) -> {
    int pageIndex = GtkNativeLibraries.Gtk.INSTANCE.gtk_notebook_get_current_page(widget);
    handlePageSwitch(pageIndex);
});
```

### Enhanced Approach (New)

```java
// Enhanced approach - direct parameter access
ui.onNotebookSwitchPage("on_options_notebook_switch_page",
    (notebook, page, pageNum, userData) -> {
        handlePageSwitch(pageNum); // Direct access to page number!
    });
```

## Available Enhanced Signals

### 1. Notebook Switch-Page Signal

**GTK Signature:** `(GtkNotebook *notebook, GtkWidget *page, guint page_num, gpointer user_data)`

**Enhanced Interface:** `NotebookSwitchPageCallback`

**Benefits:**

-   Direct access to page number without additional GTK calls
-   Access to page widget pointer
-   More efficient than legacy approach

**Example:**

```java
ui.onNotebookSwitchPage("on_options_notebook_switch_page",
    (notebook, page, pageNum, userData) -> {
        LOGGER.info("Switched to page: " + pageNum);
        service.handlePageSwitch(pageNum);
    });
```

### 2. Tree View Row Activated Signal

**GTK Signature:** `(GtkTreeView *tree_view, GtkTreePath *path, GtkTreeViewColumn *column, gpointer user_data)`

**Enhanced Interface:** `TreeViewRowActivatedCallback`

**Benefits:**

-   Direct access to tree path and column
-   No need for additional GTK queries to get selection info

**Example:**

```java
ui.onTreeViewRowActivated("on_files_treeview_row_activated",
    (treeView, path, column, userData) -> {
        // Direct access to activated row path and column
        service.handleRowActivated(path, column);
    });
```

### 3. Switch State-Set Signal

**GTK Signature:** `(GtkSwitch *widget, gboolean state, gpointer user_data)`

**Enhanced Interface:** `SwitchStateSetCallback`

**Benefits:**

-   Direct access to new switch state
-   No need to query widget for current state

**Example:**

```java
ui.onSwitchStateSet("on_tor_switch_state_set",
    (switchWidget, state, userData) -> {
        LOGGER.info("Tor switch toggled to: " + state);
        service.handleTorToggle(state);
        return true; // Prevent further signal propagation if needed
    });
```

## Implementation Details

### Signal Handler Resolution

The enhanced system uses a priority-based resolution:

1. **Enhanced Handlers First:** Check if an enhanced handler is registered
2. **Legacy Handlers Second:** Fall back to legacy 2-parameter handlers
3. **Warning:** Log warning if no handler is found

### Type Safety

All enhanced callbacks extend `GtkCallback` to ensure they can be properly cast and connected to GTK's signal system. The base `invoke(Pointer, Pointer)` method is overridden with a default implementation that throws an exception, ensuring the correct enhanced method is called.

### Backward Compatibility

-   **100% Compatible:** All existing code continues to work unchanged
-   **Migration Optional:** Enhanced signals can be adopted gradually
-   **Mixed Usage:** Projects can use both approaches simultaneously

## Adding New Enhanced Signals

To add support for a new enhanced signal:

1. **Define the Callback Interface:**

```java
public interface MyEnhancedCallback extends GtkCallback {
    @Override
    default void invoke(Pointer instance, Pointer data) {
        throw new UnsupportedOperationException("Use enhanced method");
    }

    void invoke(Pointer widget, int param1, String param2, Pointer userData);
}
```

2. **Add to Signal Registry:**

```java
SIGNAL_TYPE_MAP.put("my-signal", SignalType.MY_ENHANCED);
```

3. **Add GladeUI Method:**

```java
public void onMyEnhancedSignal(String handlerName, MyEnhancedCallback callback) {
    signalService.registerEnhancedHandler(handlerName, callback);
}
```

## Performance Benefits

### Enhanced Approach

-   ✅ **Zero Additional GTK Calls:** Parameters received directly
-   ✅ **Type Safety:** Compile-time parameter validation
-   ✅ **Efficiency:** No widget queries needed
-   ✅ **Clarity:** Intent clear from method signature

### Legacy Approach

-   ⚠️ **GTK Queries Required:** Additional calls to get parameters
-   ⚠️ **Runtime Overhead:** Extra function calls per signal
-   ⚠️ **Potential Race Conditions:** Widget state might change between signal and query

## Current Status

### Implemented Enhanced Signals

-   ✅ `switch-page` (Notebook page switching)
-   ✅ `row-activated` (TreeView row activation)
-   ✅ `state-set` (Switch state changes)

### Legacy Signals (Still Available)

-   ✅ All existing 2-parameter signals continue to work
-   ✅ Can be upgraded to enhanced signals on demand
-   ✅ No breaking changes required

## Migration Strategy

### Phase 1: Optional Enhancement

-   Enhanced signals available alongside legacy ones
-   Developers can choose which approach to use
-   No existing code needs to change

### Phase 2: Gradual Adoption

-   New code can use enhanced signals
-   Critical signals can be migrated for performance benefits
-   Testing can be done incrementally

### Phase 3: Full Enhancement (Future)

-   All commonly used signals have enhanced versions
-   Legacy support maintained for backward compatibility
-   Performance optimized throughout the application

## Example: NewDownloadController

The `NewDownloadController` demonstrates both approaches:

```java
// Legacy approach (currently active)
ui.on("on_options_notebook_switch_page",
    (widget, data) -> handler.on_options_notebook_switch_page(widget, data));

// Enhanced approach (commented, ready to use)
// ui.onNotebookSwitchPage("on_options_notebook_switch_page",
//     (notebook, page, pageNum, userData) -> handler.onNotebookPageSwitchEnhanced(notebook, page, pageNum, userData));
```

This provides a clear comparison and easy migration path for developers.

## Benefits Summary

### For Developers

-   **Cleaner Code:** Direct parameter access
-   **Better Performance:** No additional GTK calls
-   **Type Safety:** Compile-time validation
-   **Flexibility:** Choose the best approach for each use case

### For the Project

-   **Backward Compatibility:** No breaking changes
-   **Performance Improvement:** Reduced GTK API calls
-   **Future-Proof:** Extensible architecture for new signals
-   **Developer Experience:** Better debugging and maintenance

### For Users

-   **Better Performance:** More responsive UI
-   **Reliability:** Fewer race conditions
-   **Consistency:** Standardized signal handling across the application

## Conclusion

The enhanced GTK signal handling system provides a modern, efficient, and type-safe approach to handling GTK signals while maintaining complete backward compatibility. It can be adopted gradually and provides immediate benefits in terms of performance and code clarity.
