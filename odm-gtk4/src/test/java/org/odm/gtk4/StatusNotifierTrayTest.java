package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.gnome.gio.DBusCallFlags;
import org.gnome.gio.DBusConnection;
import org.gnome.gio.DBusConnectionFlags;
import org.gnome.gio.DBusInterfaceVTable;
import org.gnome.gio.DBusNodeInfo;
import org.gnome.gio.DBusSignalFlags;
import org.gnome.gio.TestDBus;
import org.gnome.gio.TestDBusFlags;
import org.gnome.glib.MainContext;
import org.gnome.glib.Variant;
import org.gnome.glib.VariantType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class StatusNotifierTrayTest {
    private static TestDBus bus;

    @BeforeAll
    static void startBus() throws Exception {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        bus = new TestDBus(TestDBusFlags.NONE);
        bus.up();
    }

    @AfterAll
    static void stopBus() {
        bus.stop();
        TestDBus.unset();
    }

    @Test
    void desktopDiscoversMenuInvokesActionsAndReceivesLiveStateChanges() throws Exception {
        DBusConnection desktop = DBusConnection.forAddressSync(bus.getBusAddress(),
                EnumSet.of(DBusConnectionFlags.AUTHENTICATION_CLIENT,
                        DBusConnectionFlags.MESSAGE_BUS_CONNECTION), null, null);
        try (Arena arena = Arena.ofShared()) {
            desktop.callSync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                    "RequestName", tuple(Variant.string("org.kde.StatusNotifierWatcher"), Variant.uint32(4)),
                    new VariantType("(u)"), DBusCallFlags.NONE, 5000, null);
            var watcher = DBusNodeInfo.forXml("""
                    <node><interface name="org.kde.StatusNotifierWatcher">
                    <method name="RegisterStatusNotifierItem"><arg type="s" direction="in"/></method>
                    </interface></node>
                    """);
            AtomicReference<String> owner = new AtomicReference<>();
            AtomicReference<String> path = new AtomicReference<>();
            var callbacks = new DBusInterfaceVTable((conn, sender, object, iface, method, params, invocation) -> {
                owner.set(sender);
                path.set(params.getChildValue(0).dupString(null));
                invocation.returnValue(tuple());
            }, null, null, arena);
            int registration = desktop.registerObject("/StatusNotifierWatcher",
                    watcher.lookupInterface("org.kde.StatusNotifierWatcher"), callbacks, MemorySegment.NULL, null);
            List<String> invoked = new ArrayList<>();
            StatusNotifierTray tray = await(CompletableFuture.supplyAsync(() -> new StatusNotifierTray(invoked::add)));
            try {
                assertTrue(tray.isAvailable());
                assertNotNull(owner.get());
                Variant menuPath = call(desktop, owner.get(), path.get(), "org.freedesktop.DBus.Properties",
                        "Get", tuple(Variant.string("org.kde.StatusNotifierItem"), Variant.string("Menu")));
                assertEquals(TrayMenu.PATH, menuPath.getChildValue(0).getVariant().dupString(null));

                Map<String, TrayMenu.ActionState> states = new LinkedHashMap<>();
                for (TrayMenu.Entry entry : TrayMenu.ENTRIES) {
                    if (entry.action() != null) { states.put(entry.action(), new TrayMenu.ActionState(true, false)); }
                }
                tray.updateActions(states);
                Variant response = menuCall(desktop, owner.get(), "GetLayout",
                        tuple(Variant.int32(0), Variant.int32(-1), strings()));
                assertEquals("(u(ia{sv}av))", response.getTypeString());
                Variant children = response.getChildValue(1).getChildValue(2);
                List<String> labels = new ArrayList<>();
                for (int i = 0; i < children.nChildren(); i++) {
                    Variant row = children.getChildValue(i).getVariant();
                    Variant properties = row.getChildValue(1);
                    Variant label = properties.lookupValue("label", null);
                    if (label != null) { labels.add(label.dupString(null)); }
                }
                assertEquals(List.of("Open", "New Download", "Pause All", "Resume All",
                        "Clipboard", "Silent Mode", "Offline Mode", "Exit"), labels);
                assertEquals("checkmark", property(desktop, owner.get(), 7, "toggle-type").dupString(null));
                assertEquals(0, property(desktop, owner.get(), 7, "toggle-state").getInt32());

                // Every listed command is a real DBusMenu click, not a direct Java call.
                for (TrayMenu.Entry entry : TrayMenu.ENTRIES) {
                    if (entry.action() == null) { continue; }
                    menuCall(desktop, owner.get(), "Event", event(entry.id(), "clicked"));
                }
                assertEquals(List.of("open", "new-download", "pause-all", "resume-all",
                        "clipboard-monitoring", "clipboard-silent", "offline", "quit"), invoked);
                call(desktop, owner.get(), path.get(), "org.kde.StatusNotifierItem", "Activate",
                        tuple(Variant.int32(0), Variant.int32(0)));
                assertEquals("open", invoked.getLast());

                CompletableFuture<List<Integer>> changed = new CompletableFuture<>();
                int subscription = desktop.signalSubscribe(owner.get(), TrayMenu.INTERFACE,
                        "ItemsPropertiesUpdated", TrayMenu.PATH, null, DBusSignalFlags.NONE,
                        (conn, sender, object, iface, signal, params) -> {
                            // Signal parameters are borrowed until this callback returns.
                            try {
                                assertEquals("(a(ia{sv})a(ias))", params.getTypeString());
                                List<Integer> ids = new ArrayList<>();
                                Variant updates = params.getChildValue(0);
                                for (int i = 0; i < updates.nChildren(); i++) {
                                    ids.add(updates.getChildValue(i).getChildValue(0).getInt32());
                                }
                                changed.complete(ids);
                            } catch (Throwable failure) { changed.completeExceptionally(failure); }
                        });
                try {
                    // Round-trip before emitting so AddMatch has reached the bus.
                    desktop.callSync("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                            "GetId", tuple(), null, DBusCallFlags.NONE, 5000, null);
                    states.put("pause-all", new TrayMenu.ActionState(false, false));
                    states.put("clipboard-monitoring", new TrayMenu.ActionState(true, true));
                    tray.updateActions(states);
                    assertEquals(List.of(4, 7), await(changed));
                    assertFalse(property(desktop, owner.get(), 4, "enabled").getBoolean());
                    assertEquals(1, property(desktop, owner.get(), 7, "toggle-state").getInt32());
                    int before = invoked.size();
                    menuCall(desktop, owner.get(), "Event", event(4, "clicked"));
                    menuCall(desktop, owner.get(), "Event", event(7, "hovered"));
                    assertEquals(before, invoked.size(), "Disabled commands and hover events must not activate");
                } finally {
                    desktop.signalUnsubscribe(subscription);
                }

                Variant filtered = menuCall(desktop, owner.get(), "GetLayout",
                        tuple(Variant.int32(7), Variant.int32(0), strings("enabled")))
                        .getChildValue(1).getChildValue(1);
                assertEquals(1, filtered.nChildren());
                assertNotNull(filtered.lookupValue("enabled", null));
                assertNull(filtered.lookupValue("label", null));
                Variant group = menuCall(desktop, owner.get(), "GetGroupProperties",
                        tuple(array("i", Variant.int32(7), Variant.int32(9)), strings("toggle-state")));
                assertEquals(2, group.getChildValue(0).nChildren());
                assertFalse(menuCall(desktop, owner.get(), "AboutToShow", tuple(Variant.int32(0)))
                        .getChildValue(0).getBoolean());
                Variant groupEvent = menuCall(desktop, owner.get(), "EventGroup",
                        tuple(array("(isvu)", event(1, "clicked"), event(999, "clicked"))));
                assertEquals(999, groupEvent.getChildValue(0).getChildValue(0).getInt32());
                assertEquals("open", invoked.getLast());
                assertThrows(java.util.concurrent.CompletionException.class, () -> menuCall(
                        desktop, owner.get(), "GetLayout", tuple(Variant.int32(-1), Variant.int32(-1), strings())));
            } finally {
                tray.unregister();
                tray.unregister();
                assertFalse(tray.isAvailable());
                // The connection goes away asynchronously; this call either observes
                // the unregistered object or the departed bus name.
                assertThrows(java.util.concurrent.CompletionException.class, () -> menuCall(
                        desktop, owner.get(), "AboutToShow", tuple(Variant.int32(0))));
                desktop.unregisterObject(registration);
                while (MainContext.default_().pending()) { MainContext.default_().iteration(false); }
            }
        } finally {
            desktop.closeSync(null);
        }
    }

    private static Variant property(DBusConnection desktop, String owner, int id, String name) {
        return menuCall(desktop, owner, "GetProperty", tuple(Variant.int32(id), Variant.string(name)))
                .getChildValue(0).getVariant();
    }
    private static Variant menuCall(DBusConnection desktop, String owner, String method, Variant args) {
        return call(desktop, owner, TrayMenu.PATH, TrayMenu.INTERFACE, method, args);
    }
    private static Variant call(DBusConnection desktop, String owner, String path,
            String iface, String method, Variant args) {
        CompletableFuture<Variant> result = new CompletableFuture<>();
        desktop.call(owner, path, iface, method, args, null, DBusCallFlags.NONE, 5000, null, reply -> {
            try { result.complete(desktop.callFinish(reply)); }
            catch (Exception failure) { result.completeExceptionally(failure); }
        });
        return await(result);
    }
    private static <T> T await(CompletableFuture<T> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!future.isDone() && System.nanoTime() < deadline) {
            MainContext.default_().iteration(false);
            try { Thread.sleep(2); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        }
        assertTrue(future.isDone(), "D-Bus request did not complete");
        return future.join();
    }
    private static Variant event(int id, String kind) {
        return tuple(Variant.int32(id), Variant.string(kind), Variant.variant(Variant.int32(0)), Variant.uint32(0));
    }
    private static Variant tuple(Variant... values) { return Variant.tuple(values); }
    private static Variant array(String type, Variant... values) { return Variant.array(new VariantType(type), values); }
    private static Variant strings(String... values) {
        return array("s", java.util.Arrays.stream(values).map(Variant::string).toArray(Variant[]::new));
    }
}
