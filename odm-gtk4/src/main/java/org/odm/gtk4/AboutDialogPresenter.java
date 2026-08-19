package org.odm.gtk4;

import org.gnome.gdk.Texture;
import org.gnome.gtk.AboutDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Window;

/**
 * About dialog — 1:1 GTK4 port of about.glade (GtkAboutDialog with the
 * original program name, author, website, license) plus the oDM logo.
 */
public final class AboutDialogPresenter {

    private AboutDialogPresenter() {
    }

    public static void present(Window parent) {
        GtkBuilder builder = UiLoader.load("/ui/about.ui");
        AboutDialog about = Widgets.require(builder, "about_dialog", AboutDialog.class);
        about.setTransientFor(parent);
        about.setLogo(loadLogo());
        about.present();
    }

    /** Loads the oDM logo from the module's classpath resources. */
    static Texture loadLogo() {
        try (var in = AboutDialogPresenter.class.getResourceAsStream("/images/logo-128.png")) {
            if (in != null) {
                return Texture.fromBytes(in.readAllBytes());
            }
        } catch (Exception e) {
            // fall through to icon-name fallback in the .ui
        }
        return null;
    }
}
