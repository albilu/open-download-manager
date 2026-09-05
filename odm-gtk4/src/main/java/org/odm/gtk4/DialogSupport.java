package org.odm.gtk4;

import java.util.Objects;
import org.gnome.gtk.AlertDialog;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.Window;

/** Shared window ownership policy for user-facing dialogs. */
final class DialogSupport {

    private DialogSupport() {
    }

    /**
     * Keeps a dialog associated with its parent without making the two windows
     * an attached modal unit. In particular, GNOME can then move the dialog by
     * its own title bar without dragging the main window with it.
     */
    static void configureIndependent(Window dialog, Window parent) {
        Objects.requireNonNull(dialog, "dialog");
        dialog.setModal(false);
        if (parent != null) {
            dialog.setTransientFor(parent);
            dialog.setDestroyWithParent(true);
        }
    }

    /** File choosers are modal by default, unlike ordinary GtkWindow objects. */
    static void configureIndependent(FileDialog dialog) {
        Objects.requireNonNull(dialog, "dialog").setModal(false);
    }

    /** Alert dialogs are modal by default, unlike ordinary GtkWindow objects. */
    static void configureIndependent(AlertDialog dialog) {
        Objects.requireNonNull(dialog, "dialog").setModal(false);
    }
}
