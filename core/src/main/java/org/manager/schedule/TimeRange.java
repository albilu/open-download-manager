package org.manager.schedule;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Represents a time range within a day for scheduling purposes.
 * A time range has a start time and an end time, and can span across midnight.
 */
public class TimeRange {

    private final LocalTime startTime;
    private final LocalTime endTime;
    private final boolean spansMidnight;

    /**
     * Creates a new TimeRange with the specified start and end times.
     *
     * @param startTime The start time of the range
     * @param endTime The end time of the range
     */
    public TimeRange(LocalTime startTime, LocalTime endTime) {
        this.startTime = Objects.requireNonNull(startTime, "Start time cannot be null");
        this.endTime = Objects.requireNonNull(endTime, "End time cannot be null");
        this.spansMidnight = endTime.isBefore(startTime);
    }

    /**
     * Creates a new TimeRange from string representations of start and end times.
     * Accepts formats like "HH:mm", "HH:mm:ss", or "H:mm".
     *
     * @param startTimeStr The start time as a string (e.g., "09:00", "14:30:15")
     * @param endTimeStr The end time as a string (e.g., "17:00", "23:59:59")
     * @throws IllegalArgumentException if the time strings cannot be parsed
     */
    public TimeRange(String startTimeStr, String endTimeStr) {
        this.startTime = parseTime(startTimeStr);
        this.endTime = parseTime(endTimeStr);
        this.spansMidnight = endTime.isBefore(startTime);
    }

    /**
     * Creates a TimeRange that represents the entire day (00:00 to 23:59:59).
     *
     * @return A TimeRange covering the entire day
     */
    public static TimeRange allDay() {
        return new TimeRange(LocalTime.MIN, LocalTime.of(23, 59, 59));
    }

    /**
     * Creates a TimeRange from a string in the format "HH:mm-HH:mm" or "HH:mm:ss-HH:mm:ss".
     *
     * @param timeRangeStr The time range string (e.g., "09:00-17:00", "14:30:15-23:00:00")
     * @return A new TimeRange
     * @throws IllegalArgumentException if the string format is invalid
     */
    public static TimeRange parse(String timeRangeStr) {
        if (timeRangeStr == null || timeRangeStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Time range string cannot be null or empty");
        }

        String[] parts = timeRangeStr.trim().split("-");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid time range format. Expected 'HH:mm-HH:mm' or 'HH:mm:ss-HH:mm:ss'");
        }

        return new TimeRange(parts[0].trim(), parts[1].trim());
    }

    /**
     * Parses a time string into a LocalTime object.
     * Supports formats: "HH:mm", "HH:mm:ss", "H:mm", "H:mm:ss"
     */
    private LocalTime parseTime(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Time string cannot be null or empty");
        }

        timeStr = timeStr.trim();

        // Try different time formats
        DateTimeFormatter[] formatters = {
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm"),
            DateTimeFormatter.ofPattern("H:mm")
        };

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalTime.parse(timeStr, formatter);
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }

        throw new IllegalArgumentException("Invalid time format: " + timeStr +
            ". Expected formats: HH:mm, HH:mm:ss, H:mm, or H:mm:ss");
    }

    /**
     * Checks if the given time falls within this time range.
     *
     * @param time The time to check
     * @return true if the time is within this range, false otherwise
     */
    public boolean contains(LocalTime time) {
        Objects.requireNonNull(time, "Time cannot be null");

        if (spansMidnight) {
            // Range spans midnight (e.g., 22:00 to 06:00)
            return time.isAfter(startTime) || time.equals(startTime) ||
                   time.isBefore(endTime) || time.equals(endTime);
        } else {
            // Normal range within the same day
            return (time.isAfter(startTime) || time.equals(startTime)) &&
                   (time.isBefore(endTime) || time.equals(endTime));
        }
    }

    /**
     * Checks if this time range overlaps with another time range.
     *
     * @param other The other time range
     * @return true if the ranges overlap, false otherwise
     */
    public boolean overlaps(TimeRange other) {
        Objects.requireNonNull(other, "Other time range cannot be null");

        return this.contains(other.startTime) || this.contains(other.endTime) ||
               other.contains(this.startTime) || other.contains(this.endTime);
    }

    /**
     * Gets the duration of this time range in seconds.
     * For ranges that span midnight, calculates the total duration across the day boundary.
     *
     * @return The duration in seconds
     */
    public long getDurationSeconds() {
        if (spansMidnight) {
            // Duration from start time to midnight + duration from midnight to end time
            long toMidnight = LocalTime.MAX.toSecondOfDay() - startTime.toSecondOfDay() + 1;
            long fromMidnight = endTime.toSecondOfDay();
            return toMidnight + fromMidnight;
        } else {
            return endTime.toSecondOfDay() - startTime.toSecondOfDay();
        }
    }

    /**
     * Gets the start time of this range.
     *
     * @return The start time
     */
    public LocalTime getStartTime() {
        return startTime;
    }

    /**
     * Gets the end time of this range.
     *
     * @return The end time
     */
    public LocalTime getEndTime() {
        return endTime;
    }

    /**
     * Checks if this time range spans across midnight.
     *
     * @return true if the range spans midnight, false otherwise
     */
    public boolean spansMidnight() {
        return spansMidnight;
    }

    /**
     * Returns a string representation of this time range.
     *
     * @return A string in the format "HH:mm-HH:mm"
     */
    @Override
    public String toString() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        return startTime.format(formatter) + "-" + endTime.format(formatter);
    }

    /**
     * Returns a detailed string representation including seconds.
     *
     * @return A string in the format "HH:mm:ss-HH:mm:ss"
     */
    public String toDetailedString() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        return startTime.format(formatter) + "-" + endTime.format(formatter);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        TimeRange timeRange = (TimeRange) obj;
        return Objects.equals(startTime, timeRange.startTime) &&
               Objects.equals(endTime, timeRange.endTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startTime, endTime);
    }
}
