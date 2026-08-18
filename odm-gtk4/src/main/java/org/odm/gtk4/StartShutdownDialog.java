package org.odm.gtk4;

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

    public StartShutdownDialog(Window parent) {
        GtkBuilder builder = UiLoader.load("/ui/start-shutdown.ui");
        this.dialog = Widgets.require(builder, "startup_shutdown_dialog", Window.class);
        this.statusMessageLabel = Widgets.require(builder, "status_message_label", Label.class);
        this.progressBar = Widgets.require(builder, "progress_bar", ProgressBar.class);
        if (parent != null) {
            dialog.setTransientFor(parent);
        }
    }

    public void present() {
        dialog.present();
    }

    public void close() {
        dialog.close();
    }

    public void setMessage(String message) {
        UiThread.marshal(() -> statusMessageLabel.setLabel(message));
    }

    /** Pulses the bar for indeterminate work. */
    public void pulse() {
        UiThread.marshal(progressBar::pulse);
    }

    public void setFraction(double fraction) {
        UiThread.marshal(() -> progressBar.setFraction(fraction));
    }
}
