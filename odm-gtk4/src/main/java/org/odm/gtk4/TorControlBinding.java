package org.odm.gtk4;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.gnome.gtk.Widget;
import org.tor.TorService;

/** Keeps visible Tor controls in sync with the service without retaining closed dialogs. */
final class TorControlBinding {
    private TorControlBinding() { }

    static void bind(Widget owner, TorService service, Consumer<Boolean> update) {
        update.accept(service != null && service.isRunning());
        if (service == null) {
            return;
        }
        AtomicBoolean listening = new AtomicBoolean();
        TorService.TorServiceListener listener = event -> UiThread.marshal(() -> {
            if (listening.get()) {
                update.accept(service.isRunning());
            }
        });
        owner.onMap(() -> {
            if (listening.compareAndSet(false, true)) {
                service.addListener(listener);
            }
            update.accept(service.isRunning());
        });
        owner.onUnmap(() -> {
            listening.set(false);
            service.removeListener(listener);
        });
    }
}
