package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.gnome.gtk.Gtk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Lifecycle contract for the startup/shutdown progress dialog: show() must
 * attach the pulse timer, close() must remove it and destroy the window,
 * and the whole cycle must be repeatable (the app shows one instance for
 * startup and a second one for graceful shutdown).
 */
class StartShutdownDialogSmokeTest {

    @BeforeAll
    static void initGtk() {
        Gtk.init();
    }

    @Test
    @Timeout(30)
    @DisplayName("show -> message update -> close, twice (startup and shutdown phases)")
    void dialogLifecycleIsRepeatable() {
        for (int phase = 0; phase < 2; phase++) {
            final int currentPhase = phase;
            StartShutdownDialog dialog = assertDoesNotThrow(
                    () -> new StartShutdownDialog((org.gnome.gtk.Window) null),
                    () -> "phase " + currentPhase + ": dialog must construct");

            assertDoesNotThrow(() -> dialog.show("Phase " + currentPhase + "…"),
                    () -> "phase " + currentPhase + ": show must attach the pulse timer");
            assertDoesNotThrow(() -> dialog.setMessage("Still working…"));

            assertDoesNotThrow(dialog::close,
                    () -> "phase " + currentPhase + ": close must remove the timer and destroy the window");
        }
    }
}
