package org.odm.gtk4;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnome.gio.DBusCallFlags;
import org.gnome.gio.DBusConnection;
import org.gnome.gio.DBusConnectionFlags;
import org.gnome.gio.DBusMethodInfo;
import org.gnome.gio.DBusPropertyInfo;
import org.gnome.gio.DBusPropertyInfoFlags;
import org.gnome.gio.DBusSignalInfo;
import org.gnome.gio.DBusAnnotationInfo;
import org.gnome.gio.DBusArgInfo;
import org.gnome.gio.DBusInterfaceInfo;
import org.gnome.glib.Variant;
import org.javagi.gobject.JavaClosure;

/**
 * StatusNotifier tray export. Registers org.kde.StatusNotifierItem on the
 * session bus AND announces it to org.kde.StatusNotifierWatcher — without
 * the watcher registration, panels never discover the item and no icon
 * appears. Currently: icon + Activate (show the main window).
 */
public class StatusNotifierTray {

    private static final Logger LOGGER = Logger.getLogger(StatusNotifierTray.class.getName());

    private static final String OBJECT_PATH = "/org/odm/odm";
    private static final String WATCHER_BUS_NAME = "org.kde.StatusNotifierWatcher";
    private static final String WATCHER_PATH = "/StatusNotifierWatcher";

    private final DBusConnection connection;
    private int registrationId = -1;
    private boolean registeredWithWatcher = false;

    public interface TrayHandlers {

        void onActivate();
    }

    public StatusNotifierTray(TrayHandlers handlers) {
        DBusConnection conn = null;
        try {
            String address = System.getenv("DBUS_SESSION_BUS_ADDRESS");
            if (address == null || address.isBlank()) {
                throw new IllegalStateException("No session bus address; tray unavailable");
            }
            conn = DBusConnection.forAddressSync(address, EnumSet.noneOf(DBusConnectionFlags.class), null, null);

            DBusPropertyInfo[] properties = {
                new DBusPropertyInfo(0, "Title", "s",
                        EnumSet.of(DBusPropertyInfoFlags.READABLE), new DBusAnnotationInfo[0]),
                new DBusPropertyInfo(0, "Id", "s",
                        EnumSet.of(DBusPropertyInfoFlags.READABLE), new DBusAnnotationInfo[0]),
                new DBusPropertyInfo(0, "Status", "s",
                        EnumSet.of(DBusPropertyInfoFlags.READABLE), new DBusAnnotationInfo[0]),
                new DBusPropertyInfo(0, "IconName", "s",
                        EnumSet.of(DBusPropertyInfoFlags.READABLE), new DBusAnnotationInfo[0])
            };
            DBusMethodInfo[] methods = {
                new DBusMethodInfo(0, "Activate", new DBusArgInfo[0], new DBusArgInfo[0],
                        new DBusAnnotationInfo[0])
            };
            DBusSignalInfo[] signals = new DBusSignalInfo[0];

            DBusInterfaceInfo info = new DBusInterfaceInfo(0, "org.kde.StatusNotifierItem",
                    methods, signals, properties, new DBusAnnotationInfo[0]);

            // The closure shape exposes no per-property context; all four
            // properties are strings, and a human-readable title is the only
            // sane shared value (the old code returned "oDM" for everything)
            JavaClosure getProperty = new JavaClosure((Supplier<Variant>) () ->
                    Variant.string("Open Download Manager"));
            JavaClosure setProperty = new JavaClosure((Runnable) () -> { /* ignore writes */ });
            JavaClosure methodCall = new JavaClosure((Runnable) handlers::onActivate);

            registrationId = conn.registerObjectWithClosures(OBJECT_PATH, info,
                    getProperty, setProperty, methodCall);
            registerWithWatcher(conn);
            LOGGER.info("StatusNotifierTray registered (id " + registrationId + ")");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "StatusNotifier tray unavailable", e);
        }
        this.connection = conn;
    }

    /**
     * Announces the exported item to the watcher so panels actually show it.
     * Best-effort: older watchers or missing hosts only mean "no icon".
     */
    private void registerWithWatcher(DBusConnection conn) {
        try {
            conn.callSync(WATCHER_BUS_NAME, WATCHER_PATH, WATCHER_BUS_NAME,
                    "RegisterStatusNotifierItem",
                    Variant.tuple(new Variant[]{Variant.string(OBJECT_PATH)}),
                    null, EnumSet.noneOf(DBusCallFlags.class), -1, null);
            registeredWithWatcher = true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "StatusNotifierWatcher not reachable; tray icon will not appear", e);
        }
    }

    public void unregister() {
        if (connection != null && registrationId >= 0) {
            try {
                connection.unregisterObject(registrationId);
                LOGGER.info("StatusNotifierTray unregistered"
                        + (registeredWithWatcher ? " (watcher notified at registration)" : ""));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error unregistering tray object", e);
            }
            registrationId = -1;
        }
        // The tray owns a dedicated session-bus connection; leaving it open
        // leaks a DBus connection per window/tray lifecycle
        if (connection != null) {
            try {
                connection.closeSync(null);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error closing tray DBus connection", e);
            }
        }
    }
}
