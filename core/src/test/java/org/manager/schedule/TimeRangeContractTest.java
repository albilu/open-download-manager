package org.manager.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("TimeRange containment, overlap and parsing contract")
class TimeRangeContractTest {

    @Test
    @DisplayName("containment is inclusive on both endpoints")
    void containmentIsInclusive() {
        TimeRange range = new TimeRange("09:00", "17:00");
        assertTrue(range.contains(LocalTime.of(9, 0)), "start is contained");
        assertTrue(range.contains(LocalTime.of(17, 0)), "end is contained");
        assertTrue(range.contains(LocalTime.of(12, 0)));
        assertFalse(range.contains(LocalTime.of(8, 59)));
        assertFalse(range.contains(LocalTime.of(17, 1)));
    }

    @Test
    @DisplayName("midnight-spanning ranges contain the late and early hours but not midday")
    void midnightSpanningContainment() {
        TimeRange night = new TimeRange("22:00", "06:00");
        assertTrue(night.spansMidnight());
        assertTrue(night.contains(LocalTime.of(23, 30)));
        assertTrue(night.contains(LocalTime.of(2, 0)));
        assertTrue(night.contains(LocalTime.of(6, 0)));
        assertTrue(night.contains(LocalTime.of(22, 0)));
        assertFalse(night.contains(LocalTime.NOON));
        assertFalse(night.contains(LocalTime.of(12, 0)));
    }

    @Test
    @DisplayName("duration counts the minutes of the window, across midnight too")
    void durations() {
        assertEquals(8 * 3600, new TimeRange("09:00", "17:00").getDurationSeconds());
        assertEquals(8 * 3600, new TimeRange("22:00", "06:00").getDurationSeconds(),
                "22:00->06:00 spans midnight and must count 8 hours");
        assertEquals(0, new TimeRange("09:00", "09:00").getDurationSeconds());
        assertEquals(23 * 3600 + 59 * 60 + 59, TimeRange.allDay().getDurationSeconds());
    }

    @Test
    @DisplayName("overlaps detects shared minutes in every arrangement")
    void overlapDetection() {
        TimeRange morning = new TimeRange("06:00", "12:00");
        TimeRange business = new TimeRange("09:00", "17:00");
        TimeRange evening = new TimeRange("18:00", "22:00");
        TimeRange night = new TimeRange("23:00", "02:00");

        assertTrue(morning.overlaps(business));
        assertTrue(business.overlaps(morning), "overlap is symmetric");
        assertFalse(morning.overlaps(evening));
        assertFalse(night.overlaps(evening),
                "23:00-02:00 and 18:00-22:00 share no minutes despite the midnight wrap");
        assertTrue(new TimeRange("21:00", "23:30").overlaps(evening), "21:00 is inside 18:00-22:00");
        assertTrue(night.overlaps(new TimeRange("01:00", "04:00")), "01:00 is inside the night range tail");
        assertFalse(new TimeRange("13:00", "14:00").overlaps(night));
    }

    @ParameterizedTest(name = "\"{0}\" parses to {1}-{2}")
    @CsvSource({
            "09:00-17:00, 09:00, 17:00",
            "9:30-14:45, 09:30, 14:45",
            "09:00:15-17:30:45, 09:00:15, 17:30:45",
            "8:05:10-9:10:20, 08:05:10, 09:10:20",
            "  09:00 - 17:00 , 09:00, 17:00"
    })
    void parsingAcceptsSupportedFormats(String input, String start, String end) {
        TimeRange range = TimeRange.parse(input);
        assertEquals(LocalTime.parse(start), range.getStartTime());
        assertEquals(LocalTime.parse(end), range.getEndTime());
    }

    @Test
    @DisplayName("malformed input is rejected with a helpful message")
    void malformedInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> TimeRange.parse(null));
        assertThrows(IllegalArgumentException.class, () -> TimeRange.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> TimeRange.parse("09:00"));
        assertThrows(IllegalArgumentException.class, () -> TimeRange.parse("09:00-17:00-23:00"));
        assertThrows(IllegalArgumentException.class, () -> TimeRange.parse("25:00-26:00"));
        assertThrows(IllegalArgumentException.class, () -> new TimeRange("09:00", null));
        assertThrows(IllegalArgumentException.class, () -> new TimeRange((String) null, "17:00"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TimeRange.parse("garbage"));
        assertTrue(ex.getMessage().contains("HH:mm"));
    }

    @Test
    @DisplayName("string representations are stable")
    void stringForms() {
        TimeRange range = new TimeRange("09:00:30", "17:30:45");
        assertEquals("09:00-17:30", range.toString());
        assertEquals("09:00:30-17:30:45", range.toDetailedString());
    }

    @Test
    @DisplayName("equality is by start/end pair, independent of construction path")
    void equalityContract() {
        TimeRange a = new TimeRange("09:00", "17:00");
        TimeRange b = TimeRange.parse("09:00-17:00");
        TimeRange c = new TimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0));
        assertEquals(a, b);
        assertEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new TimeRange("09:00", "18:00"));
        assertNotEquals(a, null);
        assertNotEquals(a, "09:00-17:00");
    }
}
