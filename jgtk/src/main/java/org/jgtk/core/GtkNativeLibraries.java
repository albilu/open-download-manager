package org.jgtk.core;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/**
 * Native library interfaces for GTK3 and related libraries.
 * These interfaces provide the core JNA bindings to native GTK functions.
 */
public final class GtkNativeLibraries {

    private GtkNativeLibraries() {
        // Utility class - prevent instantiation
    }

    /**
     * GLib library interface for error handling and idle functions
     */
    public interface GLib extends Library {

        GLib INSTANCE = Native.load("glib-2.0", GLib.class);

        String g_quark_to_string(int domain);

        void g_error_free(Pointer error);

        /**
         * Adds a function to be called whenever there are no higher priority
         * events pending to the default main loop.
         * 
         * @param function the function to call
         * @param data     data to pass to the function
         * @return the ID (greater than 0) of the event source
         */
        int g_idle_add(GCallback function, Pointer data);
    }

    /**
     * GLib callback interface for idle functions
     */
    public interface GCallback extends com.sun.jna.Callback {
        boolean callback(Pointer data);
    }

    /**
     * X11 interface for threading support
     */
    public interface X11 extends Library {

        X11 INSTANCE = Native.load("X11", X11.class);

        int XInitThreads();
    }

    /**
     * Main GTK3 library interface
     */
    public interface Gtk extends Library {

        Gtk INSTANCE = Native.load("gtk-3", Gtk.class);

        // Core GTK initialization and lifecycle
        void gtk_init(Pointer argc, Pointer argv);

        void gtk_main();

        void gtk_main_quit();

        boolean gtk_events_pending();

        boolean gtk_main_iteration();

        boolean gtk_main_iteration_do(boolean blocking);

        // Builder operations
        Pointer gtk_builder_new();

        boolean gtk_builder_add_from_file(Pointer builder, String filename, PointerByReference error);

        boolean gtk_builder_add_from_string(Pointer builder, String buffer, long length, PointerByReference error);

        Pointer gtk_builder_get_object(Pointer builder, String name);

        // Widget operations
        void gtk_widget_show_all(Pointer widget);

        void gtk_widget_show(Pointer widget);

        void gtk_widget_hide(Pointer widget);

        void gtk_widget_destroy(Pointer widget);

        void gtk_widget_set_sensitive(Pointer widget, boolean sensitive);

        boolean gtk_widget_get_sensitive(Pointer widget);

        void gtk_widget_set_visible(Pointer widget, boolean visible);

        boolean gtk_widget_get_visible(Pointer widget);

        void gtk_widget_set_tooltip_text(Pointer widget, String text);

        // Type checking
        boolean g_type_check_instance_is_a(Pointer instance, long g_type);

        long gtk_toggle_button_get_type();

        long gtk_check_menu_item_get_type();

        long gtk_switch_get_type();

        long gtk_widget_get_type();

        // Object management
        void g_object_unref(Pointer object);

        // Entry widget methods
        void gtk_entry_set_text(Pointer entry, String text);

        String gtk_entry_get_text(Pointer entry);

        // Spin button methods
        void gtk_spin_button_set_value(Pointer spin_button, double value);

        double gtk_spin_button_get_value(Pointer spin_button);

        int gtk_spin_button_get_value_as_int(Pointer spin_button);

        // Toggle button and checkbox methods
        void gtk_toggle_button_set_active(Pointer toggle_button, boolean is_active);

        boolean gtk_toggle_button_get_active(Pointer toggle_button);

        // Check menu item methods
        void gtk_check_menu_item_set_active(Pointer check_menu_item, boolean is_active);

        boolean gtk_check_menu_item_get_active(Pointer check_menu_item);

        // Switch methods
        void gtk_switch_set_active(Pointer switch_widget, boolean is_active);

        boolean gtk_switch_get_active(Pointer switch_widget);

        // ComboBox methods
        void gtk_combo_box_set_active(Pointer combo_box, int index);

        int gtk_combo_box_get_active(Pointer combo_box);

