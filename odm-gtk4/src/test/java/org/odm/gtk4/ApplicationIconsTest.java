package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.gnome.glib.GLib;
import org.gnome.glib.MainContext;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ApplicationIconsTest {
    @BeforeAll static void initializeGtk() throws Exception {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        GLib.setPrgname(ApplicationIcons.APPLICATION_ID);
        Gtk.init();
    }

    @Test void iconsAreSquareTransparentOutsideAndKeepTheMarkOnAnOpaqueLightSurface() throws Exception {
        for (int size : ApplicationIcons.SIZES) {
            var image = ImageIO.read(new ByteArrayInputStream(ApplicationIcons.pngBytes(size)));
            assertNotNull(image);
            assertEquals(size, image.getWidth());
            assertEquals(size, image.getHeight());
            assertTrue(image.getColorModel().hasAlpha());
            assertEquals(0, image.getRGB(0, 0) >>> 24, "the outside corners must be transparent");
            int surface = image.getRGB(size / 2, size * 10 / 128);
            assertEquals(255, surface >>> 24, "the backing must not inherit the desktop color");
            assertTrue((surface & 255) > 220, "dark lettering needs a light backing");
            int bar = image.getRGB(size / 2, size / 2);
            assertTrue(((bar >> 16) & 255) > ((bar >> 8) & 255) * 2,
                    "the red bar must remain visible at every exported size");
        }
    }

    @Test void mainWindowExportsOdmIdentityAndActualIconPixelsToX11WithoutAnInstallation() throws Exception {
        Window window = Widgets.require(UiLoader.load("/ui/main-window.ui"), "main_window", Window.class);
        String title = "ODM icon test " + ProcessHandle.current().pid();
        window.setTitle(title);
        try {
            assertEquals(ApplicationIcons.ICON_NAME, window.getIconName());
            window.present();
            while (MainContext.default_().iteration(false)) { }
            Process probe = new ProcessBuilder("xprop", "-name", title, "-notype",
                    "-len", "4000000", // xprop's default truncates the larger icon variants
                    "-f", "_NET_WM_ICON", "32c", "_NET_WM_ICON", "WM_CLASS").start();
            String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(probe.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, probe.exitValue());
            assertTrue(output.contains("\"org.odm\""), "the window must identify as ODM, not java");
            String iconLine = output.lines().filter(line -> line.startsWith("_NET_WM_ICON = "))
                    .findFirst().orElseThrow(() -> new AssertionError("X11 icon response: "
                            + output.substring(0, Math.min(output.length(), 240))
                            + "; surface=" + window.getSurface().getClass().getName()));
            String[] cardinals = iconLine.substring(iconLine.indexOf('=') + 1).trim().split(",\\s*");
            int offset = 0;
            int exported = 0;
            for (int size : ApplicationIcons.SIZES) {
                // GDK can omit the largest variants to fit the X server's
                // property-size limit. Every exported image must be complete.
                if (offset == cardinals.length) { break; }
                assertEquals(size, Integer.parseInt(cardinals[offset++]));
                assertEquals(size, Integer.parseInt(cardinals[offset++]));
                var expected = ImageIO.read(new ByteArrayInputStream(ApplicationIcons.pngBytes(size)));
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int expectedPixel = expected.getRGB(x, y);
                        int actualPixel = (int) Long.parseLong(cardinals[offset++]);
                        int alpha = expectedPixel >>> 24;
                        assertEquals(alpha, actualPixel >>> 24, "window icon opacity differs");
                        // GDK exports Cairo-format, premultiplied ARGB to X11.
                        // PNG stores straight RGB; allow conversion rounding.
                        for (int shift : new int[]{16, 8, 0}) {
                            assertEquals(((expectedPixel >>> shift) & 255) * alpha / 255.0,
                                    (actualPixel >>> shift) & 255, 1.0,
                                    "window icon color differs from packaged icon");
                        }
                    }
                }
                exported++;
            }
            assertTrue(exported >= 6, "window icons from 16 through 128 pixels must be exported");
            assertEquals(cardinals.length, offset);
        } finally {
            window.destroy();
        }
    }
}
