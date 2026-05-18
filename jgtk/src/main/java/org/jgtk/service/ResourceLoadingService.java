package org.jgtk.service;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.jgtk.core.GtkNativeLibraries;
import org.jgtk.core.GtkInitializationService;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Service for loading Glade UI resources from various sources.
 * Handles loading from files, strings, and classpath resources.
 */
public class ResourceLoadingService {

    private static final Logger LOGGER = Logger.getLogger(ResourceLoadingService.class.getName());

    private final Pointer builder;

    /**
     * Creates a new resource loading service with a new GTK builder.
     */
    public ResourceLoadingService() {
        // Ensure GTK is initialized
        GtkInitializationService.initializeGtk();
        this.builder = GtkNativeLibraries.Gtk.INSTANCE.gtk_builder_new();

        if (builder == null) {
            throw new RuntimeException("Failed to create GTK builder");
        }
    }

    /**
     * Gets the GTK builder pointer.
     *
     * @return the builder pointer
     */
    public Pointer getBuilder() {
        return builder;
    }

    /**
     * Loads a Glade file from the filesystem.
     *
     * @param filename the path to the Glade file
     * @return true if the file was loaded successfully, false otherwise
     */
    public boolean loadFromFile(String filename) {
        if (filename == null || filename.isBlank()) {
            LOGGER.warning("Filename is null or empty");
            return false;
        }

        var error = new PointerByReference();
        var success = GtkNativeLibraries.Gtk.INSTANCE.gtk_builder_add_from_file(builder, filename, error);

        if (!success) {
            GtkInitializationService.handleError(error, "loading file: " + filename);
            return false;
        }

        LOGGER.fine("Successfully loaded Glade file: " + filename);
        return true;
    }

    /**
     * Loads a Glade UI from a string containing the Glade XML content.
     *
     * @param gladeContent the Glade XML content as a string
     * @return true if the content was loaded successfully, false otherwise
     */
    public boolean loadFromString(String gladeContent) {
        if (gladeContent == null || gladeContent.isBlank()) {
            LOGGER.warning("Glade content is null or empty");
            return false;
        }

        var error = new PointerByReference();
        var success = GtkNativeLibraries.Gtk.INSTANCE.gtk_builder_add_from_string(
                builder, gladeContent, gladeContent.length(), error);

        if (!success) {
            GtkInitializationService.handleError(error, "loading from string");
            return false;
        }

        LOGGER.fine("Successfully loaded Glade content from string");
        return true;
    }

    /**
     * Loads a Glade file from the classpath resources using multiple classloaders
     * to find the resource (like GladeUI does).
     *
     * @param resourcePath the path to the resource (e.g.,
     *                     "/glade/main-window/main-window.glade")
     * @return true if the resource was loaded successfully, false otherwise
     */
    public boolean loadFromResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            LOGGER.warning("Resource path is null or empty");
            return false;
        }

        try {
            // Try multiple classloaders to find the resource
            var contextClassLoader = Thread.currentThread().getContextClassLoader();
            var inputStream = contextClassLoader != null ? contextClassLoader.getResourceAsStream(resourcePath) : null;

            // Try fallback classloaders if not found
            if (inputStream == null) {
                inputStream = getClass().getResourceAsStream(resourcePath);
            }

            if (inputStream == null) {
                inputStream = ClassLoader.getSystemResourceAsStream(resourcePath);
            }

            if (inputStream == null) {
                LOGGER.warning("Resource not found: " + resourcePath);
                return false;
            }

            // Create a temporary file to work with GTK's file-based loader
            var tempFile = Files.createTempFile("glade-ui", ".glade");
            try {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                var success = loadFromFile(tempFile.toString());

                if (success) {
                    LOGGER.fine("Successfully loaded Glade resource: " + resourcePath);
                }

                return success;
            } finally {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    LOGGER.warning("Failed to delete temporary file: " + e.getMessage());
                }
                inputStream.close();
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to load resource " + resourcePath + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Loads a Glade file from the classpath resources using a specific classloader.
     *
     * @param resourcePath the path to the resource (e.g.,
     *                     "/glade/main-window/main-window.glade")
     * @param classLoader  the classloader to use for loading the resource
     * @return true if the resource was loaded successfully, false otherwise
     */
    public boolean loadFromResource(String resourcePath, ClassLoader classLoader) {
        if (resourcePath == null || resourcePath.isBlank()) {
            LOGGER.warning("Resource path is null or empty");
            return false;
        }

        var loaderToUse = classLoader != null ? classLoader : ResourceLoadingService.class.getClassLoader();

        try (var resourceStream = loaderToUse.getResourceAsStream(resourcePath)) {
            if (resourceStream == null) {
                LOGGER.warning("Resource not found: " + resourcePath);
                return false;
            }

            // Create a temporary file to work with GTK's file-based loader
            var tempFile = Files.createTempFile("glade-ui", ".glade");
            try {
                Files.copy(resourceStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                var success = loadFromFile(tempFile.toString());

                if (success) {
                    LOGGER.fine("Successfully loaded Glade resource: " + resourcePath);
                }

                return success;
            } finally {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    LOGGER.warning("Failed to delete temporary file: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to load resource " + resourcePath + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets a widget by its ID from the loaded Glade file.
     *
     * @param widgetId the ID of the widget as specified in the Glade file
     * @return the widget pointer, or null if not found
     */
    public Pointer getWidget(String widgetId) {
        if (widgetId == null || widgetId.trim().isEmpty()) {
            return null;
        }

        try {
            Pointer widget = GtkNativeLibraries.Gtk.INSTANCE.gtk_builder_get_object(builder, widgetId);
            if (widget == null) {
                LOGGER.warning("GTK widget retrieval failed: widget not found - ID '" + widgetId + "'");
            } else {
                LOGGER.fine(() -> "Successfully retrieved GTK widget: ID '" + widgetId + "'");
            }
            return widget;
        } catch (Exception e) {
            LOGGER.severe("GTK widget retrieval failed with exception for ID '" + widgetId + "': " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Destroys the builder and releases all associated resources.
     */
    public void destroy() {
        if (builder != null) {
            try {
                GtkNativeLibraries.Gtk.INSTANCE.g_object_unref(builder);
                LOGGER.fine("Builder destroyed successfully");
            } catch (Exception e) {
                LOGGER.warning("Error destroying builder: " + e.getMessage());
            }
        }
    }

    /**
     * Creates an isolated resource loading service for dialogs.
     * This is useful when you need to load a dialog that won't conflict with
     * existing builders.
     *
     * @param resourcePath the path to the resource
     * @return a new ResourceLoadingService with the resource loaded, or null if
     *         loading failed
     */
    public static ResourceLoadingService createForDialog(String resourcePath) {
        return createForDialog(resourcePath, null);
    }

    /**
     * Creates an isolated resource loading service for dialogs using a specific
     * classloader.
     *
     * @param resourcePath the path to the resource
     * @param classLoader  the classloader to use, or null for default
     * @return a new ResourceLoadingService with the resource loaded, or null if
     *         loading failed
     */
    public static ResourceLoadingService createForDialog(String resourcePath, ClassLoader classLoader) {
        try {
            ResourceLoadingService service = new ResourceLoadingService();
            boolean success = service.loadFromResource(resourcePath, classLoader);

            if (!success) {
                service.destroy();
                return null;
            }

            return service;
        } catch (Exception e) {
            LOGGER.severe("Failed to create dialog resource service: " + e.getMessage());
            return null;
        }
    }
}
