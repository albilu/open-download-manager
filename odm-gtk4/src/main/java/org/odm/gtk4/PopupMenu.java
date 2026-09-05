package org.odm.gtk4;

import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CssProvider;
import org.gnome.gtk.EventControllerMotion;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Popover;
import org.gnome.gtk.Widget;

/**
 * Simple menu built from a GtkPopover containing a vertical box of buttons.
 * Used for the download context menu. Each entry is a real button with a
 * connected lambda, so handler wiring remains compile-time checked.
 */
public class PopupMenu {

    private static final String CONTEXT_MENU_CSS = """
            button.odm-context-menu-item:hover,
            button.odm-context-menu-item.odm-context-menu-item-hover,
            button.odm-context-menu-item:focus-visible {
              background-color: alpha(@theme_fg_color, 0.14);
            }
            button.odm-context-menu-item:active {
              background-color: alpha(@theme_fg_color, 0.22);
            }
            button.odm-context-menu-item:disabled.odm-context-menu-item-hover {
              background-color: transparent;
            }
            """;
    private static CssProvider contextMenuCssProvider;

    private final Popover popover;
    private final Box box;

    public PopupMenu() {
        installContextMenuCss();
        box = new Box(Orientation.VERTICAL, 0);
        popover = new Popover();
        popover.setHasArrow(false);
        popover.setAutohide(true);
        popover.setChild(box);
    }

    /** Adds an enabled menu entry. */
    public PopupMenu add(String label, Runnable action) {
        return add(label, true, action);
    }

    /** Adds a menu entry whose sensitivity reflects the current selection. */
    public PopupMenu add(String label, boolean enabled, Runnable action) {
        Button item = new Button();
        Label itemLabel = new Label(label);
        itemLabel.setXalign(0.0f);
        itemLabel.setHexpand(true);
        item.setChild(itemLabel);
        item.setHalign(org.gnome.gtk.Align.FILL);
        item.setHexpand(true);
        item.setSensitive(enabled);
        item.addCssClass("flat");
        item.addCssClass("odm-context-menu-item");
        EventControllerMotion hover = new EventControllerMotion();
        hover.onEnter((x, y) -> item.addCssClass("odm-context-menu-item-hover"));
        // Some compositors map the popover with the pointer already inside a
        // row and do not deliver a distinct enter transition. Any subsequent
        // motion must still establish the visible hover state.
        hover.onMotion((x, y) -> item.addCssClass("odm-context-menu-item-hover"));
        hover.onLeave(() -> item.removeCssClass("odm-context-menu-item-hover"));
        item.addController(hover);
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

    /** Parents and anchors a standalone context popover to a widget position. */
    public PopupMenu popupAt(Widget anchor, int x, int y) {
        Widget currentParent = popover.getParent();
        if (currentParent != anchor) {
            if (currentParent != null) {
                popover.unparent();
            }
            popover.setParent(anchor);
        }
        popover.setPointingTo(new org.gnome.gdk.Rectangle(x, y, 1, 1));
        popover.popup();
        return this;
    }

    public void dispose() {
        popover.popdown();
        if (popover.getParent() != null) {
            popover.unparent();
        }
    }

    public Popover getPopover() {
        return popover;
    }

    private static synchronized void installContextMenuCss() {
        if (contextMenuCssProvider != null
                || org.gnome.gdk.Display.getDefault() == null) {
            return;
        }
        CssProvider provider = new CssProvider();
        provider.loadFromString(CONTEXT_MENU_CSS);
        Gtk.styleContextAddProviderForDisplay(org.gnome.gdk.Display.getDefault(),
                provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);
        contextMenuCssProvider = provider;
    }
}
