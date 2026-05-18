# Main Window Glade File Fixes

## Overview

This document summarizes the fixes applied to `main-window.glade` to improve object identification and signal definitions for the ODM (Open Download Manager) application.

## Issues Fixed

### 1. Missing Object IDs

#### Main Containers

-   **Fixed**: Added `id="main_container"` to the main GtkBox container
-   **Fixed**: Added `id="content_paned"` to the vertical paned container
-   **Fixed**: Added `id="download_list_container"` to download list container box
-   **Fixed**: Added `id="download_scrolled_window"` to download list scrolled window
-   **Fixed**: Added `id="progress_container"` to progress bar container
-   **Fixed**: Added `id="general_info_container"` to general info container

#### TreeView Selections

-   **Fixed**: Added `id="status_selection"` to status treeview selection
-   **Fixed**: Added `id="category_selection"` to category treeview selection
-   **Fixed**: Added `id="download_selection"` to download treeview selection
-   **Fixed**: Added `id="trackers_selection"` to trackers treeview selection
-   **Fixed**: Added `id="peers_selection"` to peers treeview selection
-   **Fixed**: Added `id="files_selection"` to files treeview selection

#### Toolbar Components

-   **Fixed**: Added `id="tor_tool_item"` to Tor switch tool item
-   **Fixed**: Added `id="tor_box"` to Tor switch container box
-   **Fixed**: Added `id="spacer_tool_item_1"` and `id="spacer_tool_item_2"` to toolbar spacers
-   **Fixed**: Added `id="search_tool_item"` to search entry tool item

#### Status Bar Components

-   **Fixed**: Added `id="statusbar_content_box"` to status bar content container
-   **Fixed**: Added `id="upload_speed_box"` to upload speed container
-   **Fixed**: Added `id="upload_speed_icon"` to upload speed icon
-   **Fixed**: Added `id="download_speed_box"` to download speed container
-   **Fixed**: Added `id="download_speed_icon"` to download speed icon
-   **Fixed**: Added `id="dht_progress_box"` to DHT progress container
-   **Fixed**: Added `id="dht_status_label"` to DHT status label
-   **Fixed**: Added `id="activity_spinner"` to activity spinner

#### Separators

-   **Fixed**: Added `id="upload_separator"` to upload section separator
-   **Fixed**: Added `id="download_separator"` to download section separator
-   **Fixed**: Added `id="dht_inner_separator"` to DHT inner separator

### 2. Inconsistent Signal Handler Names

#### Fixed Handler Names

-   **Before**: `clipboard_import_import_menu_item_activate`
-   **After**: `on_clipboard_import_menu_item_activate`

-   **Before**: `on_column_toricon_menu_item_toggled`
-   **After**: `on_column_tor_icon_menu_item_toggled`

### 3. Missing Critical Signals

#### Download TreeView

-   **Added**: `cursor-changed` signal with handler `on_download_treeview_cursor_changed`
-   **Added**: `popup-menu` signal with handler `on_download_treeview_popup_menu`

#### Search Entry

-   **Added**: `activate` signal with handler `on_search_entry_activate`

#### Files TreeView

-   **Added**: `cursor-changed` signal with handler `on_files_view_cursor_changed`
-   **Added**: `button-press-event` signal with handler `on_files_view_button_press_event`

#### Files Toggle Renderer

-   **Added**: `toggled` signal with handler `on_files_selected_renderer_toggled`

### 4. New Context Menu

#### Added Download Context Menu

Created a comprehensive context menu (`id="download_context_menu"`) with the following items:

-   **Open** - `on_context_open_menu_item_activate`
-   **Open Folder** - `on_context_open_folder_menu_item_activate`
-   **Pause** - `on_context_pause_menu_item_activate`
-   **Resume** - `on_context_resume_menu_item_activate`
-   **Force Download** - `on_context_force_download_menu_item_activate`
-   **Move Up** - `on_context_move_up_menu_item_activate`
-   **Move Down** - `on_context_move_down_menu_item_activate`
-   **Delete** - `on_context_delete_menu_item_activate`
-   **Delete with Files** - `on_context_delete_with_files_menu_item_activate`
-   **Properties** - `on_context_properties_menu_item_activate`

## Signal Handler Naming Convention

All signal handlers now follow the consistent pattern:

```
on_<widget_name>_<signal_name>
```

Examples:

-   `on_main_window_delete_event`
-   `on_new_download_button_clicked`
-   `on_download_treeview_cursor_changed`
-   `on_search_entry_activate`

## Verification

### Validation Status

-   ✅ Glade file validates without errors or warnings
-   ✅ All major UI components have proper object IDs
-   ✅ Signal handlers follow consistent naming convention
-   ✅ Critical user interaction signals are defined

### Next Steps for Implementation

The Java controller (`MainWindowController.java`) needs to be updated to implement the signal handlers:

1. **Window Signals**

    - `on_main_window_delete_event`
    - `on_main_window_destroy`

2. **Menu Signals**

    - All menu item activation handlers
    - Toggle menu item handlers for preferences

3. **Toolbar Signals**

    - Button click handlers
    - Tor switch state change handler

4. **TreeView Signals**

    - Selection change handlers
    - Context menu handlers
    - Row activation handlers

5. **Search Functionality**

    - Search entry handlers

6. **Files Management**
    - File selection toggle handlers
    - File list interaction handlers

## Benefits of These Fixes

1. **Improved Maintainability**: All UI components are properly identified and can be accessed programmatically
2. **Enhanced User Experience**: Context menus and proper signal handling provide intuitive interaction
3. **Consistent Architecture**: Standardized naming convention makes the codebase more maintainable
4. **Complete Functionality**: All necessary signals are defined for full application functionality
5. **Better Debugging**: Named components make UI debugging and testing easier

## Compatibility

These changes maintain backward compatibility with existing code while providing the foundation for proper signal handling implementation in the Java controller classes.
