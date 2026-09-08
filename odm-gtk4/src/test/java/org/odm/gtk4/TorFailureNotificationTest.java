package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.gnome.gio.DBusCallFlags;
import org.gnome.gio.DBusConnection;
import org.gnome.gio.DBusConnectionFlags;
import org.gnome.gio.DBusInterfaceVTable;
import org.gnome.gio.DBusNodeInfo;
import org.gnome.gio.TestDBus;
import org.gnome.gio.TestDBusFlags;
import org.gnome.glib.MainContext;
import org.gnome.glib.Variant;
import org.gnome.glib.VariantType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
@Timeout(15)
class TorFailureNotificationTest {
    @BeforeAll
    static void initializeNativeBindings() throws Exception {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
    }

    @Test
    void sendsCriticalNotificationToAnIsolatedDesktopBus() throws Exception {
        TestDBus bus = new TestDBus(TestDBusFlags.NONE);
        bus.up();
        DBusConnection connection = null;
        try (Arena arena = Arena.ofShared()) {
            connection = DBusConnection.forAddressSync(bus.getBusAddress(),
                    EnumSet.of(DBusConnectionFlags.AUTHENTICATION_CLIENT,
                            DBusConnectionFlags.MESSAGE_BUS_CONNECTION), null, null);
            connection.callSync("org.freedesktop.DBus", "/org/freedesktop/DBus",
                    "org.freedesktop.DBus", "RequestName", Variant.tuple(new Variant[]{
                            Variant.string("org.freedesktop.Notifications"), Variant.uint32(4)}),
                    new VariantType("(u)"), DBusCallFlags.NONE, 5000, null);
            DBusNodeInfo node = DBusNodeInfo.forXml("""
                    <node><interface name="org.freedesktop.Notifications"><method name="Notify">
                    <arg type="s" direction="in"/><arg type="u" direction="in"/>
                    <arg type="s" direction="in"/><arg type="s" direction="in"/>
                    <arg type="s" direction="in"/><arg type="as" direction="in"/>
                    <arg type="a{sv}" direction="in"/><arg type="i" direction="in"/>
                    <arg type="u" direction="out"/>
                    </method></interface></node>
                    """);
            AtomicReference<AssertionError> error = new AtomicReference<>();
            String message = "Tor check failed — Offline Mode enabled.";
            DBusInterfaceVTable vtable = new DBusInterfaceVTable(
                    (conn, sender, path, iface, method, parameters, invocation) -> {
                        try {
                            assertEquals("Notify", method);
                            assertEquals("(susssasa{sv}i)", parameters.getTypeString());
                            assertEquals("oDM", parameters.getChildValue(0).dupString(null));
                            assertEquals("Tor verification failed", parameters.getChildValue(3).dupString(null));
                            assertEquals(message, parameters.getChildValue(4).dupString(null));
                            assertEquals(2, parameters.getChildValue(6)
                                    .lookupValue("urgency", new VariantType("y")).getByte());
                            assertEquals(0, parameters.getChildValue(7).getInt32());
                        } catch (AssertionError failure) {
                            error.set(failure);
                        }
                        invocation.returnValue(Variant.tuple(new Variant[]{Variant.uint32(1)}));
                    }, null, null, arena);
            int registration = connection.registerObject("/org/freedesktop/Notifications",
                    node.lookupInterface("org.freedesktop.Notifications"), vtable, MemorySegment.NULL, null);
            try {
                CompletableFuture<Void> sent = TorFailureNotification.send(connection, message);
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
                while (!sent.isDone() && System.nanoTime() < deadline) {
                    MainContext.default_().iteration(false);
                    Thread.sleep(5);
                }
                assertTrue(sent.isDone());
                sent.join();
                assertNull(error.get(), () -> String.valueOf(error.get()));
            } finally {
                connection.unregisterObject(registration);
            }
        } finally {
            if (connection != null) {
                connection.closeSync(null);
            }
            bus.down();
        }
    }

    @Test
    void statusShowsCountryFlagAndIpAndToleratesUnknownCountry() {
        assertEquals("Tor verified — 🇫🇷 192.0.2.1", MainWindow.torCheckStatus(
                new org.tor.TorCircuitMonitor.Result(true, "verified", "192.0.2.1", "fr", 0)));
        assertEquals("Tor verified — 2001:db8::1", MainWindow.torCheckStatus(
                new org.tor.TorCircuitMonitor.Result(true, "verified", "2001:db8::1", null, 0)));
        assertEquals("Tor verified — 192.0.2.1", MainWindow.torCheckStatus(
                new org.tor.TorCircuitMonitor.Result(true, "verified", "192.0.2.1", "??", 0)));
        assertTrue(MainWindow.torCheckStatus(new org.tor.TorCircuitMonitor.Result(
                false, "Unable to verify the Tor circuit", null, null, 0)).contains("Offline Mode enabled"));
    }
}
