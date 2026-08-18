package org.odm.gtk4;

import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Popover;

/**
 * Simple menu built from a GtkPopover containing a vertical box of buttons.
 * Used for the main menu (via {@code MenuButton.setPopover}) and the download
 * context menu (via {@code popup()}). Each entry is a real button with a
 * connected lambda — no string handler names, so a typo'd handler is a
 * compile error, not a dead menu item.
 */
public class PopupMenu {

    private final Popover popover;
    private final Box box;

    public PopupMenu() {
        box = new Box(Orientation.VERTICAL, 0);
        popover = new Popover();
        popover.setChild(box);
    }

    /** Adds a menu entry; on pressed the action runs and the menu closes. */
    public PopupMenu add(String label, Runnable action) {
        Button item = new Button();
        item.setLabel(label);
        item.setHalign(org.gnome.gtk.Align.START);
        item.setHexpand(false);
        item.setCanFocus(false);
        item.addCssClass("flat");
        item.onClicked(() -> {
            popover.popdown();
            action.run();
        });
        box.append(item);
        return this;
    }

    /** Adds a separator. */
    public PopupMenu separator() {
        box.append(new org.gnome.gtk.Separator(Orientation.HORIZONTAL));
        return this;
    }

    public PopupMenu popup() {
        popover.popup();
        return this;
    }

    public Popover getPopover() {
        return popover;
    }
}
