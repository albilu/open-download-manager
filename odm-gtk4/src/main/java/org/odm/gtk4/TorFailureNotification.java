package org.odm.gtk4;

import java.util.concurrent.CompletableFuture;
import org.gnome.gio.Application;
import org.gnome.gio.NotificationPriority;

/** Critical desktop alerts for failed automatic Tor circuit checks. */
final class TorFailureNotification {
    private TorFailureNotification() { }

    static CompletableFuture<Void> send(Application application, String message) {
        return DesktopNotifications.send(application, "tor-verification-failed",
                "Tor verification failed", message, NotificationPriority.URGENT);
    }
}
