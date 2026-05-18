package org.manager.folder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FolderMonitorSettings class.
 * Tests configuration settings for folder monitoring functionality.
 */
@DisplayName("FolderMonitorSettings Unit Tests")
class FolderMonitorSettingsTest {

    private FolderMonitorSettings settings;

    @BeforeEach
    void setUp() {
        settings = new FolderMonitorSettings();
    }

    @Nested
    @DisplayName("Default Settings Tests")
    class DefaultSettingsTests {

        @Test
        @DisplayName("Should create settings with default values")
        void shouldCreateWithDefaultValues() {
            // Verify default file extensions
            assertTrue(settings.getFileExtensions().contains(".torrent"));
            assertEquals(1, settings.getFileExtensions().size());

            // Verify other defaults
            assertFalse(settings.isRecursive());
            assertEquals(FolderMonitorSettings.FileAction.MOVE_TO_TRASH, settings.getFileAction());
            assertNull(settings.getMoveToDirectory());
            assertTrue(settings.isProcessExistingFiles());
            assertEquals(Duration.ofSeconds(2), settings.getDebounceDelay());
            assertTrue(settings.isEnabled());
            assertEquals(10, settings.getMaxFilesPerBatch());
            assertFalse(settings.isCaseSensitive());
            assertTrue(settings.getExcludePatterns().isEmpty());
            assertEquals(Long.MAX_VALUE, settings.getMaxFileSize());
            assertEquals(0L, settings.getMinFileSize());
        }

        @Test
        @DisplayName("Should return unmodifiable file extensions set")
        void shouldReturnUnmodifiableFileExtensions() {
            Set<String> extensions = settings.getFileExtensions();

            assertThrows(UnsupportedOperationException.class, () -> {
                extensions.add(".test");
            });
        }

        @Test
        @DisplayName("Should return unmodifiable exclude patterns set")
        void shouldReturnUnmodifiableExcludePatterns() {
            Set<String> patterns = settings.getExcludePatterns();

            assertThrows(UnsupportedOperationException.class, () -> {
                patterns.add("*.tmp");
            });
        }
    }

    @Nested
    @DisplayName("File Extensions Tests")
    class FileExtensionsTests {

        @Test
        @DisplayName("Should set file extensions")
        void shouldSetFileExtensions() {
            Set<String> extensions = Set.of(".torrent", ".magnet", ".meta4");

            FolderMonitorSettings result = settings.setFileExtensions(extensions);

            assertSame(settings, result); // Test method chaining
            assertEquals(extensions, settings.getFileExtensions());
        }

        @Test
        @DisplayName("Should add file extension")
        void shouldAddFileExtension() {
            FolderMonitorSettings result = settings.addFileExtension(".meta4");

            assertSame(settings, result);
            assertTrue(settings.getFileExtensions().contains(".meta4"));
            assertTrue(settings.getFileExtensions().contains(".torrent")); // Original should remain
        }

        @Test
        @DisplayName("Should remove file extension")
        void shouldRemoveFileExtension() {
            settings.addFileExtension(".meta4");

            FolderMonitorSettings result = settings.removeFileExtension(".torrent");

            assertSame(settings, result);
            assertFalse(settings.getFileExtensions().contains(".torrent"));
            assertTrue(settings.getFileExtensions().contains(".meta4"));
        }

        @Test
        @DisplayName("Should handle null file extensions gracefully")
        void shouldHandleNullFileExtensions() {
            assertDoesNotThrow(() -> settings.setFileExtensions(null));
            // Should create empty set when null is passed
            assertTrue(settings.getFileExtensions().isEmpty());
        }

        @Test
        @DisplayName("Should handle empty file extensions set")
        void shouldHandleEmptyFileExtensions() {
            Set<String> emptySet = new HashSet<>();

            settings.setFileExtensions(emptySet);

            assertTrue(settings.getFileExtensions().isEmpty());
        }
    }

