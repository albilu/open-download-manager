package org.odm.gtk4;

import org.gnome.gtk.AboutDialog;
import org.gnome.gtk.License;
import org.gnome.gtk.Window;

/**
 * About dialog, using GTK's native AboutDialog.
 */
public final class AboutDialogPresenter {

    private AboutDialogPresenter() {
    }

    public static void present(Window parent) {
        AboutDialog about = new AboutDialog();
        about.setTransientFor(parent);
        about.setModal(true);
        about.setProgramName("Open Download Manager");
        about.setVersion("0.1.0-SNAPSHOT");
        about.setComments("A full featured native download manager for Linux,\n"
                + "based on aria2, yt-dlp and httrack.");
        about.setWebsite("https://github.com/open-download-manager");
        about.setLicenseType(License.GPL_3_0);
        about.present();
    }
}
