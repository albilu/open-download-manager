package org.odm.gtk4;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import org.freedesktop.cairo.Cairo;

/** Native-library setup for the Cairo binding used by GTK drawing callbacks. */
final class CairoSupport {

    static {
        // cairobindings 1.18.4.3 searches java.library.path itself. Temurin
        // (including jlink runtimes) omits Debian/Ubuntu multiarch directories,
        // although GTK's own dlopen-based loader finds the same Cairo library.
        // Add only installed system Cairo locations, without replacing custom paths.
        var directories = new LinkedHashSet<String>();
        String configured = System.getProperty("java.library.path", "");
        if (!configured.isBlank()) {
            directories.add(configured);
        }
        for (String root : new String[]{"/usr/lib", "/lib"}) {
            Path directory = Path.of(root);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var children = Files.newDirectoryStream(directory, "*-linux-gnu*")) {
                for (Path child : children) {
                    if (Files.isRegularFile(child.resolve("libcairo.so.2"))) {
                        directories.add(child.toString());
                    }
                }
            } catch (IOException ignored) {
                // Leave other platforms/locations to the binding's normal loader.
            }
        }
        System.setProperty("java.library.path", String.join(File.pathSeparator, directories));
    }

    private CairoSupport() { }

    static void ensureInitialized() {
        Cairo.ensureInitialized();
    }
}