    @Nested
    @DisplayName("Boolean Property Tests")
    class BooleanPropertyTests {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("Should set recursive monitoring")
        void shouldSetRecursive(boolean recursive) {
            FolderMonitorSettings result = settings.setRecursive(recursive);

            assertSame(settings, result);
            assertEquals(recursive, settings.isRecursive());
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("Should set process existing files")
        void shouldSetProcessExistingFiles(boolean processExisting) {
            FolderMonitorSettings result = settings.setProcessExistingFiles(processExisting);

            assertSame(settings, result);
            assertEquals(processExisting, settings.isProcessExistingFiles());
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("Should set enabled flag")
        void shouldSetEnabled(boolean enabled) {
            FolderMonitorSettings result = settings.setEnabled(enabled);

            assertSame(settings, result);
            assertEquals(enabled, settings.isEnabled());
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("Should set case sensitive flag")
        void shouldSetCaseSensitive(boolean caseSensitive) {
            FolderMonitorSettings result = settings.setCaseSensitive(caseSensitive);

            assertSame(settings, result);
            assertEquals(caseSensitive, settings.isCaseSensitive());
        }
    }

    @Nested
    @DisplayName("File Action Tests")
    class FileActionTests {

        @ParameterizedTest
        @EnumSource(FolderMonitorSettings.FileAction.class)
        @DisplayName("Should set all file actions")
        void shouldSetFileAction(FolderMonitorSettings.FileAction action) {
            FolderMonitorSettings result = settings.setFileAction(action);

            assertSame(settings, result);
            assertEquals(action, settings.getFileAction());
        }

        @Test
        @DisplayName("Should handle null file action")
        void shouldHandleNullFileAction() {
            assertDoesNotThrow(() -> settings.setFileAction(null));
            assertNull(settings.getFileAction());
        }
    }

    @Nested
    @DisplayName("Directory Path Tests")
    class DirectoryPathTests {

        @Test
        @DisplayName("Should set move to directory")
        void shouldSetMoveToDirectory() {
            Path testPath = Paths.get("/tmp/test");

            FolderMonitorSettings result = settings.setMoveToDirectory(testPath);

            assertSame(settings, result);
            assertEquals(testPath, settings.getMoveToDirectory());
        }

        @Test
        @DisplayName("Should handle null move to directory")
        void shouldHandleNullMoveToDirectory() {
            settings.setMoveToDirectory(Paths.get("/test"));

            settings.setMoveToDirectory(null);

            assertNull(settings.getMoveToDirectory());
        }
    }

    @Nested
    @DisplayName("Numeric Property Tests")
    class NumericPropertyTests {

        @ParameterizedTest
        @ValueSource(ints = {1, 5, 10, 50, 100})
        @DisplayName("Should set max files per batch")
        void shouldSetMaxFilesPerBatch(int maxFiles) {
            FolderMonitorSettings result = settings.setMaxFilesPerBatch(maxFiles);

            assertSame(settings, result);
            assertEquals(maxFiles, settings.getMaxFilesPerBatch());
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, 1024L, 1048576L, Long.MAX_VALUE})
        @DisplayName("Should set max file size")
        void shouldSetMaxFileSize(long maxSize) {
            FolderMonitorSettings result = settings.setMaxFileSize(maxSize);

            assertSame(settings, result);
            assertEquals(maxSize, settings.getMaxFileSize());
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, 100L, 1024L, 10240L})
        @DisplayName("Should set min file size")
        void shouldSetMinFileSize(long minSize) {
            FolderMonitorSettings result = settings.setMinFileSize(minSize);

            assertSame(settings, result);
            assertEquals(minSize, settings.getMinFileSize());
        }

        @Test
        @DisplayName("Should handle negative max files per batch")
        void shouldHandleNegativeMaxFiles() {
            settings.setMaxFilesPerBatch(-1);
            assertEquals(-1, settings.getMaxFilesPerBatch());
        }

        @Test
        @DisplayName("Should handle negative file sizes")
        void shouldHandleNegativeFileSizes() {
            settings.setMaxFileSize(-1L);
            settings.setMinFileSize(-1L);

            assertEquals(-1L, settings.getMaxFileSize());
            assertEquals(-1L, settings.getMinFileSize());
        }
    }

    @Nested
    @DisplayName("Duration Tests")
    class DurationTests {

        @Test
        @DisplayName("Should set debounce delay")
        void shouldSetDebounceDelay() {
            Duration delay = Duration.ofSeconds(5);

            FolderMonitorSettings result = settings.setDebounceDelay(delay);

            assertSame(settings, result);
            assertEquals(delay, settings.getDebounceDelay());
        }

        @Test
        @DisplayName("Should handle null debounce delay")
        void shouldHandleNullDebounceDelay() {
            assertDoesNotThrow(() -> settings.setDebounceDelay(null));
            assertNull(settings.getDebounceDelay());
        }

        @Test
        @DisplayName("Should handle zero duration")
        void shouldHandleZeroDuration() {
            Duration zero = Duration.ZERO;

            settings.setDebounceDelay(zero);

            assertEquals(zero, settings.getDebounceDelay());
        }

