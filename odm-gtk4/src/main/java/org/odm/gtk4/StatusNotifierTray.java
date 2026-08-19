package org.odm.gtk4;

import java.util.EnumSet;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
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
 * session bus so a tray icon appears in StatusNotifier-compatible panels
 * (KDE/XFCE natively; GNOME via extension). Currently: icon + Activate (show
 * the main window).
 */
public class StatusNotifierTray {

    private static final Logger LOGGER = Logger.getLogger(StatusNotifierTray.class.getName());

    private final DBusConnection connection;
    private int registrationId = -1;

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

            JavaClosure getProperty = new JavaClosure((Supplier<Variant>) () -> {
                Variant v = new Variant("oDM");
                return v;
            });
            JavaClosure setProperty = new JavaClosure((Runnable) () -> { /* ignore writes */ });
            JavaClosure methodCall = new JavaClosure((Runnable) handlers::onActivate);

            registrationId = conn.registerObjectWithClosures("/org/odm/odm", info,
                    getProperty, setProperty, methodCall);
            LOGGER.info("StatusNotifierTray registered (id " + registrationId + ")");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "StatusNotifier tray unavailable", e);
        }
        this.connection = conn;
    }

    public void unregister() {
        if (connection != null && registrationId >= 0) {
            connection.unregisterObject(registrationId);
            LOGGER.info("StatusNotifierTray unregistered");
        }
    }
}
