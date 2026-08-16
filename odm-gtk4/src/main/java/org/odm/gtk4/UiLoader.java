package org.odm.gtk4;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.gnome.gtk.GtkBuilder;

/**
 * Classpath-.ui loading helper. Every window builds through here so resource
 * handling is identical everywhere.
 */
public final class UiLoader {

    private UiLoader() {
    }

    /**
     * Loads a .ui file from the module's classpath resources.
     *
     * @param classpathResource absolute resource path, e.g. "/ui/main-window.ui"
     * @return a builder with the UI definition loaded
     * @throws IllegalStateException if the resource is missing or unparseable
     */
    public static GtkBuilder load(String classpathResource) {
        String xml;
        try (InputStream in = UiLoader.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("Missing UI resource: " + classpathResource);
            }
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read UI resource: " + classpathResource, e);
        }
        try {
            return GtkBuilder.fromString(xml, -1);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse UI resource: " + classpathResource, e);
        }
    }
}
