package org.manager.clipboard;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for ClipboardSettings. Tests configuration settings, validation,
 * and behavior of clipboard monitoring settings.
 */
@DisplayName("ClipboardSettings Unit Tests")
class ClipboardSettingsTest {

    private ClipboardSettings settings;

    @BeforeEach
    void setUp() {
        settings = new ClipboardSettings();
    }

    @Nested
    @DisplayName("Default Values")
    class DefaultValuesTest {

        @Test
        @DisplayName("Should have correct default values")
        void testDefaultValues() {
            assertFalse(settings.isMonitoringEnabled(), "Monitoring should be disabled by default");
            assertFalse(settings.isSilentMode(), "Silent mode should be disabled by default");
            assertEquals(500, settings.getMonitoringIntervalMs(), "Default monitoring interval should be 500ms");
            assertFalse(settings.isAutoDownloadDetectedUrls(), "Auto download should be disabled by default");
            assertTrue(settings.isFilterVideoUrls(), "Video URL filtering should be enabled by default");
            assertTrue(settings.isFilterTorrentUrls(), "Torrent URL filtering should be enabled by default");
            assertTrue(settings.isFilterDirectDownloads(), "Direct download filtering should be enabled by default");
            assertEquals(10, settings.getMaxUrlsPerClipboard(), "Default max URLs should be 10");
            assertFalse(settings.isLogClipboardActivity(), "Clipboard activity logging should be disabled by default");
        }
    }

    @Nested
    @DisplayName("Setting Methods")
    class SettingMethodsTest {

        @Test
        @DisplayName("Should set monitoring enabled correctly")
        void testSetMonitoringEnabled() {
            ClipboardSettings result = settings.setMonitoringEnabled(true);

            assertTrue(settings.isMonitoringEnabled(), "Monitoring should be enabled");
            assertSame(settings, result, "Should return same instance for chaining");
        }

        @Test
        @DisplayName("Should set silent mode correctly")
        void testSetSilentMode() {
            ClipboardSettings result = settings.setSilentMode(true);

            assertTrue(settings.isSilentMode(), "Silent mode should be enabled");
            assertSame(settings, result, "Should return same instance for chaining");
        }

        @Test
        @DisplayName("Should set valid monitoring interval")
        void testSetValidMonitoringInterval() {
            ClipboardSettings result = settings.setMonitoringIntervalMs(1000);

            assertEquals(1000, settings.getMonitoringIntervalMs(), "Monitoring interval should be set");
            assertSame(settings, result, "Should return same instance for chaining");
        }

        @ParameterizedTest
        @ValueSource(longs = {50, 99, 0, -1, -100})
        @DisplayName("Should reject invalid monitoring intervals")
        void testInvalidMonitoringInterval(long interval) {
            assertThrows(IllegalArgumentException.class,
                    () -> settings.setMonitoringIntervalMs(interval),
                    "Should throw exception for interval: " + interval);
        }

        @Test
        @DisplayName("Should set auto download correctly")
        void testSetAutoDownloadDetectedUrls() {
            ClipboardSettings result = settings.setAutoDownloadDetectedUrls(true);

            assertTrue(settings.isAutoDownloadDetectedUrls(), "Auto download should be enabled");
            assertSame(settings, result, "Should return same instance for chaining");
        }

        @Test
        @DisplayName("Should set video URL filtering correctly")
        void testSetFilterVideoUrls() {
            ClipboardSettings result = settings.setFilterVideoUrls(false);

            assertFalse(settings.isFilterVideoUrls(), "Video URL filtering should be disabled");
            assertSame(settings, result, "Should return same instance for chaining");
        }

        @Test
        @DisplayName("Should set torrent URL filtering correctly")
        void testSetFilterTorrentUrls() {
            ClipboardSettings result = settings.setFilterTorrentUrls(false);

            assertFalse(settings.isFilterTorrentUrls(), "Torrent URL filtering should be disabled");
            assertSame(settings, result, "Should return same instance for chaining");
        }

        @Test
        @DisplayName("Should set direct download filtering correctly")
        void testSetFilterDirectDownloads() {
            ClipboardSettings result = settings.setFilterDirectDownloads(false);

            assertFalse(settings.isFilterDirectDownloads(), "Direct download filtering should be disabled");
            assertSame(settings, result, "Should return same instance for chaining");
        }

        @Test
        @DisplayName("Should set valid max URLs per clipboard")
        void testSetValidMaxUrlsPerClipboard() {
            ClipboardSettings result = settings.setMaxUrlsPerClipboard(5);

            assertEquals(5, settings.getMaxUrlsPerClipboard(), "Max URLs should be set");
            assertSame(settings, result, "Should return same instance for chaining");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -10})
        @DisplayName("Should reject invalid max URLs per clipboard")
        void testInvalidMaxUrlsPerClipboard(int maxUrls) {
            assertThrows(IllegalArgumentException.class,
                    () -> settings.setMaxUrlsPerClipboard(maxUrls),
                    "Should throw exception for max URLs: " + maxUrls);
        }

