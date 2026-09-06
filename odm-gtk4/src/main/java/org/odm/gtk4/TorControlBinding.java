package org.odm.gtk4;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.gnome.gtk.Widget;
import org.tor.TorService;

/** Keeps visible Tor controls in sync with the service without retaining closed dialogs. */
final class TorControlBinding {
    private TorControlBinding() { }

    static void bind(Widget owner, TorService service, Consumer<Boolean> update) {
        if (service == null) {
            update.accept(false);
            return;
        }
        bindEvents(owner, service,
                () -> update.accept(service.isRunning()),
                event -> update.accept(service.isRunning()));
    }

    /**
     * Relays lifecycle events while {@code owner} is mapped and performs a
     * state refresh both immediately and whenever the owner is mapped again.
     */
    static void bindEvents(Widget owner, TorService service, Runnable refresh,
            Consumer<TorService.TorServiceEvent> update) {
        refresh.run();
        if (service == null) {
            return;
        }
        AtomicBoolean listening = new AtomicBoolean();
        TorService.TorServiceListener listener = event -> UiThread.marshal(() -> {
            if (listening.get()) {
                update.accept(event);
            }
        });
        owner.onMap(() -> {
            if (listening.compareAndSet(false, true)) {
                service.addListener(listener);
            }
            refresh.run();
        });
        owner.onUnmap(() -> {
            listening.set(false);
            service.removeListener(listener);
        });
    }
}
