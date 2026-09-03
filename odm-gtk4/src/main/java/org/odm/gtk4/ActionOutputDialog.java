package org.odm.gtk4;

import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.TextView;
import org.gnome.gtk.Window;
import org.gnome.gtk.WrapMode;

/** Read-only log window for the detailed output of one completion action. */
final class ActionOutputDialog {

    private ActionOutputDialog() {
    }

    static void present(Window parent, String action, String status,
            String result, String output) {
        String actionLabel = display(action, "Completion action");
        Window dialog = new Window();
        dialog.setTitle("Action Output — " + actionLabel);
        dialog.setTransientFor(parent);
        dialog.setDestroyWithParent(true);
        dialog.setModal(true);
        dialog.setDefaultSize(760, 480);

        Box content = new Box(Orientation.VERTICAL, 8);
        content.setMarginTop(12);
        content.setMarginBottom(12);
        content.setMarginStart(12);
        content.setMarginEnd(12);

        Label actionValue = new Label(actionLabel);
        actionValue.setXalign(0);
        actionValue.setSelectable(true);
        actionValue.addCssClass("heading");
        content.append(actionValue);

        Label outcome = new Label("Status: " + display(status, "—")
                + "\nResult: " + display(result, "—"));
        outcome.setXalign(0);
        outcome.setSelectable(true);
        outcome.setWrap(true);
        content.append(outcome);

        TextView log = new TextView();
        log.setEditable(false);
        log.setMonospace(true);
        log.setWrapMode(WrapMode.WORD_CHAR);
        log.setLeftMargin(8);
        log.setRightMargin(8);
        log.setTopMargin(8);
        log.setBottomMargin(8);
        String displayedOutput = output == null || output.isBlank()
                ? ("Running".equals(status)
                        ? "This action is still running. Detailed output will be available when it finishes."
                        : "This action did not produce detailed output.")
                : output;
        log.getBuffer().setText(displayedOutput, -1);
        AccessibilitySupport.label(log, "Detailed output for " + actionLabel);

        ScrolledWindow scroller = new ScrolledWindow();
        scroller.setHexpand(true);
        scroller.setVexpand(true);
        scroller.setChild(log);
        content.append(scroller);

        Button close = Button.withLabel("Close");
        close.setHalign(Align.END);
        close.onClicked(dialog::close);
        content.append(close);

        dialog.setChild(content);
        dialog.present();
    }

    private static String display(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
