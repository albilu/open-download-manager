package org.jgtk.core;

import com.sun.jna.Callback;
import com.sun.jna.Pointer;

/**
 * Enhanced callback interfaces for GTK signal handlers.
 * Supports both legacy 2-parameter callbacks and enhanced callbacks with full
 * signal parameters.
 * Using Java 17+ sealed classes for better type safety and pattern matching.
 */
public final class GtkCallbacks {

    private GtkCallbacks() {
        // Utility class - prevent instantiation
    }

    /**
     * Basic callback interface for signal handlers (legacy - 2 parameters)
     */
    public interface GtkCallback extends Callback {
        void invoke(Pointer instance, Pointer data);
    }

    /**
     * Enhanced callback interface for notebook switch-page signal
     * Signature: (GtkNotebook *notebook, GtkWidget *page, guint page_num, gpointer
     * user_data)
     */
    public interface NotebookSwitchPageCallback extends GtkCallback {
        @Override
        default void invoke(Pointer instance, Pointer data) {
            // This will be overridden by JNA to call the correct method
            throw new UnsupportedOperationException("This method should not be called directly");
        }

        void invoke(Pointer notebook, Pointer page, int pageNum, Pointer userData);
    }

    /**
     * Enhanced callback interface for tree view row-activated signal
     * Signature: (GtkTreeView *tree_view, GtkTreePath *path, GtkTreeViewColumn
     * *column, gpointer user_data)
     */
    public interface TreeViewRowActivatedCallback extends GtkCallback {
        @Override
        default void invoke(Pointer instance, Pointer data) {
            // This will be overridden by JNA to call the correct method
            throw new UnsupportedOperationException("This method should not be called directly");
        }

        void invoke(Pointer treeView, Pointer path, Pointer column, Pointer userData);
    }

    /**
     * Enhanced callback interface for switch state-set signal
     * Signature: (GtkSwitch *widget, gboolean state, gpointer user_data)
     */
    public interface SwitchStateSetCallback extends GtkCallback {
        @Override
        default void invoke(Pointer instance, Pointer data) {
            // This will be overridden by JNA to call the correct method
            throw new UnsupportedOperationException("This method should not be called directly");
        }

        boolean invoke(Pointer switchWidget, boolean state, Pointer userData);
    }

    /**
     * Builder connect callback for automatic signal connection
     */
    public interface BuilderConnectCallback extends Callback {
        void invoke(Pointer builder, Pointer object,
                String signalName, String handlerName,
                Pointer connectObject, int flags, Pointer userData);
    }
}
