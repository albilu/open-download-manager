package org.manager.clipboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClipboardEvent.
 * Tests event creation, URL handling, and metadata management.
 */
@DisplayName("ClipboardEvent Unit Tests")
class ClipboardEventTest {

    private String sampleContent;
    private List<URI> sampleUrls;
    private ClipboardEvent.ClipboardEventType sampleEventType;

    @BeforeEach
    void setUp() {
        sampleContent = "Check out https://example.com/file.zip";
        sampleUrls = Arrays.asList(
            URI.create("https://example.com/file.zip"),
            URI.create("https://test.org/app.exe")
        );
        sampleEventType = ClipboardEvent.ClipboardEventType.URLS_DETECTED;
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTest {

        @Test
        @DisplayName("Should create event with valid parameters")
        void testValidConstructor() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);

            assertEquals(sampleContent, event.getContent());
            assertEquals(sampleUrls.size(), event.getDetectedUrls().size());
            assertEquals(sampleEventType, event.getEventType());
            assertNotNull(event.getTimestamp());
        }

        @Test
        @DisplayName("Should handle null content")
        void testConstructorWithNullContent() {
            ClipboardEvent event = new ClipboardEvent(null, sampleUrls, sampleEventType);

            assertNull(event.getContent());
            assertEquals(sampleUrls.size(), event.getDetectedUrls().size());
            assertEquals(sampleEventType, event.getEventType());
        }

