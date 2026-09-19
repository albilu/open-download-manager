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
        present(parent, I18n.format("Action Output — %s", display(action, I18n.tr("Completion action"))),
                action, I18n.format("Status: %s\nResult: %s", display(status, "—"), UiErrors.message(display(result, "—"))),
                output, status);
    }

    static void presentError(Window parent, String name, String error) {
        present(parent, I18n.tr("Download Error"), name, I18n.tr("Download error details"), error, I18n.tr("Error"));
    }

    private static void present(Window parent, String title, String action,
            String summary, String output, String status) {
        String actionLabel = display(action, I18n.tr("Completion action"));
        GtkBuilder builder = UiLoader.load("/ui/action-output.ui");
        Window dialog = Widgets.require(builder, "action_output_dialog", Window.class);
        dialog.setTitle(title);
        DialogSupport.configureIndependent(dialog, parent);

        Label actionValue = Widgets.require(builder,
                "action_output_action_label", Label.class);
        actionValue.setLabel(actionLabel);

        Label outcome = Widgets.require(builder, "action_output_result_label", Label.class);
        outcome.setLabel(summary);

        TextView log = Widgets.require(builder, "action_output_text_view", TextView.class);
        String displayedOutput = output == null || output.isBlank()
                ? (I18n.tr("Running").equals(status)
                        ? I18n.tr("This action is still running. Detailed output will be available when it finishes.")
                        : I18n.tr("This action did not produce detailed output."))
                : UiErrors.details(output);
        log.getBuffer().setText(displayedOutput, -1);
        AccessibilitySupport.label(log, I18n.format("Detailed output for %s", actionLabel));

        Widgets.require(builder, "action_output_close_button", Button.class)
                .onClicked(dialog::close);
        dialog.present();
    }

    private static String display(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
