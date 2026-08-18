package org.odm.gtk4;

import org.gnome.gtk.AboutDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Window;

/**
 * About dialog — 1:1 GTK4 port of about.glade (GtkAboutDialog with the
 * original program name, author, website, license).
 */
public final class AboutDialogPresenter {

    private AboutDialogPresenter() {
    }

    public static void present(Window parent) {
        GtkBuilder builder = UiLoader.load("/ui/about.ui");
        AboutDialog about = Widgets.require(builder, "about_dialog", AboutDialog.class);
        about.setTransientFor(parent);
        about.present();
    }
}
