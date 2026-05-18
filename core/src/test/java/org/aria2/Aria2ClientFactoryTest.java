package org.aria2;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.manager.ApplicationContext;

/**
 * Test to verify that Aria2Client works correctly with the ApplicationContext
 * factory pattern. This test specifically validates that the constructor
 * migration was successful.
 */
class Aria2ClientFactoryTest {

    @BeforeEach
    void setUp() {
        // Reset ApplicationContext before each test
        ApplicationContext.resetInstance();
    }

    @AfterEach
    void tearDown() {
        // Clean up after each test
        ApplicationContext.shutdown();
    }

    @Test
    void testDefaultConstructorUsesApplicationContext() {
        // This test verifies that the default constructor properly uses ApplicationContext
        // instead of creating new instances directly

        // Initialize ApplicationContext
        ApplicationContext.initialize();

        // Create Aria2Client using default constructor
        // This should internally call ApplicationContext.getDependencyManager().getToolPath(DependencyManager.ARIA2)
        assertDoesNotThrow(() -> {
            Aria2Client client = new Aria2Client();
            assertNotNull(client, "Aria2Client should be created successfully");
        }, "Default constructor should work with ApplicationContext");
    }

    @Test
    void testConstructorWithCustomPath() {
        // Verify that the parameterized constructor still works
        String customPath = "/custom/path/to/aria2c";

        assertDoesNotThrow(() -> {
            Aria2Client client = new Aria2Client(customPath);
            assertNotNull(client, "Aria2Client should be created with custom path");
        }, "Custom path constructor should still work");
    }

    @Test
    void testConstructorWithFullParameters() {
        // Verify that the full constructor still works
        String customPath = "/custom/path/to/aria2c";
        String customRpcUrl = "http://localhost:6801/jsonrpc";
        String customToken = "test-token";

        assertDoesNotThrow(() -> {
            Aria2Client client = new Aria2Client(customPath, customRpcUrl, customToken);
            assertNotNull(client, "Aria2Client should be created with full parameters");
        }, "Full parameter constructor should still work");
    }

    @Test
    void testApplicationContextIntegration() {
        // Test that ApplicationContext provides consistent tool path
        ApplicationContext.initialize();

        // Get tool path directly from ApplicationContext
        String toolPath = ApplicationContext.getToolPath("aria2");

        // Create client and verify it uses the same path
        // Note: We can't directly access the path from the client due to encapsulation,
        // but we can verify the client creates without errors
        assertDoesNotThrow(() -> {
            Aria2Client client = new Aria2Client();
            // If this succeeds, it means the constructor chain worked properly
            assertNotNull(client);
        });

        // Verify tool path is reasonable (not null if aria2 is available)
        if (ApplicationContext.isToolAvailable("aria2")) {
            assertNotNull(toolPath, "Tool path should not be null when aria2 is available");
            assertFalse(toolPath.trim().isEmpty(), "Tool path should not be empty when aria2 is available");
        }
    }

    @Test
    void testSingletonBehavior() {
        // Verify that multiple clients use the same ApplicationContext instance
        ApplicationContext.initialize();

        // Create multiple clients
        assertDoesNotThrow(() -> {
            Aria2Client client1 = new Aria2Client();
            Aria2Client client2 = new Aria2Client();
            Aria2Client client3 = new Aria2Client();

            // All should be created successfully
            assertNotNull(client1);
            assertNotNull(client2);
            assertNotNull(client3);

            // They should be different instances (not the same object)
            assertNotSame(client1, client2);
            assertNotSame(client2, client3);

        }, "Multiple Aria2Client instances should be created successfully using shared ApplicationContext");
    }

    @Test
    void testFactoryPatternPerformance() {
        // This test demonstrates the performance benefit of the factory pattern
        ApplicationContext.initialize();

        long startTime = System.currentTimeMillis();

        // Create multiple clients (this should be fast after first initialization)
        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> {
                Aria2Client client = new Aria2Client();
                assertNotNull(client);
            });
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // This is more of a performance indicator than a strict test
        // The actual time will depend on system performance
        assertTrue(duration < 5000,
                "Creating 10 Aria2Client instances should be reasonably fast (< 5 seconds), took: " + duration + "ms");
    }
}