        void gtk_combo_box_text_append_text(Pointer combo_box, String text);

        void gtk_combo_box_text_remove_all(Pointer combo_box);

        String gtk_combo_box_text_get_active_text(Pointer combo_box);

        // TreeView methods
        void gtk_tree_view_column_set_visible(Pointer tree_column, boolean visible);

        boolean gtk_tree_view_column_get_visible(Pointer tree_column);

        Pointer gtk_tree_view_get_selection(Pointer tree_view);

        Pointer gtk_tree_view_get_model(Pointer tree_view);

        int gtk_tree_selection_count_selected_rows(Pointer selection);

        boolean gtk_tree_selection_get_selected(Pointer selection, Pointer model, Pointer iter);

        // File chooser methods
        void gtk_file_chooser_set_current_folder(Pointer chooser, String filename);

        String gtk_file_chooser_get_current_folder(Pointer chooser);

        void gtk_file_chooser_set_filename(Pointer chooser, String filename);

        String gtk_file_chooser_get_filename(Pointer chooser);

        // Dialog methods
        Pointer gtk_message_dialog_new(Pointer parent, int flags, int type, int buttons, String message_format);

        int gtk_dialog_run(Pointer dialog);

        Pointer gtk_file_chooser_dialog_new(String title, Pointer parent, int action, String first_button_text,
                int first_response);

        void gtk_dialog_add_button(Pointer dialog, String button_text, int response_id);

        // Window methods
        void gtk_window_set_title(Pointer window, String title);

        String gtk_window_get_title(Pointer window);

        void gtk_window_present(Pointer window);

        void gtk_window_set_modal(Pointer window, boolean modal);

        void gtk_window_set_keep_above(Pointer window, boolean setting);

        void gtk_window_set_focus(Pointer window, Pointer focus);

        // Label methods
        void gtk_label_set_text(Pointer label, String text);

        String gtk_label_get_text(Pointer label);

        // Notebook methods
        int gtk_notebook_get_current_page(Pointer notebook);

        // List store methods
        void gtk_list_store_append(Pointer list_store, Pointer iter);

        void gtk_list_store_set(Pointer list_store, Pointer iter, int column, Object value, int terminator);

        void gtk_list_store_clear(Pointer list_store);

        // Tree model methods for iteration
        boolean gtk_tree_model_get_iter_first(Pointer tree_model, Pointer iter);

        boolean gtk_tree_model_iter_next(Pointer tree_model, Pointer iter);

        void gtk_tree_model_get(Pointer tree_model, Pointer iter, int column, Object value, int terminator);

        // About dialog methods
        void gtk_about_dialog_set_logo(Pointer about_dialog, Pointer pixbuf);

        // Pixbuf methods
        Pointer gdk_pixbuf_new_from_file(String filename, PointerByReference error);

        Pointer gdk_pixbuf_new_from_resource(String resource_path, PointerByReference error);

        // Image widget methods
        void gtk_image_set_from_pixbuf(Pointer image, Pointer pixbuf);

        // Progress bar methods
        void gtk_progress_bar_set_fraction(Pointer progress_bar, double fraction);

        // Menu methods
        void gtk_menu_popup_at_pointer(Pointer menu, Pointer event);
    }

    /**
     * GObject library interface for signal handling
     */
    public interface GObjectLib extends Library {

        GObjectLib INSTANCE = Native.load("gobject-2.0", GObjectLib.class);

        void g_signal_connect_data(Pointer instance, String detailed_signal,
                GtkCallbacks.GtkCallback c_handler, Pointer data, Pointer destroy_data, int connect_flags);
    }

    /**
     * GTK Builder signals interface
     */
    public interface GtkBuilderSignals extends Library {

        GtkBuilderSignals INSTANCE = Native.load("gtk-3", GtkBuilderSignals.class);

        void gtk_builder_connect_signals_full(Pointer builder,
                GtkCallbacks.BuilderConnectCallback func, Pointer userData);
    }
}
