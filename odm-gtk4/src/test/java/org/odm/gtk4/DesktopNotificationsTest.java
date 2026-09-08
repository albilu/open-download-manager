package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.gnome.gio.Application;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gio.BusType;
import org.gnome.gio.DBusCallFlags;
import org.gnome.gio.DBusConnection;
import org.gnome.gio.DBusConnectionFlags;
import org.gnome.gio.DBusInterfaceVTable;
import org.gnome.gio.DBusNodeInfo;
import org.gnome.gio.NotificationPriority;
import org.gnome.gio.Gio;
import org.gnome.gio.TestDBus;
import org.gnome.gio.TestDBusFlags;
import org.gnome.glib.GLib;
import org.gnome.glib.MainContext;
import org.gnome.glib.Variant;
import org.gnome.glib.VariantType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.GlobalSettings;
import org.manager.download.Download;

@Execution(ExecutionMode.SAME_THREAD)
@Timeout(20)
class DesktopNotificationsTest {
    @TempDir
    static Path dataHome;
    private static TestDBus bus;
    private static DBusConnection applicationBus;

    @BeforeAll
    static void initializeNativeBindings() throws Exception {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        GLib.setenv("XDG_DATA_HOME", dataHome.toString(), true);
        GLib.setApplicationName("oDM");
        assertEquals(dataHome.toString(), GLib.getUserDataDir().toString());
        bus = new TestDBus(TestDBusFlags.NONE);
        bus.up();
        applicationBus = Gio.busGetSync(BusType.SESSION, null);
        applicationBus.setExitOnClose(false);
    }

    @AfterAll
    static void stopIsolatedDesktop() throws Exception {
        if (applicationBus != null) {
            applicationBus.closeSync(null);
        }
        if (bus != null) {
            // Each test class has its own JVM. Stop our daemon without waiting
            // for Java-GI's cached GObject wrappers to be garbage collected.
            bus.stop();
            TestDBus.unset();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionActionSendsTheDownloadNameAndOdmLogo(boolean installed) throws Exception {
        verifyDelivery(installed, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void torFailureUsesTheSameDesktopBackendAtCriticalPriority(boolean installed) throws Exception {
        verifyDelivery(installed, true);
    }

    @Test
    void canceledWorkDoesNotReachTheDesktop() {
        Application app = org.mockito.Mockito.mock(Application.class);
        var sent = DesktopNotifications.send(app, "canceled", "Canceled", "Canceled",
                NotificationPriority.NORMAL);
        assertTrue(sent.cancel(true));
        while (MainContext.default_().pending()) {
            MainContext.default_().iteration(false);
        }
        org.mockito.Mockito.verifyNoInteractions(app);
    }

    @Test
    void anUnregisteredApplicationReportsDeliveryFailure() {
        Application app = new Application("org.odm.Unregistered", ApplicationFlags.NON_UNIQUE);
        var sent = DesktopNotifications.send(app, "unregistered", "Completed", "Completed",
                NotificationPriority.NORMAL);
        while (!sent.isDone()) {
            MainContext.default_().iteration(false);
        }
        assertThrows(java.util.concurrent.CompletionException.class, sent::join);
    }

    private void verifyDelivery(boolean installed, boolean torFailure) throws Exception {
        String applicationId = "org.odm.NotificationTest" + (installed ? "Installed" : "Local")
                + (torFailure ? "Tor" : "Completion");
        if (installed) {
            Path desktop = dataHome.resolve("applications/" + applicationId + ".desktop");
            Files.createDirectories(desktop.getParent());
            Files.writeString(desktop, """
                    [Desktop Entry]
                    Type=Application
                    Name=oDM
                    Exec=true
                    Icon=open-download-manager
                    X-GNOME-UsesNotifications=true
                    """);
        }
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
            Download download = new Download(URI.create("https://example.com/video.mp4"));
            download.setName("Lecture <part 1> & notes.mp4");
            String title = torFailure ? "Tor verification failed" : "Download completed";
            String message = torFailure ? "Tor check failed — Offline Mode enabled."
                    : download.getName() + " has completed.";
            CompletableFuture<Void> received = new CompletableFuture<>();
            DBusInterfaceVTable vtable = new DBusInterfaceVTable(
                    (conn, sender, path, iface, method, parameters, invocation) -> {
                        try {
                            assertEquals("Notify", method);
                            assertEquals("oDM", parameters.getChildValue(0).dupString(null));
                            assertEquals(title, parameters.getChildValue(3).dupString(null));
                            assertEquals(installed ? message : GLib.markupEscapeText(message, -1),
                                    parameters.getChildValue(4).dupString(null));
                            Variant hints = parameters.getChildValue(6);
                            assertEquals(torFailure ? 2 : 1,
                                    hints.lookupValue("urgency", new VariantType("y")).getByte());
                            assertEquals(torFailure ? "network.error" : "transfer.complete",
                                    hints.lookupValue("category", new VariantType("s")).dupString(null));
                            if (installed) {
                                assertEquals(applicationId, hints.lookupValue(
                                        "desktop-entry", new VariantType("s")).dupString(null));
                                assertEquals("open-download-manager", hints.lookupValue(
                                        "image-path", new VariantType("s")).dupString(null));
                            } else {
                                assertEquals("open-download-manager", parameters.getChildValue(2).dupString(null));
                                Variant logo = hints.lookupValue("image-data", new VariantType("(iiibiiay)"));
                                assertNotNull(logo);
                                assertEquals(128, logo.getChildValue(0).getInt32());
                                assertEquals(128, logo.getChildValue(1).getInt32());
                                assertEquals(512, logo.getChildValue(2).getInt32());
                                assertTrue(logo.getChildValue(3).getBoolean());
                                assertEquals(65536, logo.getChildValue(6).nChildren());
                            }
                            assertEquals(torFailure && !installed ? 0 : -1,
                                    parameters.getChildValue(7).getInt32());
                            received.complete(null);
                        } catch (Throwable failure) {
                            received.completeExceptionally(failure);
                        }
                        invocation.returnValue(Variant.tuple(new Variant[]{Variant.uint32(1)}));
                    }, null, null, arena);
            int registration = connection.registerObject("/org/freedesktop/Notifications",
                    node.lookupInterface("org.freedesktop.Notifications"), vtable, MemorySegment.NULL, null);
            try {
                Application app = new Application(applicationId, ApplicationFlags.NON_UNIQUE);
                app.setDefault();
                AtomicReference<CompletableFuture<?>> delivery = new AtomicReference<>();
                app.onActivate(() -> {
                    app.hold();
                    if (torFailure) {
                        delivery.set(TorFailureNotification.send(app, message));
                    } else {
                        var action = CompletionActionPolicy.forChoice("desktop-notify", new GlobalSettings());
                        delivery.set(CompletableFuture.supplyAsync(() -> action.execute(download)));
                    }
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    GLib.timeoutAdd(0, 10, () -> {
                        if ((delivery.get().isDone() && received.isDone()) || System.nanoTime() > deadline) {
                            app.release();
                            app.quit();
                            return false;
                        }
                        return true;
                    });
                });
                assertEquals(0, app.run(new String[0]));
                assertTrue(received.isDone(), "The isolated desktop did not receive the notification");
                received.join();
                assertTrue(delivery.get().isDone());
                Object result = delivery.get().join();
                if (!torFailure) {
                    assertEquals(true, result);
                }
            } finally {
                connection.unregisterObject(registration);
            }
        } finally {
            if (connection != null) {
                connection.closeSync(null);
            }
        }
    }
}