        @Test
        @DisplayName("Should set clipboard activity logging correctly")
        void testSetLogClipboardActivity() {
            ClipboardSettings result = settings.setLogClipboardActivity(true);

            assertTrue(settings.isLogClipboardActivity(), "Clipboard activity logging should be enabled");
            assertSame(settings, result, "Should return same instance for chaining");
        }
    }

    @Nested
    @DisplayName("Method Chaining")
    class MethodChainingTest {

        @Test
        @DisplayName("Should support method chaining for all setters")
        void testMethodChaining() {
            ClipboardSettings result = settings
                    .setMonitoringEnabled(true)
                    .setSilentMode(true)
                    .setMonitoringIntervalMs(1000)
                    .setAutoDownloadDetectedUrls(true)
                    .setFilterVideoUrls(false)
                    .setFilterTorrentUrls(false)
                    .setFilterDirectDownloads(false)
                    .setMaxUrlsPerClipboard(5)
                    .setLogClipboardActivity(true);

            assertSame(settings, result, "Should return same instance");
            assertTrue(settings.isMonitoringEnabled());
            assertTrue(settings.isSilentMode());
            assertEquals(1000, settings.getMonitoringIntervalMs());
            assertTrue(settings.isAutoDownloadDetectedUrls());
            assertFalse(settings.isFilterVideoUrls());
            assertFalse(settings.isFilterTorrentUrls());
            assertFalse(settings.isFilterDirectDownloads());
            assertEquals(5, settings.getMaxUrlsPerClipboard());
            assertTrue(settings.isLogClipboardActivity());
        }
    }

    @Nested
    @DisplayName("Copy Constructor")
    class CopyConstructorTest {

        @Test
        @DisplayName("Should create copy with same values")
        void testCopyConstructor() {
            // Configure original settings
            settings.setMonitoringEnabled(true)
                    .setSilentMode(true)
                    .setMonitoringIntervalMs(1000)
                    .setAutoDownloadDetectedUrls(true)
                    .setMaxUrlsPerClipboard(5);

            ClipboardSettings copy = new ClipboardSettings(settings);

            assertEquals(settings.isMonitoringEnabled(), copy.isMonitoringEnabled());
            assertEquals(settings.isSilentMode(), copy.isSilentMode());
            assertEquals(settings.getMonitoringIntervalMs(), copy.getMonitoringIntervalMs());
            assertEquals(settings.isAutoDownloadDetectedUrls(), copy.isAutoDownloadDetectedUrls());
            assertEquals(settings.getMaxUrlsPerClipboard(), copy.getMaxUrlsPerClipboard());

            // Should be different instances
            assertNotSame(settings, copy);
        }

        @Test
        @DisplayName("Should handle null source in copy constructor")
        void testCopyConstructorWithNull() {
            ClipboardSettings copy = new ClipboardSettings(null);

            // Should have default values
            assertFalse(copy.isMonitoringEnabled());
            assertFalse(copy.isSilentMode());
            assertEquals(500, copy.getMonitoringIntervalMs());
        }
    }

    @Nested
    @DisplayName("Copy Method")
    class CopyMethodTest {

        @Test
        @DisplayName("Should create independent copy")
        void testCopyMethod() {
            settings.setMonitoringEnabled(true)
                    .setMaxUrlsPerClipboard(15);

            ClipboardSettings copy = settings.copy();

            assertEquals(settings.isMonitoringEnabled(), copy.isMonitoringEnabled());
            assertEquals(settings.getMaxUrlsPerClipboard(), copy.getMaxUrlsPerClipboard());

            // Modify original - copy should not be affected
            settings.setMonitoringEnabled(false);
            assertTrue(copy.isMonitoringEnabled(), "Copy should maintain original values");
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTest {

        @Test
        @DisplayName("Should validate correctly with valid settings")
        void testValidationWithValidSettings() {
            settings.setMonitoringIntervalMs(100)
                    .setMaxUrlsPerClipboard(1);

            assertDoesNotThrow(() -> settings.validate(), "Should not throw for valid settings");
        }

        @Test
        @DisplayName("Should throw exception for invalid monitoring interval during validation")
        void testValidationWithInvalidInterval() {
            // Set invalid interval using reflection to bypass setter validation
            try {
                java.lang.reflect.Field field = ClipboardSettings.class.getDeclaredField("monitoringIntervalMs");
                field.setAccessible(true);
                field.setLong(settings, 50);
            } catch (Exception e) {
                fail("Failed to set invalid interval for testing");
            }

            assertThrows(IllegalStateException.class, () -> settings.validate(),
                    "Should throw exception for invalid monitoring interval");
        }

        @Test
        @DisplayName("Should throw exception for invalid max URLs during validation")
        void testValidationWithInvalidMaxUrls() {
            // Set invalid max URLs using reflection to bypass setter validation
            try {
                java.lang.reflect.Field field = ClipboardSettings.class.getDeclaredField("maxUrlsPerClipboard");
                field.setAccessible(true);
                field.setInt(settings, 0);
            } catch (Exception e) {
                fail("Failed to set invalid max URLs for testing");
            }

            assertThrows(IllegalStateException.class, () -> settings.validate(),
                    "Should throw exception for invalid max URLs");
        }
    }

    @Nested
    @DisplayName("Reset to Defaults")
    class ResetToDefaultsTest {

        @Test
        @DisplayName("Should reset all settings to defaults")
        void testResetToDefaults() {
            // Configure non-default settings
            settings.setMonitoringEnabled(true)
                    .setSilentMode(true)
                    .setMonitoringIntervalMs(1000)
                    .setAutoDownloadDetectedUrls(true)
                    .setFilterVideoUrls(false)
                    .setFilterTorrentUrls(false)
                    .setFilterDirectDownloads(false)
                    .setMaxUrlsPerClipboard(5)
                    .setLogClipboardActivity(true);

            ClipboardSettings result = settings.resetToDefaults();

            assertSame(settings, result, "Should return same instance for chaining");
            assertFalse(settings.isMonitoringEnabled());
            assertFalse(settings.isSilentMode());
            assertEquals(500, settings.getMonitoringIntervalMs());
            assertFalse(settings.isAutoDownloadDetectedUrls());
            assertTrue(settings.isFilterVideoUrls());
            assertTrue(settings.isFilterTorrentUrls());
            assertTrue(settings.isFilterDirectDownloads());
            assertEquals(10, settings.getMaxUrlsPerClipboard());
            assertFalse(settings.isLogClipboardActivity());
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsAndHashCodeTest {

        @Test
        @DisplayName("Should be equal to itself")
        void testEqualsWithSelf() {
            assertEquals(settings, settings);
            assertEquals(settings.hashCode(), settings.hashCode());
        }

        @Test
        @DisplayName("Should be equal to identical settings")
        void testEqualsWithIdentical() {
            ClipboardSettings other = new ClipboardSettings();

            assertEquals(settings, other);
            assertEquals(settings.hashCode(), other.hashCode());
        }

        @Test
        @DisplayName("Should not be equal to different settings")
        void testEqualsWithDifferent() {
            ClipboardSettings other = new ClipboardSettings();
            other.setMonitoringEnabled(true);

            assertNotEquals(settings, other);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void testEqualsWithNull() {
            assertNotEquals(settings, null);
        }

        @Test
        @DisplayName("Should not be equal to different type")
        void testEqualsWithDifferentType() {
            assertNotEquals(settings, "not a ClipboardSettings");
        }

        @Test
        @DisplayName("Should have consistent hashCode for equal objects")
        void testHashCodeConsistency() {
            settings.setMonitoringEnabled(true).setMaxUrlsPerClipboard(5);
            ClipboardSettings other = new ClipboardSettings();
            other.setMonitoringEnabled(true).setMaxUrlsPerClipboard(5);

            assertEquals(settings, other);
            assertEquals(settings.hashCode(), other.hashCode());
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTest {

        @Test
        @DisplayName("Should return non-null string representation")
        void testToString() {
            String result = settings.toString();

            assertNotNull(result);
            assertTrue(result.contains("ClipboardSettings"));
            assertTrue(result.contains("monitoringEnabled"));
            assertTrue(result.contains("silentMode"));
        }

        @Test
        @DisplayName("Should include all setting values in string")
        void testToStringContainsAllValues() {
            settings.setMonitoringEnabled(true)
                    .setSilentMode(true)
                    .setMonitoringIntervalMs(1000);

            String result = settings.toString();

            assertTrue(result.contains("monitoringEnabled=true"));
            assertTrue(result.contains("silentMode=true"));
            assertTrue(result.contains("monitoringIntervalMs=1000"));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTest {

        @Test
        @DisplayName("Should handle minimum valid monitoring interval")
        void testMinimumValidMonitoringInterval() {
            assertDoesNotThrow(() -> settings.setMonitoringIntervalMs(100));
            assertEquals(100, settings.getMonitoringIntervalMs());
        }

        @Test
        @DisplayName("Should handle large monitoring interval")
        void testLargeMonitoringInterval() {
            long largeInterval = Long.MAX_VALUE;
            assertDoesNotThrow(() -> settings.setMonitoringIntervalMs(largeInterval));
            assertEquals(largeInterval, settings.getMonitoringIntervalMs());
        }

        @Test
        @DisplayName("Should handle minimum valid max URLs")
        void testMinimumValidMaxUrls() {
            assertDoesNotThrow(() -> settings.setMaxUrlsPerClipboard(1));
            assertEquals(1, settings.getMaxUrlsPerClipboard());
        }

        @Test
        @DisplayName("Should handle large max URLs")
        void testLargeMaxUrls() {
            int largeMaxUrls = Integer.MAX_VALUE;
            assertDoesNotThrow(() -> settings.setMaxUrlsPerClipboard(largeMaxUrls));
            assertEquals(largeMaxUrls, settings.getMaxUrlsPerClipboard());
        }
    }
}
