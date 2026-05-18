# GTK Widget Type Handling

## Overview

This document explains how the Open Download Manager handles different GTK widget types, particularly the distinction between `GtkCheckMenuItem` and `GtkToggleButton` widgets.

## The Problem

GTK has different widget types that serve similar purposes but require different API calls:

- **GtkToggleButton**: Used for checkboxes and toggle buttons in forms and toolbars
- **GtkCheckMenuItem**: Used for checkable items in menus

Both widgets can be in an "active" or "inactive" state, but they use different GTK functions:

### Toggle Buttons
```c
gtk_toggle_button_set_active(widget, active);
boolean gtk_toggle_button_get_active(widget);
```

### Check Menu Items
```c
gtk_check_menu_item_set_active(widget, active);
boolean gtk_check_menu_item_get_active(widget);
```

## The Error

Previously, the application was using `gtk_toggle_button_get_active()` on `GtkCheckMenuItem` widgets, which caused GTK critical errors:

```
(java:1): Gtk-CRITICAL **: gtk_toggle_button_get_active: assertion 'GTK_IS_TOGGLE_BUTTON (toggle_button)' failed
```

This happened because the code was trying to call toggle button functions on check menu item widgets.

## The Solution

### 1. Added Check Menu Item Support

Extended the `GladeUI.Gtk` interface to include check menu item functions:

```java
// Check menu item methods
void gtk_check_menu_item_set_active(Pointer check_menu_item, boolean is_active);
boolean gtk_check_menu_item_get_active(Pointer check_menu_item);
```

### 2. Created Wrapper Methods

Added specific methods for check menu items in `GladeUI`:

```java
public void setCheckMenuItemActive(String widgetId, boolean active)
public boolean getCheckMenuItemActive(String widgetId)
```

### 3. Added Smart Widget Methods

Created intelligent wrapper methods that automatically detect widget type:

```java
public void setWidgetActive(String widgetId, boolean active)
public boolean getWidgetActive(String widgetId)
```

These methods use a simple heuristic to detect widget type:
- If the widget ID contains "menu_item", it's treated as a check menu item
- If the widget ID contains "switch", it's treated as a switch widget  
- Otherwise, it's treated as a toggle button

### 4. Updated Application Code

All menu item handlers now use the smart wrapper methods:

```java
// OLD (causes GTK errors):
boolean visible = ui.getToggleButtonActive("left_panel_menu_item");

// NEW (works correctly):
boolean visible = ui.getWidgetActive("left_panel_menu_item");
```

## Widget ID Patterns

The application uses these naming conventions:

### Check Menu Items (use `getWidgetActive`/`setWidgetActive`)
- `left_panel_menu_item` - GtkCheckMenuItem
- `info_panel_menu_item` - GtkCheckMenuItem  
- `offline_menu_item` - GtkCheckMenuItem
- `clipboard_monitoring_menu_item` - GtkCheckMenuItem
- `clipboard_silent_menu_item` - GtkCheckMenuItem
- `completion_none_menu_item` - GtkRadioMenuItem (inherits from GtkCheckMenuItem)
- `completion_suspend_menu_item` - GtkRadioMenuItem (inherits from GtkCheckMenuItem)
- `completion_shutdown_menu_item` - GtkRadioMenuItem (inherits from GtkCheckMenuItem)
- `completion_custom_menu_item` - GtkRadioMenuItem (inherits from GtkCheckMenuItem)
- `column_*_menu_item` - GtkCheckMenuItem (various column visibility toggles)

### Switch Widgets (use `getWidgetActive`/`setWidgetActive`)
- `tor_switch` - GtkSwitch

### Toggle Buttons (use `getToggleButtonActive`/`setToggleButtonActive`)
- `start_immediately_check` - GtkCheckButton
- `use_auth_check` - GtkCheckButton  
- `close_to_tray_check` - GtkCheckButton

## Best Practices

1. **Use Smart Methods**: Prefer `getWidgetActive()` and `setWidgetActive()` for new code as they handle both widget types automatically.

2. **Consistent Naming**: Follow the naming convention where menu items include "menu_item" in their ID.

3. **Widget Type Verification**: If you're unsure about a widget's type, check the Glade file to see its `class` attribute:
   - `<object class="GtkCheckMenuItem">` → use check menu item methods
   - `<object class="GtkRadioMenuItem">` → use check menu item methods (inherits from GtkCheckMenuItem)
   - `<object class="GtkSwitch">` → use switch methods
   - `<object class="GtkToggleButton">` → use toggle button methods
   - `<object class="GtkCheckButton">` → use toggle button methods

4. **Error Handling**: Both widget type methods return `false` for non-existent widgets and handle null pointers gracefully.

## Testing

The `GtkCheckMenuItemTest` class provides unit tests to verify:
- Widget ID detection logic
- Method existence and basic functionality
- Error handling for non-existent widgets

## Future Considerations

If more widget types with similar "active" state functionality are added (like `GtkRadioButton`), the smart widget methods can be extended to handle them automatically based on naming conventions or widget type detection.

## Key Fixes Applied

### GtkSwitch Support
Added support for `GtkSwitch` widgets by:
- Adding `gtk_switch_get_active()` and `gtk_switch_set_active()` to the GTK interface
- Creating `getSwitchActive()` and `setSwitchActive()` methods in GladeUI
- Updating smart widget detection to handle "switch" in widget IDs
- Fixed `tor_switch` usage in MainWindowController

### GtkRadioMenuItem Support  
`GtkRadioMenuItem` widgets work correctly with existing check menu item methods since they inherit from `GtkCheckMenuItem` in GTK.

### Improved Error Prevention
The smart widget detection now prevents GTK critical errors by automatically selecting the correct GTK function based on widget type heuristics.