        @Test
        @DisplayName("Should handle null URL list")
        void testConstructorWithNullUrls() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, null, sampleEventType);

            assertEquals(sampleContent, event.getContent());
            assertTrue(event.getDetectedUrls().isEmpty());
            assertEquals(sampleEventType, event.getEventType());
        }

        @Test
        @DisplayName("Should handle empty URL list")
        void testConstructorWithEmptyUrls() {
            List<URI> emptyUrls = new ArrayList<>();
            ClipboardEvent event = new ClipboardEvent(sampleContent, emptyUrls, sampleEventType);

            assertEquals(sampleContent, event.getContent());
            assertTrue(event.getDetectedUrls().isEmpty());
            assertEquals(sampleEventType, event.getEventType());
        }

        @Test
        @DisplayName("Should create defensive copy of URL list")
        void testConstructorCreatesDefensiveCopy() {
            List<URI> originalUrls = new ArrayList<>(sampleUrls);
            ClipboardEvent event = new ClipboardEvent(sampleContent, originalUrls, sampleEventType);

            // Modify original list
            originalUrls.add(URI.create("https://added.com/file.pdf"));

            // Event should not be affected
            assertEquals(sampleUrls.size(), event.getDetectedUrls().size());
            assertFalse(event.getDetectedUrls().contains(URI.create("https://added.com/file.pdf")));
        }

        @Test
        @DisplayName("Should set timestamp automatically")
        void testTimestampSetAutomatically() {
            Instant beforeCreation = Instant.now();
            ClipboardEvent event = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);
            Instant afterCreation = Instant.now();

            assertNotNull(event.getTimestamp());
            assertTrue(event.getTimestamp().isAfter(beforeCreation.minusMillis(10)));
            assertTrue(event.getTimestamp().isBefore(afterCreation.plusMillis(10)));
        }
    }

    @Nested
    @DisplayName("Getter Tests")
    class GetterTest {

        private ClipboardEvent event;

        @BeforeEach
        void setUp() {
            event = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);
        }

        @Test
        @DisplayName("Should return correct content")
        void testGetContent() {
            assertEquals(sampleContent, event.getContent());
        }

        @Test
        @DisplayName("Should return immutable URL list")
        void testGetDetectedUrlsReturnsImmutableList() {
            List<URI> urls = event.getDetectedUrls();

            assertThrows(UnsupportedOperationException.class,
                () -> urls.add(URI.create("https://new.com/file.zip")),
                "Should not be able to modify returned URL list");
        }

        @Test
        @DisplayName("Should return correct event type")
        void testGetEventType() {
            assertEquals(sampleEventType, event.getEventType());
        }

        @Test
        @DisplayName("Should return timestamp")
        void testGetTimestamp() {
            assertNotNull(event.getTimestamp());
            assertTrue(event.getTimestamp().isBefore(Instant.now().plusSeconds(1)));
        }
    }

    @Nested
    @DisplayName("URL Count Tests")
    class UrlCountTest {

        @Test
        @DisplayName("Should return correct URL count")
        void testGetUrlCount() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);
            assertEquals(sampleUrls.size(), event.getUrlCount());
        }

        @Test
        @DisplayName("Should return zero for no URLs")
        void testGetUrlCountZero() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, new ArrayList<>(), sampleEventType);
            assertEquals(0, event.getUrlCount());
        }

        @Test
        @DisplayName("Should return zero for null URLs")
        void testGetUrlCountNullUrls() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, null, sampleEventType);
            assertEquals(0, event.getUrlCount());
        }
    }

    @Nested
    @DisplayName("Has URLs Tests")
    class HasUrlsTest {

        @Test
        @DisplayName("Should return true when URLs are present")
        void testHasUrlsTrue() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);
            assertTrue(event.hasUrls());
        }

        @Test
        @DisplayName("Should return false when no URLs")
        void testHasUrlsFalse() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, new ArrayList<>(), sampleEventType);
            assertFalse(event.hasUrls());
        }

        @Test
        @DisplayName("Should return false for null URLs")
        void testHasUrlsNullUrls() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, null, sampleEventType);
            assertFalse(event.hasUrls());
        }
    }

    @Nested
    @DisplayName("Empty Content Tests")
    class EmptyContentTest {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n", " \t\n "})
        @DisplayName("Should identify empty content correctly")
        void testIsEmptyContent(String content) {
            ClipboardEvent event = new ClipboardEvent(content, sampleUrls, sampleEventType);
            assertTrue(event.isContentEmpty());
        }

        @Test
        @DisplayName("Should identify non-empty content correctly")
        void testIsNotEmptyContent() {
            ClipboardEvent event = new ClipboardEvent("some content", sampleUrls, sampleEventType);
            assertFalse(event.isContentEmpty());
        }
    }

    @Nested
    @DisplayName("Event Type Tests")
    class EventTypeTest {

        @ParameterizedTest
        @EnumSource(ClipboardEvent.ClipboardEventType.class)
        @DisplayName("Should handle all event types")
        void testAllEventTypes(ClipboardEvent.ClipboardEventType eventType) {
            ClipboardEvent event = new ClipboardEvent(sampleContent, sampleUrls, eventType);
            assertEquals(eventType, event.getEventType());
        }

        @Test
        @DisplayName("Should handle URLS_DETECTED event type")
        void testUrlsDetectedEventType() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, sampleUrls,
                ClipboardEvent.ClipboardEventType.URLS_DETECTED);

            assertEquals(ClipboardEvent.ClipboardEventType.URLS_DETECTED, event.getEventType());
            assertTrue(event.hasUrls());
        }

        @Test
        @DisplayName("Should handle CONTENT_CHANGED event type")
        void testContentChangedEventType() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, new ArrayList<>(),
                ClipboardEvent.ClipboardEventType.CONTENT_CHANGED);

            assertEquals(ClipboardEvent.ClipboardEventType.CONTENT_CHANGED, event.getEventType());
            assertFalse(event.hasUrls());
        }

        @Test
        @DisplayName("Should handle CLIPBOARD_EMPTY event type")
        void testClipboardEmptyEventType() {
            ClipboardEvent event = new ClipboardEvent("", new ArrayList<>(),
                ClipboardEvent.ClipboardEventType.CONTENT_CLEARED);

            assertEquals(ClipboardEvent.ClipboardEventType.CONTENT_CLEARED, event.getEventType());
            assertTrue(event.isContentEmpty());
        }
    }

    @Nested
    @DisplayName("Equals and HashCode Tests")
    class EqualsAndHashCodeTest {

        @Test
        @DisplayName("Should be equal to itself")
        void testEqualsWithSelf() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);
            assertEquals(event, event);
            assertEquals(event.hashCode(), event.hashCode());
        }

        @Test
        @DisplayName("Should be equal to identical event")
        void testEqualsWithIdentical() {
            Instant timestamp = Instant.now();

            // Create events with same timestamp using reflection for testing
            ClipboardEvent event1 = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);
            ClipboardEvent event2 = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);

            // Set same timestamp for both events
            setTimestamp(event1, timestamp);
            setTimestamp(event2, timestamp);

            assertEquals(event1, event2);
            assertEquals(event1.hashCode(), event2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal to event with different content")
        void testNotEqualsWithDifferentContent() {
            ClipboardEvent event1 = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);
            ClipboardEvent event2 = new ClipboardEvent("different content", sampleUrls, sampleEventType);

            assertNotEquals(event1, event2);
        }

        @Test
        @DisplayName("Should not be equal to event with different URLs")
        void testNotEqualsWithDifferentUrls() {
            ClipboardEvent event1 = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);
            List<URI> differentUrls = Arrays.asList(URI.create("https://different.com/file.zip"));
            ClipboardEvent event2 = new ClipboardEvent(sampleContent, differentUrls, sampleEventType);

            assertNotEquals(event1, event2);
        }

        @Test
        @DisplayName("Should not be equal to event with different event type")
        void testNotEqualsWithDifferentEventType() {
            ClipboardEvent event1 = new ClipboardEvent(sampleContent, sampleUrls,
                ClipboardEvent.ClipboardEventType.URLS_DETECTED);
            ClipboardEvent event2 = new ClipboardEvent(sampleContent, sampleUrls,
                ClipboardEvent.ClipboardEventType.CONTENT_CHANGED);

            assertNotEquals(event1, event2);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void testNotEqualsWithNull() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);
            assertNotEquals(event, null);
        }

        @Test
        @DisplayName("Should not be equal to different type")
        void testNotEqualsWithDifferentType() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);
            assertNotEquals(event, "not a ClipboardEvent");
        }

        // Helper method to set timestamp via reflection for testing
        private void setTimestamp(ClipboardEvent event, Instant timestamp) {
            try {
                java.lang.reflect.Field field = ClipboardEvent.class.getDeclaredField("timestamp");
                field.setAccessible(true);
                field.set(event, timestamp);
            } catch (Exception e) {
                fail("Failed to set timestamp for testing: " + e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTest {

        @Test
        @DisplayName("Should return formatted string representation")
        void testToString() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, sampleUrls, sampleEventType);
            String result = event.toString();

            assertNotNull(result);
            assertTrue(result.contains("ClipboardEvent"));
            assertTrue(result.contains("type=" + sampleEventType));
            assertTrue(result.contains("urlCount=" + sampleUrls.size()));
            assertTrue(result.contains("timestamp="));
            assertTrue(result.contains("content="));
        }

        @Test
        @DisplayName("Should handle null content in toString")
        void testToStringWithNullContent() {
            ClipboardEvent event = new ClipboardEvent(null, sampleUrls, sampleEventType);
            String result = event.toString();

            assertNotNull(result);
            assertTrue(result.contains("content='null'") || result.contains("content=null"));
        }

        @Test
        @DisplayName("Should truncate long content in toString")
        void testToStringWithLongContent() {
            String longContent = "a".repeat(100);
            ClipboardEvent event = new ClipboardEvent(longContent, sampleUrls, sampleEventType);
            String result = event.toString();

            assertNotNull(result);
            // Content should be truncated
            assertFalse(result.contains(longContent));
            assertTrue(result.length() < longContent.length() + 100);
        }

        @Test
        @DisplayName("Should handle empty URL list in toString")
        void testToStringWithEmptyUrls() {
            ClipboardEvent event = new ClipboardEvent(sampleContent, new ArrayList<>(), sampleEventType);
            String result = event.toString();

            assertNotNull(result);
            assertTrue(result.contains("urlCount=0"));
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCasesTest {

        @Test
        @DisplayName("Should handle very long content")
        void testVeryLongContent() {
            String veryLongContent = "a".repeat(10000);
            ClipboardEvent event = new ClipboardEvent(veryLongContent, sampleUrls, sampleEventType);

            assertEquals(veryLongContent, event.getContent());
            assertFalse(event.isContentEmpty());
        }

        @Test
        @DisplayName("Should handle very large URL list")
        void testVeryLargeUrlList() {
            List<URI> largeUrlList = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                largeUrlList.add(URI.create("https://example" + i + ".com/file.zip"));
            }

            ClipboardEvent event = new ClipboardEvent(sampleContent, largeUrlList, sampleEventType);

            assertEquals(1000, event.getUrlCount());
            assertTrue(event.hasUrls());
            assertEquals(1000, event.getDetectedUrls().size());
        }

        @Test
        @DisplayName("Should handle content with special characters")
        void testContentWithSpecialCharacters() {
            String specialContent = "Content with special chars: üñíçødé 汉字 🚀 \n\t";
            ClipboardEvent event = new ClipboardEvent(specialContent, sampleUrls, sampleEventType);

            assertEquals(specialContent, event.getContent());
            assertFalse(event.isContentEmpty());
        }

        @Test
        @DisplayName("Should handle duplicate URLs in list")
        void testDuplicateUrls() {
            List<URI> duplicateUrls = Arrays.asList(
                URI.create("https://example.com/file.zip"),
                URI.create("https://example.com/file.zip"),
                URI.create("https://test.org/app.exe")
            );

            ClipboardEvent event = new ClipboardEvent(sampleContent, duplicateUrls, sampleEventType);

            // Should preserve all URLs including duplicates
            assertEquals(3, event.getUrlCount());
            assertEquals(3, event.getDetectedUrls().size());
        }

        @Test
        @DisplayName("Should handle mixed URI schemes")
        void testMixedUriSchemes() {
            List<URI> mixedUrls = Arrays.asList(
                URI.create("https://example.com/file.zip"),
                URI.create("ftp://ftp.example.com/file.tar"),
                URI.create("magnet:?xt=urn:btih:1234567890abcdef"),
                URI.create("file:///home/user/file.torrent")
            );

            ClipboardEvent event = new ClipboardEvent(sampleContent, mixedUrls, sampleEventType);

            assertEquals(4, event.getUrlCount());
            assertTrue(event.hasUrls());
        }
    }

    @Nested
    @DisplayName("Event Type Enum Tests")
    class EventTypeEnumTest {

        @Test
        @DisplayName("Should have expected event type values")
        void testEventTypeValues() {
            ClipboardEvent.ClipboardEventType[] values = ClipboardEvent.ClipboardEventType.values();

            assertEquals(4, values.length);
            assertTrue(Arrays.asList(values).contains(ClipboardEvent.ClipboardEventType.URLS_DETECTED));
            assertTrue(Arrays.asList(values).contains(ClipboardEvent.ClipboardEventType.CONTENT_CHANGED));
            assertTrue(Arrays.asList(values).contains(ClipboardEvent.ClipboardEventType.CONTENT_CLEARED));
        }

        @Test
        @DisplayName("Should support valueOf for all event types")
        void testEventTypeValueOf() {
            assertEquals(ClipboardEvent.ClipboardEventType.URLS_DETECTED,
                ClipboardEvent.ClipboardEventType.valueOf("URLS_DETECTED"));
            assertEquals(ClipboardEvent.ClipboardEventType.CONTENT_CHANGED,
                ClipboardEvent.ClipboardEventType.valueOf("CONTENT_CHANGED"));
            assertEquals(ClipboardEvent.ClipboardEventType.CONTENT_CLEARED,
                ClipboardEvent.ClipboardEventType.valueOf("CONTENT_CLEARED"));
        }
    }
}
