package org.odm.gtk4;

import org.gnome.glib.GLib;

/**
 * Single marshal point between core event threads and the GTK main thread.
 * GTK requires all widget access on the GTK thread; core download events
 * arrive on the odm-events executor thread (see DownloadListener javadoc).
 * Every core-to-UI update MUST go through this class — never call widget
 * methods directly from listener callbacks.
 */
public final class UiThread {

    private UiThread() {
    }

    /**
     * Schedules a task to run once on the GTK main loop. Safe to call from any
     * thread.
     *
     * @param task the UI work to run on the GTK thread
     */
    public static void marshal(Runnable task) {
        GLib.idleAddOnce(task::run);
    }
}
