package org.odm.gtk4;

import java.io.IOException;
import java.util.ArrayList;
import org.gnome.gdk.Texture;
import org.gnome.gdk.Toplevel;
import org.gnome.glib.List;
import org.gnome.gobject.GObject;
import org.gnome.gtk.Window;
import org.javagi.base.TransferOwnership;
import org.javagi.gobject.InstanceCache;

/** Shared app artwork for desktop integration and uninstalled IDE launches. */
final class ApplicationIcons {
    static final String APPLICATION_ID = "org.odm";
    static final String ICON_NAME = "open-download-manager";
    static final String SVG_RESOURCE = "/icons/hicolor/scalable/apps/" + ICON_NAME + ".svg";
    static final java.util.List<Integer> SIZES = java.util.List.of(16, 24, 32, 48, 64, 128, 256, 512);

    private ApplicationIcons() { }

    static String pngResource(int size) {
        return "/icons/hicolor/" + size + "x" + size + "/apps/" + ICON_NAME + ".png";
    }

    static byte[] pngBytes(int size) throws IOException {
        try (var input = ApplicationIcons.class.getResourceAsStream(pngResource(size))) {
            if (input == null) { throw new IOException("Missing ODM icon: " + pngResource(size)); }
            return input.readAllBytes();
        }
    }

    /** Called on the GTK thread before the window is first presented. */
    static void configure(Window window) {
        window.setIconName(ICON_NAME);
        window.onRealize(() -> {
            var surface = window.getSurface();
            if (surface != null) {
                // GtkWindow always owns a GdkToplevel. The Java binding may
                // expose its backend-specific type as a generic Surface.
                Toplevel toplevel = surface::handle;
                // Set the actual pixels as well as the theme name. XFCE can
                // then read _NET_WM_ICON even when ODM has not been installed.
                toplevel.setIconList(Textures.ICONS);
            }
        });
    }

    private static final class Textures {
        // Retain the textures and their borrowed native list for the session.
        // GDK copies this list when assigning it to each window.
        static final java.util.List<Texture> VALUES = load();
        static final List<Texture> ICONS = nativeList();

        private static List<Texture> nativeList() {
            List<Texture> icons = new List<>(Texture.getType(),
                    address -> (Texture) InstanceCache.get(address, GObject::new),
                    null, TransferOwnership.CONTAINER);
            icons.addAll(VALUES);
            return icons;
        }

        private static java.util.List<Texture> load() {
            var textures = new ArrayList<Texture>();
            try {
                for (int size : SIZES) { textures.add(Texture.fromBytes(pngBytes(size))); }
            } catch (Exception error) {
                throw new IllegalStateException("Cannot load ODM window icons", error);
            }
            return java.util.List.copyOf(textures);
        }
    }
}
