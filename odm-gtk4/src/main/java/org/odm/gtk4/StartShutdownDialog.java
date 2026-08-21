package org.odm.gtk4;

import org.gnome.glib.Source;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ProgressBar;
import org.gnome.gtk.Window;

/**
 * Startup/shutdown progress dialog — 1:1 GTK4 port of start-shutdown.glade.
 * Shown while the core initializes (or during graceful shutdown), with a
 * message label and an activity progress bar.
 */
public class StartShutdownDialog {

    private final Window dialog;
    private final Label statusMessageLabel;
    private final ProgressBar progressBar;

    /** GLib source id of the pulse timer; 0 when not pulsing. */
    private int pulseSourceId;

    public StartShutdownDialog(Window parent) {
        GtkBuilder builder = UiLoader.load("/ui/start-shutdown.ui");
        this.dialog = Widgets.require(builder, "startup_shutdown_dialog", Window.class);
        this.statusMessageLabel = Widgets.require(builder, "status_message_label", Label.class);
        this.progressBar = Widgets.require(builder, "progress_bar", ProgressBar.class);
        org.gnome.gtk.Image logo = Widgets.require(builder, "odm_logo_image", org.gnome.gtk.Image.class);
        org.gnome.gdk.Texture texture = AboutDialogPresenter.loadLogo();
        if (texture != null) {
            logo.setFromPaintable(texture);
        }
        if (parent != null) {
            dialog.setTransientFor(parent);
        }
    }

    /**
     * Creates the dialog and registers it with the application. A plain
     * GtkWindow is INVISIBLE to GtkApplication: with no tracked windows the
     * main loop quits right after activate, killing the app while the core
     * initializes. Registering keeps the loop alive for the dialog's
     * lifetime (destroying the window unregisters it again).
     *
     * @param app the owning application
     */
    public StartShutdownDialog(org.gnome.gtk.Application app) {
        this((Window) null);
        app.addWindow(dialog);
    }

    /**
     * Shows the dialog with an initial message and a self-pulsing activity
     * bar for indeterminate work (core init / graceful shutdown have no
     * meaningful percentage). The pulse timer stops on {@link #close()}.
     *
     * @param message the initial status message
     */
    public void show(String message) {
        statusMessageLabel.setLabel(message);
        dialog.present();
        if (pulseSourceId == 0) {
            pulseSourceId = org.gnome.glib.GLib.timeoutAdd(
                    org.gnome.glib.GLib.PRIORITY_DEFAULT, 100,
                    () -> {
                        progressBar.pulse();
                        return true; // keep pulsing until close()
                    });
        }
    }

    /** Updates the status message. Safe from any thread. */
    public void setMessage(String message) {
        UiThread.marshal(() -> statusMessageLabel.setLabel(message));
    }

    /** Closes and destroys the dialog, stopping the pulse timer. */
    public void close() {
        if (pulseSourceId != 0) {
            Source.remove(pulseSourceId);
            pulseSourceId = 0;
        }
        dialog.destroy();
    }
}
