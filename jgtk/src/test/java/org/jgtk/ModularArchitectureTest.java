package org.jgtk;

import org.jgtk.GladeUI;
import org.jgtk.core.GtkInitializationService;
import org.jgtk.service.ResourceLoadingService;
import org.jgtk.service.SignalManagementService;
import org.jgtk.service.WidgetManagementService;

/**
 * Basic smoke test for the modular GladeUI architecture.
 * This test verifies that the services can be instantiated and basic operations
 * work.
 */
public class ModularArchitectureTest {

    public static void main(String[] args) {
        System.out.println("Testing Modular GladeUI Architecture...");

        try {
            testGtkInitialization();
            testServiceCreation();
            testNewGladeUICreation();
            System.out.println("\n✅ All tests passed! Modular architecture is working.");
        } catch (Exception e) {
            System.err.println("\n❌ Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testGtkInitialization() {
        System.out.println("\n🧪 Testing GTK Initialization...");

        // Test initialization
        GtkInitializationService.initializeGtk();

        if (!GtkInitializationService.isInitialized()) {
            throw new RuntimeException("GTK initialization failed");
        }

        // Test event processing (should not block or crash)
        GtkInitializationService.processEvents();

        System.out.println("   ✓ GTK initialization successful");
    }

    private static void testServiceCreation() {
        System.out.println("\n🧪 Testing Service Creation...");

        // Test ResourceLoadingService
        ResourceLoadingService resourceService = new ResourceLoadingService();
        if (resourceService.getBuilder() == null) {
            throw new RuntimeException("ResourceLoadingService builder is null");
        }
        System.out.println("   ✓ ResourceLoadingService created successfully");

        // Test SignalManagementService
        SignalManagementService signalService = new SignalManagementService(resourceService.getBuilder());
        if (signalService.getHandlerCount() != 0) {
            throw new RuntimeException("SignalManagementService should start with 0 handlers");
        }

        // Test handler registration
        signalService.registerHandler("test_handler", () -> {
        });
        if (signalService.getHandlerCount() != 1) {
            throw new RuntimeException("Handler registration failed");
        }

        if (!signalService.hasHandler("test_handler")) {
            throw new RuntimeException("Handler lookup failed");
        }

        System.out.println("   ✓ SignalManagementService created and tested");

        // Test WidgetManagementService
        WidgetManagementService widgetService = new WidgetManagementService(resourceService);
        // We can't test much without loading a Glade file, but we can test creation
        System.out.println("   ✓ WidgetManagementService created successfully");

        // Clean up
        resourceService.destroy();
        System.out.println("   ✓ Services cleaned up successfully");
    }

    private static void testNewGladeUICreation() {
        System.out.println("\n🧪 Testing NewGladeUI Creation...");

        // Test basic creation
        GladeUI ui = new GladeUI();

        // Test service access
        if (ui.getResourceService() == null) {
            throw new RuntimeException("ResourceService is null");
        }

        if (ui.getSignalService() == null) {
            throw new RuntimeException("SignalService is null");
        }

        if (ui.getWidgetService() == null) {
            throw new RuntimeException("WidgetService is null");
        }

        System.out.println("   ✓ NewGladeUI created with all services");

        // Test signal registration
        ui.on("test_signal", () -> {
        });
        if (ui.getSignalService().getHandlerCount() != 1) {
            throw new RuntimeException("Signal registration through facade failed");
        }

        System.out.println("   ✓ Signal registration through facade works");

        // Test string loading (will fail but shouldn't crash)
        boolean result = ui.loadFromString("<interface></interface>");
        System.out.println("   ✓ String loading tested (result: " + result + ")");

        // Clean up
        ui.destroy();
        System.out.println("   ✓ NewGladeUI destroyed successfully");
    }

    /**
     * Simple performance test to ensure the modular architecture doesn't add
     * significant overhead.
     */
    private static void performanceTest() {
        System.out.println("\n🧪 Performance Test...");

        int iterations = 1000;
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            GladeUI ui = new GladeUI();
            ui.on("test_handler", () -> {
            });
            ui.destroy();
        }

        long endTime = System.nanoTime();
        double avgTimeMs = (endTime - startTime) / 1_000_000.0 / iterations;

        System.out.printf("   ✓ Average creation time: %.3f ms per instance\n", avgTimeMs);

        if (avgTimeMs > 10.0) {
            throw new RuntimeException("Performance regression: creation took " + avgTimeMs + "ms");
        }
    }
}
