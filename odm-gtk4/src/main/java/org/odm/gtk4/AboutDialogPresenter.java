package org.odm.gtk4;

import org.gnome.gdk.Texture;
import org.gnome.gdkpixbuf.PixbufLoader;
import org.gnome.gtk.AboutDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Window;

/**
 * About dialog — 1:1 GTK4 port of about.glade (GtkAboutDialog with the
 * original program name, author, website, license) plus the oDM logo.
 */
public final class AboutDialogPresenter {

    static final String LOGO_RESOURCE = "/images/logo-128.svg";

    private AboutDialogPresenter() {
    }

    public static void present(Window parent) {
        GtkBuilder builder = UiLoader.load("/ui/about.ui");
        AboutDialog about = Widgets.require(builder, "about_dialog", AboutDialog.class);
        DialogSupport.configureIndependent(about, parent);
        about.setLogo(loadLogo());
        String implementationVersion = AboutDialogPresenter.class.getPackage()
                .getImplementationVersion();
        if (implementationVersion != null && !implementationVersion.isBlank()) {
            about.setVersion(implementationVersion);
        }
        about.present();
    }

    /** Loads the oDM logo from the module's classpath resources. */
    static Texture loadLogo() {
        try (var in = AboutDialogPresenter.class.getResourceAsStream(LOGO_RESOURCE)) {
            if (in != null) {
                PixbufLoader loader = PixbufLoader.withType("svg");
                loader.setSize(128, 128);
                loader.write(in.readAllBytes());
                loader.close();
                if (loader.getPixbuf() != null) {
                    return Texture.forPixbuf(loader.getPixbuf());
                }
            }
        } catch (Exception e) {
            // fall through to icon-name fallback in the .ui
        }
        return null;
    }
}
