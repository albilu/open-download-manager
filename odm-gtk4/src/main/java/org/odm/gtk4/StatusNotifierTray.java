package org.odm.gtk4;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gio.DBusCallFlags;
import org.gnome.gio.DBusConnection;
import org.gnome.gio.DBusConnectionFlags;
import org.gnome.gio.DBusInterfaceVTable;
import org.gnome.gio.DBusMethodInfo;
import org.gnome.gio.DBusPropertyInfo;
import org.gnome.gio.DBusPropertyInfoFlags;
import org.gnome.gio.DBusSignalInfo;
import org.gnome.gio.DBusAnnotationInfo;
import org.gnome.gio.DBusArgInfo;
import org.gnome.gio.DBusInterfaceInfo;
import org.gnome.glib.Variant;

/**
 * StatusNotifier tray export. Registers org.kde.StatusNotifierItem on the
 * session bus AND announces it to org.kde.StatusNotifierWatcher — without
 * the watcher registration, panels never discover the item and no icon
 * appears. Currently: icon + Activate (show the main window).
 */
public class StatusNotifierTray {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatusNotifierTray.class);

    private static final String OBJECT_PATH = "/org/odm/odm";
    private static final String WATCHER_BUS_NAME = "org.kde.StatusNotifierWatcher";
    private static final String WATCHER_PATH = "/StatusNotifierWatcher";

    private final DBusConnection connection;
    private final Arena callbackArena;
    private int registrationId = -1;
    private boolean registeredWithWatcher = false;

    public interface TrayHandlers {

        void onActivate();
    }

    public StatusNotifierTray(TrayHandlers handlers) {
        DBusConnection conn = null;
        Arena arena = Arena.ofShared();
        try {
            String address = System.getenv("DBUS_SESSION_BUS_ADDRESS");
            if (address == null || address.isBlank()) {
                throw new IllegalStateException("No session bus address; tray unavailable");
            }
            conn = DBusConnection.forAddressSync(address,
                    EnumSet.of(DBusConnectionFlags.AUTHENTICATION_CLIENT,
                            DBusConnectionFlags.MESSAGE_BUS_CONNECTION), null, null);

            DBusPropertyInfo[] properties = {
                new DBusPropertyInfo(0, "Title", "s",
                        EnumSet.of(DBusPropertyInfoFlags.READABLE), new DBusAnnotationInfo[0], arena),
                new DBusPropertyInfo(0, "Id", "s",
                        EnumSet.of(DBusPropertyInfoFlags.READABLE), new DBusAnnotationInfo[0], arena),
                new DBusPropertyInfo(0, "Category", "s",
                        EnumSet.of(DBusPropertyInfoFlags.READABLE), new DBusAnnotationInfo[0], arena),
                new DBusPropertyInfo(0, "Status", "s",
                        EnumSet.of(DBusPropertyInfoFlags.READABLE), new DBusAnnotationInfo[0], arena),
                new DBusPropertyInfo(0, "IconName", "s",
                        EnumSet.of(DBusPropertyInfoFlags.READABLE), new DBusAnnotationInfo[0], arena),
                new DBusPropertyInfo(0, "ItemIsMenu", "b",
                        EnumSet.of(DBusPropertyInfoFlags.READABLE), new DBusAnnotationInfo[0], arena),
                new DBusPropertyInfo(0, "WindowId", "u",
                        EnumSet.of(DBusPropertyInfoFlags.READABLE), new DBusAnnotationInfo[0], arena)
            };
            DBusArgInfo[] activateArgs = {
                new DBusArgInfo(0, "x", "i", new DBusAnnotationInfo[0], arena),
                new DBusArgInfo(0, "y", "i", new DBusAnnotationInfo[0], arena)
            };
            DBusMethodInfo[] methods = {
                new DBusMethodInfo(0, "Activate", activateArgs, new DBusArgInfo[0],
                        new DBusAnnotationInfo[0], arena),
                new DBusMethodInfo(0, "SecondaryActivate", activateArgs, new DBusArgInfo[0],
                        new DBusAnnotationInfo[0], arena)
            };
            DBusSignalInfo[] signals = new DBusSignalInfo[0];

            DBusInterfaceInfo info = new DBusInterfaceInfo(0, "org.kde.StatusNotifierItem",
                    methods, signals, properties, new DBusAnnotationInfo[0], arena);
            DBusInterfaceVTable vtable = new DBusInterfaceVTable(
                    (connection, sender, objectPath, interfaceName, methodName, parameters, invocation) -> {
                        if ("Activate".equals(methodName) || "SecondaryActivate".equals(methodName)) {
                            handlers.onActivate();
                            invocation.returnValue(Variant.tuple(new Variant[0]));
                        } else {
                            invocation.returnDbusError("org.freedesktop.DBus.Error.UnknownMethod",
                                    "Unsupported tray method");
                        }
                    },
                    (connection, sender, objectPath, interfaceName, propertyName, error) ->
                            trayProperty(propertyName),
                    null, arena);

            registrationId = conn.registerObject(OBJECT_PATH, info, vtable,
                    MemorySegment.NULL, null);
            registerWithWatcher(conn);
            LOGGER.info("StatusNotifierTray registered (id " + registrationId + ")");
        } catch (Exception e) {
            LOGGER.warn("StatusNotifier tray unavailable", e);
        }
        this.connection = conn;
        this.callbackArena = arena;
    }

    private static Variant trayProperty(String propertyName) {
        return switch (propertyName) {
            case "Title" -> Variant.string("Open Download Manager");
            case "Id" -> Variant.string("open-download-manager");
            case "Category" -> Variant.string("ApplicationStatus");
            case "Status" -> Variant.string("Active");
            case "IconName" -> Variant.string("open-download-manager");
            case "ItemIsMenu" -> Variant.boolean_(false);
            case "WindowId" -> Variant.uint32(0);
            default -> null;
        };
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
            LOGGER.warn(
                    "StatusNotifierWatcher not reachable; tray icon will not appear", e);
        }
    }

    /** True only when both the exported item and the desktop watcher are live. */
    public boolean isAvailable() {
        return connection != null && !connection.isClosed()
                && registrationId > 0 && registeredWithWatcher;
    }

    public void unregister() {
        if (connection != null && registrationId >= 0) {
            try {
                connection.unregisterObject(registrationId);
                LOGGER.info("StatusNotifierTray unregistered"
                        + (registeredWithWatcher ? " (watcher notified at registration)" : ""));
            } catch (Exception e) {
                LOGGER.warn("Error unregistering tray object", e);
            }
            registrationId = -1;
        }
        // The tray owns a dedicated session-bus connection; leaving it open
        // leaks a DBus connection per window/tray lifecycle
        if (connection != null) {
            try {
                connection.closeSync(null);
            } catch (Exception e) {
                LOGGER.warn("Error closing tray DBus connection", e);
            }
        }
        if (callbackArena.scope().isAlive()) {
            callbackArena.close();
        }
    }
}
