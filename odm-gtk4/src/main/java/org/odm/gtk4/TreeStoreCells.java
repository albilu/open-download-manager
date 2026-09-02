package org.odm.gtk4;

import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreeStore;
import org.gnome.gobject.Value;
import org.javagi.gobject.types.Types;

/** Typed, leak-safe cell access for {@link TreeStore} rows. */
final class TreeStoreCells {

    private TreeStoreCells() {
    }

    static void setString(TreeStore store, TreeIter iter, int column, String value) {
        Value cell = new Value().init(Types.STRING);
        cell.setString(value);
        store.setValue(iter, column, cell);
        cell.unset();
    }

    static void setInt(TreeStore store, TreeIter iter, int column, int value) {
        Value cell = new Value().init(Types.INT);
        cell.setInt(value);
        store.setValue(iter, column, cell);
        cell.unset();
    }

    static void setLong(TreeStore store, TreeIter iter, int column, long value) {
        Value cell = new Value().init(Types.INT64);
        cell.setInt64(value);
        store.setValue(iter, column, cell);
        cell.unset();
    }

    static void setDouble(TreeStore store, TreeIter iter, int column, double value) {
        Value cell = new Value().init(Types.DOUBLE);
        cell.setDouble(value);
        store.setValue(iter, column, cell);
        cell.unset();
    }

    static void setBoolean(TreeStore store, TreeIter iter, int column, boolean value) {
        Value cell = new Value().init(Types.BOOLEAN);
        cell.setBoolean(value);
        store.setValue(iter, column, cell);
        cell.unset();
    }

    static String getString(TreeStore store, TreeIter iter, int column) {
        Value cell = new Value();
        store.getValue(iter, column, cell);
        try {
            return cell.getString();
        } finally {
            cell.unset();
        }
    }

    static int getInt(TreeStore store, TreeIter iter, int column) {
        Value cell = new Value();
        store.getValue(iter, column, cell);
        try {
            return cell.getInt();
        } finally {
            cell.unset();
        }
    }

    static boolean getBoolean(TreeStore store, TreeIter iter, int column) {
        Value cell = new Value();
        store.getValue(iter, column, cell);
        try {
            return cell.getBoolean();
        } finally {
            cell.unset();
        }
    }

    static long getLong(TreeStore store, TreeIter iter, int column) {
        Value cell = new Value();
        store.getValue(iter, column, cell);
        try {
            return cell.getInt64();
        } finally {
            cell.unset();
        }
    }
}
