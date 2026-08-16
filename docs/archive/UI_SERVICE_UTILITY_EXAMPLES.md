# UI Service Utility Classes - Usage Examples

## Overview

This document provides practical examples of how to use the new utility classes created during the UI service package refactoring.

## 1. BaseDialogService Usage

### Before (Old Pattern)
```java
public class MyDialogService {
    private static final Logger LOGGER = Logger.getLogger(MyDialogService.class.getName());
    private final GladeUI ui;
    private Consumer<Boolean> closeDialogCallback;
    
    public MyDialogService(GladeUI ui) {
        this.ui = ui;
    }
    
    public void setCloseDialogCallback(Consumer<Boolean> callback) {
        this.closeDialogCallback = callback;
    }
    
    public void initializeService() {
        try {
            LOGGER.info("Initializing MyDialog service...");
            // initialization code
            LOGGER.info("MyDialog service initialized successfully");
        } catch (Exception e) {
            LOGGER.severe("Failed to initialize MyDialog service: " + e.getMessage());
            throw new RuntimeException("MyDialog service initialization failed", e);
        }
    }
    
    public void handleCancel() {
        if (closeDialogCallback != null) {
            closeDialogCallback.accept(false);
        }
    }
    
    public void cleanup() {
        try {
            // cleanup code
            LOGGER.info("MyDialog service cleanup completed");
        } catch (Exception e) {
            LOGGER.warning("Error during cleanup: " + e.getMessage());
        }
    }
}
```

### After (Using BaseDialogService)
```java
public class MyDialogService extends BaseDialogService {
    
    public MyDialogService(GladeUI ui) {
        super(ui, MyDialogService.class);
    }
    
    @Override
    protected void doInitialize() {
        // Only the actual initialization logic here
        // Error handling, logging, etc. is handled by base class
    }
    
    @Override
    protected String getServiceName() {
        return "MyDialog";
    }
    
    @Override
    protected void doCleanup() {
        // Only the actual cleanup logic here
        // Error handling and logging is handled by base class
    }
    
    // handleCancel() and handleDialogDestroy() are inherited
    // setCloseDialogCallback() is inherited
}
```

## 2. ResourceLoaderUtils Usage

### Before (Duplicated Logo Loading)
```java
private void loadLogo() {
    try {
        String logoPath = "/images/logo-128.svg";
        try (var logoStream = getClass().getResourceAsStream(logoPath)) {
            if (logoStream != null) {
                var tempFile = java.nio.file.Files.createTempFile("odm-logo", ".svg");
                java.nio.file.Files.copy(logoStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

                PointerByReference error = new PointerByReference();
                Pointer pixbuf = GtkNativeLibraries.Gtk.INSTANCE.gdk_pixbuf_new_from_file(
                        tempFile.toString(), error);

                if (pixbuf != null) {
                    GtkNativeLibraries.Gtk.INSTANCE.gtk_about_dialog_set_logo(aboutDialog, pixbuf);
                    LOGGER.info("Logo loaded successfully");
                } else {
                    LOGGER.warning("Failed to create pixbuf from logo file");
                }
                java.nio.file.Files.deleteIfExists(tempFile);
            } else {
                LOGGER.warning("Logo resource not found");
            }
        }
    } catch (Exception e) {
        LOGGER.warning("Failed to load logo: " + e.getMessage());
    }
}
```

### After (Using ResourceLoaderUtils)
```java
private void loadLogo() {
    // For About Dialog
    if (!ResourceLoaderUtils.loadAndSetAboutDialogLogo(aboutDialog)) {
        logger.warning("Could not load logo for about dialog");
    }
    
    // For Image Widget
    if (!ResourceLoaderUtils.loadAndSetImageWidgetLogo(imageWidget)) {
        logger.warning("Could not load logo for image widget");
    }
    
    // Advanced usage with custom handling
    ResourceLoaderUtils.LogoLoadResult result = ResourceLoaderUtils.loadODMLogo();
    if (result.isSuccess()) {
        // Custom handling of the pixbuf
        customizePixbuf(result.getPixbuf());
    } else {
        logger.warning("Logo loading failed: " + result.getErrorMessage());
    }
}
```

## 3. FormatUtils Usage

### Before (Multiple Implementations)
```java
// In DownloadPropertyService
private String formatFileSize(long bytes) {
    if (bytes < 0) return "Unknown";
    if (bytes == 0) return "0 B";
    String[] units = { "B", "KB", "MB", "GB", "TB" };
    int unitIndex = 0;
    double size = bytes;
    while (size >= 1024 && unitIndex < units.length - 1) {
        size /= 1024;
        unitIndex++;
    }
    return String.format("%.1f %s", size, units[unitIndex]);
}

// In NewDownloadService  
private String formatFileSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
}

// In DownloadCoordinatorService
private static String formatFileSize(long bytes) {
    if (bytes <= 0) return "0 B";
    String[] units = {"B", "KB", "MB", "GB", "TB"};
    int unitIndex = 0;
    double size = bytes;
    while (size >= 1024 && unitIndex < units.length - 1) {
        size /= 1024;
        unitIndex++;
    }
    return String.format("%.2f %s", size, units[unitIndex]);
}
```

### After (Standardized Usage)
```java
import org.odm.ui.utils.FormatUtils;

// Consistent usage across all services
String sizeDisplay = FormatUtils.formatFileSize(download.getSize());
String speedDisplay = FormatUtils.formatSpeed(download.getSpeed());
String progressDisplay = FormatUtils.formatPercentage(download.getProgress());

LOGGER.info("Download: " + download.getName() + 
           " Size: " + FormatUtils.formatFileSize(download.getSize()) +
           " Speed: " + FormatUtils.formatSpeed(download.getSpeed()));
```