        @Test
        @DisplayName("Should handle negative duration")
        void shouldHandleNegativeDuration() {
            Duration negative = Duration.ofSeconds(-1);

            settings.setDebounceDelay(negative);

            assertEquals(negative, settings.getDebounceDelay());
        }
    }

    @Nested
    @DisplayName("Exclude Patterns Tests")
    class ExcludePatternsTests {

        @Test
        @DisplayName("Should set exclude patterns")
        void shouldSetExcludePatterns() {
            Set<String> patterns = Set.of("*.tmp", "*.bak", ".*");

            FolderMonitorSettings result = settings.setExcludePatterns(patterns);

            assertSame(settings, result);
            assertEquals(patterns, settings.getExcludePatterns());
        }

        @Test
        @DisplayName("Should add exclude pattern")
        void shouldAddExcludePattern() {
            FolderMonitorSettings result = settings.addExcludePattern("*.tmp");

            assertSame(settings, result);
            assertTrue(settings.getExcludePatterns().contains("*.tmp"));
        }

        @Test
        @DisplayName("Should handle null exclude patterns")
        void shouldHandleNullExcludePatterns() {
            assertDoesNotThrow(() -> settings.setExcludePatterns(null));
            assertTrue(settings.getExcludePatterns().isEmpty());
        }

        @Test
        @DisplayName("Should handle empty exclude patterns")
        void shouldHandleEmptyExcludePatterns() {
            Set<String> emptySet = new HashSet<>();

            settings.setExcludePatterns(emptySet);

            assertTrue(settings.getExcludePatterns().isEmpty());
        }
    }

    @Nested
    @DisplayName("Copy and ToString Tests")
    class CopyAndToStringTests {

        @Test
        @DisplayName("Should create deep copy of settings")
        void shouldCreateDeepCopy() {
            // Configure original settings
            Path moveDir = Paths.get("/tmp/move");
            settings.setFileExtensions(Set.of(".torrent", ".meta4"))
                    .setRecursive(true)
                    .setFileAction(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY)
                    .setMoveToDirectory(moveDir)
                    .setProcessExistingFiles(false)
                    .setDebounceDelay(Duration.ofSeconds(5))
                    .setEnabled(false)
                    .setMaxFilesPerBatch(20)
                    .setCaseSensitive(true)
                    .setExcludePatterns(Set.of("*.tmp", "*.bak"))
                    .setMaxFileSize(1048576L)
                    .setMinFileSize(1024L);

            FolderMonitorSettings copy = settings.copy();

            // Verify copy is different instance but has same values
            assertNotSame(settings, copy);
            assertEquals(settings.getFileExtensions(), copy.getFileExtensions());
            assertEquals(settings.isRecursive(), copy.isRecursive());
            assertEquals(settings.getFileAction(), copy.getFileAction());
            assertEquals(settings.getMoveToDirectory(), copy.getMoveToDirectory());
            assertEquals(settings.isProcessExistingFiles(), copy.isProcessExistingFiles());
            assertEquals(settings.getDebounceDelay(), copy.getDebounceDelay());
            assertEquals(settings.isEnabled(), copy.isEnabled());
            assertEquals(settings.getMaxFilesPerBatch(), copy.getMaxFilesPerBatch());
            assertEquals(settings.isCaseSensitive(), copy.isCaseSensitive());
            assertEquals(settings.getExcludePatterns(), copy.getExcludePatterns());
            assertEquals(settings.getMaxFileSize(), copy.getMaxFileSize());
            assertEquals(settings.getMinFileSize(), copy.getMinFileSize());
        }

        @Test
        @DisplayName("Should create independent copy")
        void shouldCreateIndependentCopy() {
            settings.addFileExtension(".meta4");
            FolderMonitorSettings copy = settings.copy();

            // Modify original
            settings.addFileExtension(".magnet");
            settings.addExcludePattern("*.tmp");

            // Copy should not be affected
            assertFalse(copy.getFileExtensions().contains(".magnet"));
            assertFalse(copy.getExcludePatterns().contains("*.tmp"));
        }

        @Test
        @DisplayName("Should generate meaningful toString")
        void shouldGenerateMeaningfulToString() {
            String toString = settings.toString();

            assertNotNull(toString);
            assertTrue(toString.contains("FolderMonitorSettings"));
            assertTrue(toString.contains("fileExtensions"));
            assertTrue(toString.contains("recursive"));
            assertTrue(toString.contains("fileAction"));
            assertTrue(toString.contains("enabled"));
        }

