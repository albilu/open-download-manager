package org.odm.gtk4;

import org.gnome.gtk.ListStore;
import org.gnome.gtk.TreeIter;
import org.gnome.gobject.Value;
import org.javagi.gobject.types.Types;

/**
 * Typed cell writers for GtkListStore rows. Each write allocates, sets,
 * applies, and unsets the GValue so callers cannot leak it.
 */
final class ListStoreCells {

    private ListStoreCells() {
    }

    static void setString(ListStore store, TreeIter iter, int column, String value) {
        Value v = new Value().init(Types.STRING);
        v.setString(value);
        store.setValue(iter, column, v);
        v.unset();
    }

    static void setInt(ListStore store, TreeIter iter, int column, int value) {
        // All int-typed store columns (progress, filter counts) are gint
        Value v = new Value().init(Types.INT);
        v.setInt(value);
        store.setValue(iter, column, v);
        v.unset();
    }

    static void setBoolean(ListStore store, TreeIter iter, int column, boolean value) {
        Value v = new Value().init(Types.BOOLEAN);
        v.setBoolean(value);
        store.setValue(iter, column, v);
        v.unset();
    }

    static String getString(ListStore store, TreeIter iter, int column) {
        Value v = new Value();
        store.getValue(iter, column, v);
        try {
            return v.getString();
        } finally {
            v.unset();
        }
    }

    static int getInt(ListStore store, TreeIter iter, int column) {
        Value v = new Value();
        store.getValue(iter, column, v);
        try {
            return v.getInt();
        } finally {
            v.unset();
        }
    }

    static boolean getBoolean(ListStore store, TreeIter iter, int column) {
        Value v = new Value();
        store.getValue(iter, column, v);
        try {
            return v.getBoolean();
        } finally {
            v.unset();
        }
    }
}