## 4. ServiceUtils Usage

### Button State Management
```java
// Before
try {
    Pointer button = ui.getWidget("ok_button");
    if (button != null) {
        GtkNativeLibraries.Gtk.INSTANCE.gtk_widget_set_sensitive(button, enabled);
    } else {
        LOGGER.warning("Button 'ok_button' not found");
    }
} catch (Exception e) {
    LOGGER.warning("Error updating button state: " + e.getMessage());
}

// After
ServiceUtils.updateButtonState(ui, "ok_button", enabled);
```

### Widget Value Management
```java
// Before - Unsafe
String text = ui.getEntryText("url_entry"); // May throw exception

// After - Safe with fallback
String text = ServiceUtils.getWidgetTextSafely(ui, "url_entry", "");
boolean checked = ServiceUtils.getWidgetBooleanSafely(ui, "auto_start_check", true);
int value = ServiceUtils.getWidgetIntSafely(ui, "connections_spin", 8);

ServiceUtils.setWidgetTextSafely(ui, "filename_entry", filename);
ServiceUtils.setWidgetBooleanSafely(ui, "start_check", true);
```

### Disk Space Display
```java
// Before
try {
    FileStore store = Files.getFileStore(destinationPath);
    long usableSpace = store.getUsableSpace();
    String formattedSpace = formatFileSize(usableSpace);
    ui.setLabelText("disk_space_label", "Available: " + formattedSpace);
} catch (Exception e) {
    LOGGER.warning("Error updating disk space display");
    ui.setLabelText("disk_space_label", "Available: Unknown");
}

// After
ServiceUtils.updateDiskSpaceDisplay(ui, "disk_space_label", destinationPath);
```

### Async Operations
```java
// Simple async execution
ServiceUtils.executeAsync(() -> {
    performLongRunningOperation();
}, "long operation");

// With timeout
ServiceUtils.executeAsyncWithTimeout(() -> {
    downloadFileList();
}, "download file list", 30); // 30 seconds timeout
```

## 5. DialogServiceFactory Usage

### Basic Service Creation
```java
// Before
AboutService aboutService = new AboutService(ui);
aboutService.initializeService();
aboutService.setCloseDialogCallback(callback);

// After
AboutService aboutService = DialogServiceFactory.createAboutService(ui);
aboutService.setCloseDialogCallback(callback);
```

### Builder Pattern for Complex Services
```java
NewDownloadService service = DialogServiceFactory.configurationBuilder()
    .withDownloadManager(downloadManager)
    .withSettings(globalSettings)
    .withUI(ui)
    .buildNewDownloadService();
    
service.setCloseDialogCallback(callback);
```

### Centralized Cleanup
```java
// Before - Manual cleanup for each service
if (aboutService != null) {
    try {
        aboutService.cleanup();
    } catch (Exception e) {
        LOGGER.warning("Error cleaning up about service");
    }
}

// After - Centralized cleanup
DialogServiceFactory.cleanupService(aboutService, "About");
```

## 6. Complete Service Implementation Example

```java
public class ExampleDialogService extends BaseDialogService {
    
    private Pointer dialog;
    private final GlobalSettings settings;
    
    public ExampleDialogService(GladeUI ui, GlobalSettings settings) {
        super(ui, ExampleDialogService.class);
        this.settings = settings;
    }
    
    @Override
    protected void doInitialize() {
        // Get widgets safely
        dialog = getWidgetSafely("example_dialog");
        if (dialog == null) {
            throw new RuntimeException("Example dialog not found");
        }
        
        // Load settings to UI
        ServiceUtils.loadDefaultSettingsToUI(ui, settings);
        
        // Set up disk space display
        ServiceUtils.updateDiskSpaceDisplay(ui, "space_label", settings.getDefaultDownloadDirectory());
        
        // Load logo if needed
        Pointer logoWidget = getWidgetSafely("logo_image");
        if (logoWidget != null) {
            executeUISafely(() -> {
                ResourceLoaderUtils.loadAndSetImageWidgetLogo(logoWidget);
            }, "loading dialog logo");
        }
    }
    
    @Override
    protected String getServiceName() {
        return "Example dialog";
    }
    
    public void handleOk() {
        // Get values safely
        String text = ServiceUtils.getWidgetTextSafely(ui, "text_entry", "");
        boolean option = ServiceUtils.getWidgetBooleanSafely(ui, "option_check", false);
        
        // Validate
        if (!ServiceUtils.isValidUrl(text)) {
            showError("Please enter a valid URL");
            return;
        }
        
        // Process asynchronously
        ServiceUtils.executeAsync(() -> {
            processData(text, option);
        }, "processing dialog data");
        
        // Close dialog
        if (closeDialogCallback != null) {
            closeDialogCallback.accept(true);
        }
    }
    
    private void processData(String text, boolean option) {
        // Long-running operation
        logger.info("Processing: " + text + " with option: " + option);
    }
    
    @Override
    protected void doCleanup() {
        dialog = null;
    }
}
```

## Migration Guidelines

### For New Services
1. Extend `BaseDialogService` instead of creating from scratch
2. Use `ResourceLoaderUtils` for any resource loading
3. Use `FormatUtils` for all size/speed formatting
4. Use `ServiceUtils` for common UI operations
5. Create services through `DialogServiceFactory`

### For Existing Services
1. Identify duplicated patterns that match the utilities
2. Replace custom implementations with utility calls
3. Consider extending `BaseDialogService` for consistency
4. Gradually migrate to factory pattern for service creation

### Best Practices
1. Always use safe widget access methods from `ServiceUtils`
2. Leverage `executeUISafely()` for UI operations that might fail
3. Use async utilities for long-running operations
4. Centralize service lifecycle management through factory methods
5. Let base classes handle logging and error patterns