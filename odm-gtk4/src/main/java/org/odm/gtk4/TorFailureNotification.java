package org.odm.gtk4;

import java.util.concurrent.CompletableFuture;
import org.gnome.gio.DBusCallFlags;
import org.gnome.gio.DBusConnection;
import org.gnome.glib.Variant;
import org.gnome.glib.VariantDict;
import org.gnome.glib.VariantType;

/** Desktop alerts also work for local launches without an installed application ID. */
final class TorFailureNotification {
    private TorFailureNotification() { }

    static CompletableFuture<Void> send(DBusConnection connection, String message) {
        if (connection == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No desktop session bus"));
        }
        CompletableFuture<Void> sent = new CompletableFuture<>();
        connection.call("org.freedesktop.Notifications", "/org/freedesktop/Notifications",
                "org.freedesktop.Notifications", "Notify", parameters(message),
                new VariantType("(u)"), DBusCallFlags.NONE, 5000, null, result -> {
                    try {
                        connection.callFinish(result);
                        sent.complete(null);
                    } catch (Exception failure) {
                        sent.completeExceptionally(failure);
                    }
                });
        return sent;
    }

    private static Variant parameters(String message) {
        VariantDict hints = new VariantDict((Variant) null);
        hints.insertValue("urgency", Variant.byte_((byte) 2));
        return Variant.tuple(new Variant[]{
                Variant.string("oDM"), Variant.uint32(0), Variant.string("network-offline-symbolic"),
                Variant.string("Tor verification failed"), Variant.string(message),
                Variant.strv(new String[0]), hints.end(), Variant.int32(0)
        });
    }
}
