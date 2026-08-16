package org.odm.gtk4;

import org.gnome.gobject.GObject;
import org.gnome.gtk.GtkBuilder;

/**
 * Strict widget lookup for GtkBuilder. The old GTK3 UI degraded to null +
 * a log line when a widget id was missing, which silently killed features
 * (25 dead settings widgets). Here a missing or mistyped id fails fast at
 * window-construction time — missing widgets can never ship silently.
 */
public final class Widgets {

    private Widgets() {
    }

    /**
     * Returns the widget registered under {@code id} in the builder, typed.
     *
     * @throws IllegalStateException if the id is absent or has a different type
     */
    public static <T extends GObject> T require(GtkBuilder builder, String id, Class<T> type) {
        Object obj = builder.getObject(id);
        if (obj == null) {
            throw new IllegalStateException("Missing widget id '" + id + "' (expected " + type.getSimpleName() + ")");
        }
        if (!type.isInstance(obj)) {
            throw new IllegalStateException("Widget '" + id + "' is " + obj.getClass().getSimpleName()
                    + ", expected " + type.getSimpleName());
        }
        return type.cast(obj);
    }
}
