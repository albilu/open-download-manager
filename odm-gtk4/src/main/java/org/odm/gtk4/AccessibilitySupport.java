package org.odm.gtk4;

import org.gnome.gtk.Accessible;
import org.gnome.gtk.AccessibleAnnouncementPriority;
import org.gnome.gtk.AccessibleProperty;
import org.gnome.gtk.Label;
import org.gnome.gobject.Value;
import org.javagi.gobject.types.Types;

/** Small, centralized accessibility helpers for dynamically wired GTK controls. */
final class AccessibilitySupport {

    private AccessibilitySupport() {
    }

    static void label(Accessible control, String label) {
        if (control != null && label != null) {
            // Prefer the array/GValue API over GTK's C varargs API. The
            // JavaGI varargs wrapper does not append GTK_ACCESSIBLE_PROPERTY_NONE,
            // causing GTK to read arbitrary following arguments as another
            // property on some platforms.
            Value value = new Value().init(Types.STRING);
            try {
                value.setString(label);
                control.updatePropertyValue(
                        new AccessibleProperty[] {AccessibleProperty.LABEL},
                        new Value[] {value});
            } finally {
                value.unset();
            }
        }
    }

    static void status(Label statusLabel, String message) {
        status(statusLabel, message, AccessibleAnnouncementPriority.MEDIUM);
    }

    static void status(Label statusLabel, String message,
            AccessibleAnnouncementPriority priority) {
        statusLabel.setLabel(message);
        try {
            // gtk_accessible_announce is available on GTK 4.14+. Keep the
            // visual status working when a distribution provides older GTK.
            statusLabel.announce(message, priority);
        } catch (Throwable unavailableOnOlderGtk) {
            // GTK < 4.14 has no announce API. Updating an explicit
            // accessible description still emits an accessibility-property
            // change; status labels in the UI also carry the STATUS role.
            Value value = new Value().init(Types.STRING);
            try {
                value.setString(message);
                statusLabel.updatePropertyValue(
                        new AccessibleProperty[] {AccessibleProperty.DESCRIPTION},
                        new Value[] {value});
            } finally {
                value.unset();
            }
        }
    }
}
