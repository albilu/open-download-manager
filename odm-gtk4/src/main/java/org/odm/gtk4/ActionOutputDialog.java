package org.odm.gtk4;

import org.gnome.gtk.Button;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.TextView;
import org.gnome.gtk.Window;

/** Read-only log window for the detailed output of one completion action. */
final class ActionOutputDialog {

    private ActionOutputDialog() {
    }

    static void present(Window parent, String action, String status,
            String result, String output) {
        String actionLabel = display(action, "Completion action");
        GtkBuilder builder = UiLoader.load("/ui/action-output.ui");
        Window dialog = Widgets.require(builder, "action_output_dialog", Window.class);
        dialog.setTitle("Action Output — " + actionLabel);
        DialogSupport.configureIndependent(dialog, parent);

        Label actionValue = Widgets.require(builder,
                "action_output_action_label", Label.class);
        actionValue.setLabel(actionLabel);

        Label outcome = Widgets.require(builder, "action_output_result_label", Label.class);
        outcome.setLabel("Status: " + display(status, "—")
                + "\nResult: " + display(result, "—"));

        TextView log = Widgets.require(builder, "action_output_text_view", TextView.class);
        String displayedOutput = output == null || output.isBlank()
                ? ("Running".equals(status)
                        ? "This action is still running. Detailed output will be available when it finishes."
                        : "This action did not produce detailed output.")
                : output;
        log.getBuffer().setText(displayedOutput, -1);
        AccessibilitySupport.label(log, "Detailed output for " + actionLabel);

        Widgets.require(builder, "action_output_close_button", Button.class)
                .onClicked(dialog::close);
        dialog.present();
    }

    private static String display(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