        @Test
        @DisplayName("Should include all properties in toString")
        void shouldIncludeAllPropertiesInToString() {
            settings.setFileExtensions(Set.of(".test"))
                    .setRecursive(true)
                    .setFileAction(FolderMonitorSettings.FileAction.DELETE)
                    .setMoveToDirectory(Paths.get("/test"))
                    .setProcessExistingFiles(false)
                    .setDebounceDelay(Duration.ofSeconds(10))
                    .setEnabled(false)
                    .setMaxFilesPerBatch(5)
                    .setCaseSensitive(true)
                    .setExcludePatterns(Set.of("*.tmp"))
                    .setMaxFileSize(2048L)
                    .setMinFileSize(512L);

            String toString = settings.toString();

            // Verify all configured values appear in string
            assertTrue(toString.contains(".test"));
            assertTrue(toString.contains("true"));
            assertTrue(toString.contains("DELETE"));
            assertTrue(toString.contains("/test"));
            assertTrue(toString.contains("false"));
            assertTrue(toString.contains("10"));
            assertTrue(toString.contains("5"));
            assertTrue(toString.contains("*.tmp"));
            assertTrue(toString.contains("2048"));
            assertTrue(toString.contains("512"));
        }
    }

    @Nested
    @DisplayName("Method Chaining Tests")
    class MethodChainingTests {

        @Test
        @DisplayName("Should support method chaining for all setters")
        void shouldSupportMethodChaining() {
            FolderMonitorSettings result = settings
                    .setFileExtensions(Set.of(".test"))
                    .addFileExtension(".meta4")
                    .removeFileExtension(".torrent")
                    .setRecursive(true)
                    .setFileAction(FolderMonitorSettings.FileAction.DELETE)
                    .setMoveToDirectory(Paths.get("/test"))
                    .setProcessExistingFiles(false)
                    .setDebounceDelay(Duration.ofSeconds(3))
                    .setEnabled(false)
                    .setMaxFilesPerBatch(15)
                    .setCaseSensitive(true)
                    .setExcludePatterns(Set.of("*.tmp"))
                    .addExcludePattern("*.bak")
                    .setMaxFileSize(1024L)
                    .setMinFileSize(100L);

            assertSame(settings, result);

            // Verify all changes were applied
            assertTrue(settings.getFileExtensions().contains(".test"));
            assertTrue(settings.getFileExtensions().contains(".meta4"));
            assertFalse(settings.getFileExtensions().contains(".torrent"));
            assertTrue(settings.isRecursive());
            assertEquals(FolderMonitorSettings.FileAction.DELETE, settings.getFileAction());
            assertEquals(Paths.get("/test"), settings.getMoveToDirectory());
            assertFalse(settings.isProcessExistingFiles());
            assertEquals(Duration.ofSeconds(3), settings.getDebounceDelay());
            assertFalse(settings.isEnabled());
            assertEquals(15, settings.getMaxFilesPerBatch());
            assertTrue(settings.isCaseSensitive());
            assertTrue(settings.getExcludePatterns().contains("*.tmp"));
            assertTrue(settings.getExcludePatterns().contains("*.bak"));
            assertEquals(1024L, settings.getMaxFileSize());
            assertEquals(100L, settings.getMinFileSize());
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle concurrent modifications safely")
        void shouldHandleConcurrentModificationsSafely() {
            // This test verifies that the settings object can be safely used
            // in a concurrent environment by checking that collections are properly copied
            Set<String> originalExtensions = new HashSet<>(Set.of(".torrent", ".meta4"));
            settings.setFileExtensions(originalExtensions);

            // Modify the original set
            originalExtensions.add(".magnet");

            // Settings should not be affected
            assertFalse(settings.getFileExtensions().contains(".magnet"));
        }

        @Test
        @DisplayName("Should handle extreme values gracefully")
        void shouldHandleExtremeValuesGracefully() {
            assertDoesNotThrow(() -> {
                settings.setMaxFilesPerBatch(Integer.MAX_VALUE);
                settings.setMaxFileSize(Long.MAX_VALUE);
                settings.setMinFileSize(Long.MIN_VALUE);
                settings.setDebounceDelay(Duration.ofDays(365));
            });

            assertEquals(Integer.MAX_VALUE, settings.getMaxFilesPerBatch());
            assertEquals(Long.MAX_VALUE, settings.getMaxFileSize());
            assertEquals(Long.MIN_VALUE, settings.getMinFileSize());
            assertEquals(Duration.ofDays(365), settings.getDebounceDelay());
        }
    }
}
