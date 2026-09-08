package org.odm.gtk4;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.gnome.gio.Application;
import org.gnome.gio.Cancellable;
import org.gnome.gio.DBusCallFlags;
import org.gnome.gio.DBusConnection;
import org.gnome.gio.Notification;
import org.gnome.gio.NotificationPriority;
import org.gnome.gio.ThemedIcon;
import org.gnome.glib.GLib;
import org.gnome.glib.Variant;
import org.gnome.glib.VariantDict;
import org.gnome.glib.VariantType;
import org.manager.download.Download;

/** GTK notifications, with a freedesktop fallback for uninstalled local launches. */
final class DesktopNotifications {
    static final String ICON_NAME = ApplicationIcons.ICON_NAME;

    private DesktopNotifications() { }

    static CompletableFuture<Void> downloadCompleted(Download download) {
        String name = download.getName();
        String message = name == null || name.isBlank()
                ? "Your download has completed." : name + " has completed.";
        return send(null, "download-completed-" + download.getId(),
                "Download completed", message, NotificationPriority.NORMAL);
    }

    /** Safe for completion workers as well as callers already on the GTK thread. */
    static CompletableFuture<Void> send(Application application, String id,
            String title, String message, NotificationPriority priority) {
        CompletableFuture<Void> sent = new CompletableFuture<>();
        UiThread.marshal(() -> {
            if (sent.isDone()) {
                return;
            }
            try {
                Application current = application == null ? Application.getDefault() : application;
                if (current == null || !current.getIsRegistered() || current.getIsRemote()
                        || current.getDbusConnection() == null) {
                    throw new IllegalStateException("No registered desktop application or session bus");
                }
                if (hasDesktopEntry(current.getApplicationId())) {
                    Notification notification = new Notification(title);
                    notification.setBody(message);
                    notification.setIcon(new ThemedIcon(ICON_NAME));
                    notification.setPriority(priority);
                    notification.setCategory(priority == NotificationPriority.URGENT
                            ? "network.error" : "transfer.complete");
                    current.sendNotification(id, notification);
                    sent.complete(null);
                } else {
                    sendFallback(current.getDbusConnection(), title, message, priority, sent);
                }
            } catch (Exception failure) {
                sent.completeExceptionally(failure);
            }
        });
        return sent;
    }

    private static boolean hasDesktopEntry(String applicationId) {
        if (applicationId == null) {
            return false;
        }
        return Stream.concat(Stream.of(GLib.getUserDataDir()),
                        Arrays.stream(GLib.getSystemDataDirs()))
                .anyMatch(directory -> Files.isRegularFile(
                        Path.of(directory.toString(), "applications", applicationId + ".desktop")));
    }

    private static void sendFallback(DBusConnection connection, String title, String message,
            NotificationPriority priority, CompletableFuture<Void> sent) throws IOException {
        boolean urgent = priority == NotificationPriority.URGENT;
        VariantDict hints = new VariantDict((Variant) null);
        hints.insertValue("urgency", Variant.byte_((byte) (urgent ? 2 : 1)));
        hints.insertValue("category", Variant.string(urgent ? "network.error" : "transfer.complete"));
        // The theme icon is not installed during IDE launches. Supply the bundled logo too.
        hints.insertValue("image-data", logoData());
        Variant parameters = Variant.tuple(new Variant[]{
                Variant.string("oDM"), Variant.uint32(0), Variant.string(ICON_NAME),
                Variant.string(title), Variant.string(GLib.markupEscapeText(message, -1)),
                Variant.strv(new String[0]), hints.end(), Variant.int32(urgent ? 0 : -1)
        });
        Cancellable cancellation = new Cancellable();
        sent.whenComplete((ignored, failure) -> {
            if (sent.isCancelled()) {
                cancellation.cancel();
            }
        });
        connection.call("org.freedesktop.Notifications", "/org/freedesktop/Notifications",
                "org.freedesktop.Notifications", "Notify", parameters,
                new VariantType("(u)"), DBusCallFlags.NONE, 5000, cancellation, result -> {
                    try {
                        connection.callFinish(result);
                        sent.complete(null);
                    } catch (Exception failure) {
                        sent.completeExceptionally(failure);
                    }
                });
    }

    private static Variant logoData() throws IOException {
        try (var input = DesktopNotifications.class.getResourceAsStream(ApplicationIcons.pngResource(128))) {
            if (input == null) {
                throw new IOException("ODM notification logo is missing");
            }
            var image = ImageIO.read(input);
            if (image == null) {
                throw new IOException("ODM notification logo could not be decoded");
            }
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] pixels = new byte[width * height * 4];
            for (int y = 0, offset = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    pixels[offset++] = (byte) (argb >> 16);
                    pixels[offset++] = (byte) (argb >> 8);
                    pixels[offset++] = (byte) argb;
                    pixels[offset++] = (byte) (argb >> 24);
                }
            }
            try (Arena arena = Arena.ofConfined()) {
                return Variant.tuple(new Variant[]{
                        Variant.int32(width), Variant.int32(height), Variant.int32(width * 4),
                        Variant.boolean_(true), Variant.int32(8), Variant.int32(4),
                        Variant.fixedArray(new VariantType("y"),
                                arena.allocateFrom(ValueLayout.JAVA_BYTE, pixels), pixels.length, 1)
                });
            }
        }
    }
}
