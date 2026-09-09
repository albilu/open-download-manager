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
import org.gnome.gio.DBusNodeInfo;
import org.gnome.glib.Variant;

/**
 * StatusNotifier tray export. Registers org.kde.StatusNotifierItem on the
 * session bus AND announces it to org.kde.StatusNotifierWatcher — without
 * the watcher registration, panels never discover the item and no icon
 * appears. The desktop renders our exported DBusMenu next to the icon.
 */
public class StatusNotifierTray {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatusNotifierTray.class);

    private static final String OBJECT_PATH = "/org/odm/odm";
    private static final String WATCHER_BUS_NAME = "org.kde.StatusNotifierWatcher";
    private static final String WATCHER_PATH = "/StatusNotifierWatcher";

    private final DBusConnection connection;
    private final Arena callbackArena;
    private TrayMenu menu;
    private int registrationId = -1;
    private boolean registeredWithWatcher = false;
    private boolean unregisterStarted;

    public interface TrayHandlers {

        /** Invoked on the GLib context; handlers marshal action work to GTK. */
        void onAction(String action);
    }

    public StatusNotifierTray(TrayHandlers handlers) {
        DBusConnection conn = null;
        Arena arena = Arena.ofShared();
        try {
            var busAddress = org.gnome.glib.GLib.getenv("DBUS_SESSION_BUS_ADDRESS");
            String address = busAddress == null ? null : busAddress.toString();
            if (address == null || address.isBlank()) {
                throw new IllegalStateException("No session bus address; tray unavailable");
            }
            conn = DBusConnection.forAddressSync(address,
                    EnumSet.of(DBusConnectionFlags.AUTHENTICATION_CLIENT,
                            DBusConnectionFlags.MESSAGE_BUS_CONNECTION), null, null);

            DBusNodeInfo node = DBusNodeInfo.forXml("""
                    <node><interface name="org.kde.StatusNotifierItem">
                      <property name="Title" type="s" access="read"/>
                      <property name="Id" type="s" access="read"/>
                      <property name="Category" type="s" access="read"/>
                      <property name="Status" type="s" access="read"/>
                      <property name="IconName" type="s" access="read"/>
                      <property name="ItemIsMenu" type="b" access="read"/>
                      <property name="WindowId" type="u" access="read"/>
                      <property name="Menu" type="o" access="read"/>
                      <method name="Activate"><arg type="i" direction="in"/><arg type="i" direction="in"/></method>
                      <method name="SecondaryActivate"><arg type="i" direction="in"/><arg type="i" direction="in"/></method>
                    </interface></node>
                    """);
            DBusInterfaceVTable vtable = new DBusInterfaceVTable(
                    (connection, sender, objectPath, interfaceName, methodName, parameters, invocation) -> {
                        if ("Activate".equals(methodName) || "SecondaryActivate".equals(methodName)) {
                            handlers.onAction("open");
                            invocation.returnValue(Variant.tuple(new Variant[0]));
                        } else {
                            invocation.returnDbusError("org.freedesktop.DBus.Error.UnknownMethod",
                                    "Unsupported tray method");
                        }
                    },
                    (connection, sender, objectPath, interfaceName, propertyName, error) ->
                            trayProperty(propertyName),
                    null, arena);

            registrationId = conn.registerObject(OBJECT_PATH, node.lookupInterface("org.kde.StatusNotifierItem"), vtable,
                    MemorySegment.NULL, null);
            menu = new TrayMenu(conn, arena, handlers::onAction);
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
            case "Menu" -> Variant.objectPath(TrayMenu.PATH);
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
                    null, EnumSet.noneOf(DBusCallFlags.class), 5000, null);
            registeredWithWatcher = true;
        } catch (Exception e) {
            LOGGER.warn(
                    "StatusNotifierWatcher not reachable; tray icon will not appear", e);
        }
    }

    /** True only when both the exported item and the desktop watcher are live. */
    public boolean isAvailable() {
        return connection != null && !connection.isClosed()
                && registrationId > 0 && menu != null && registeredWithWatcher;
    }

    void updateActions(java.util.Map<String, TrayMenu.ActionState> states) {
        if (menu != null && !unregisterStarted) { menu.update(states); }
    }

    public synchronized void unregister() {
        if (unregisterStarted) {
            return;
        }
        unregisterStarted = true;
        if (menu != null) { menu.unregister(); }
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
        // leaks a DBus connection per window/tray lifecycle. Close it
        // asynchronously on the GLib context that owns the callback sources:
        // closeSync can block application exit, while closing the callback
        // arena from a worker can race g_main_context_iteration and crash.
        if (connection != null && !connection.isClosed()) {
            try {
                connection.close(null, result -> {
                    try {
                        connection.closeFinish(result);
                    } catch (Exception e) {
                        LOGGER.warn("Error closing tray DBus connection", e);
                    } finally {
                        closeCallbackArena();
                    }
                });
                return;
            } catch (Exception e) {
                LOGGER.warn("Error starting tray DBus connection close", e);
            }
        }
        closeCallbackArena();
    }

    private void closeCallbackArena() {
        if (callbackArena.scope().isAlive()) {
            callbackArena.close();
        }
    }
}
