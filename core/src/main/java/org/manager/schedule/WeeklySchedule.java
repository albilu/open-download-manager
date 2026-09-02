package org.manager.schedule;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a weekly schedule with time ranges for each day of the week.
 * Each day can have multiple time ranges when downloads should be active.
 */
public class WeeklySchedule {

    private final Map<DayOfWeek, List<TimeRange>> schedule;
    private boolean enabled;

    /**
     * Creates a new empty weekly schedule.
     */
    public WeeklySchedule() {
        this.schedule = new EnumMap<>(DayOfWeek.class);
        this.enabled = true;

        // Initialize empty lists for each day
        for (DayOfWeek day : DayOfWeek.values()) {
            schedule.put(day, new ArrayList<>());
        }
    }

    /**
     * Creates a weekly schedule where all days have the same time ranges.
     *
     * @param timeRanges The time ranges to apply to all days
     */
    public WeeklySchedule(List<TimeRange> timeRanges) {
        this();
        Objects.requireNonNull(timeRanges, "Time ranges cannot be null");

        for (DayOfWeek day : DayOfWeek.values()) {
            schedule.put(day, new ArrayList<>(timeRanges));
        }
    }

    /**
     * Creates a weekly schedule with different time ranges for weekdays and weekends.
     *
     * @param weekdayRanges Time ranges for Monday through Friday
     * @param weekendRanges Time ranges for Saturday and Sunday
     */
    public WeeklySchedule(List<TimeRange> weekdayRanges, List<TimeRange> weekendRanges) {
        this();
        Objects.requireNonNull(weekdayRanges, "Weekday ranges cannot be null");
        Objects.requireNonNull(weekendRanges, "Weekend ranges cannot be null");

        // Set weekday ranges (Monday to Friday)
        for (DayOfWeek day : Arrays.asList(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                                          DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            schedule.put(day, new ArrayList<>(weekdayRanges));
        }

        // Set weekend ranges (Saturday and Sunday)
        schedule.put(DayOfWeek.SATURDAY, new ArrayList<>(weekendRanges));
        schedule.put(DayOfWeek.SUNDAY, new ArrayList<>(weekendRanges));
    }

    /**
     * Creates a weekly schedule that is always active (24/7).
     *
     * @return A schedule that allows downloads at all times
     */
    public static WeeklySchedule alwaysActive() {
        WeeklySchedule schedule = new WeeklySchedule();
        TimeRange allDay = TimeRange.allDay();

        for (DayOfWeek day : DayOfWeek.values()) {
            schedule.addTimeRange(day, allDay);
        }

        return schedule;
    }

    /**
     * Creates a weekly schedule that is never active.
     *
     * @return A schedule that never allows downloads
     */
    public static WeeklySchedule neverActive() {
        WeeklySchedule schedule = new WeeklySchedule();
        schedule.setEnabled(false);
        return schedule;
    }

    /**
     * Builds a schedule from a 7x24 hour grid (uGet-style editing surface).
     * Contiguous active-hour runs become time ranges covering the full hours
     * (for example hours 9-11 of Monday yield 09:00:00-11:59:59).
     *
     * @param hourGrid {@code [7][24]} booleans; row 0 = Monday, row 6 = Sunday
     * @return a schedule reflecting the grid
     */
    public static WeeklySchedule fromHourGrid(boolean[][] hourGrid) {
        Objects.requireNonNull(hourGrid, "Hour grid cannot be null");
        WeeklySchedule schedule = new WeeklySchedule();
        DayOfWeek[] days = DayOfWeek.values();
        for (int d = 0; d < days.length && d < hourGrid.length; d++) {
            boolean[] hours = hourGrid[d];
            if (hours == null) {
                continue;
            }
            int runStart = -1;
            for (int h = 0; h <= 24; h++) {
                boolean active = h < 24 && h < hours.length && hours[h];
                if (active && runStart < 0) {
                    runStart = h;
                } else if (!active && runStart >= 0) {
                    LocalTime end = h >= 24
                            ? LocalTime.of(23, 59, 59)
                            : LocalTime.of(h, 0).minusSeconds(1);
                    schedule.addTimeRange(days[d], new TimeRange(
                            LocalTime.of(runStart, 0), end));
                    runStart = -1;
                }
            }
        }
        return schedule;
    }

    /**
     * Encodes an hour grid as 42 lowercase hex characters (168 bits, one per
     * hour, row 0 = Monday hour 0 first).
     *
     * @param hourGrid {@code [7][24]} booleans
     * @return the hex string
     */
    public static String hourGridToString(boolean[][] hourGrid) {
        StringBuilder hex = new StringBuilder(42);
        for (int nibble = 0; nibble < 168; nibble += 4) {
            int d = nibble / 24;
            int h = nibble % 24;
            int value = 0;
            for (int bit = 0; bit < 4 && h + bit < 24; bit++) {
                if (hourGrid[d][h + bit]) {
                    value |= 1 << (3 - bit);
                }
            }
            hex.append("0123456789abcdef".charAt(value));
        }
        return hex.toString();
    }

    /**
     * Decodes a hex hour grid produced by {@link #hourGridToString}.
     *
     * @param hex 42 hex characters (or shorter/null for all-inactive)
     * @return the {@code [7][24]} grid
     */
    public static boolean[][] hourGridFromString(String hex) {
        boolean[][] grid = new boolean[7][24];
        if (hex == null || hex.isBlank()) {
            return grid;
        }
        for (int nibble = 0; nibble < 168; nibble += 4) {
            int index = nibble / 4;
            if (index >= hex.length()) {
                break;
            }
            int value = Character.digit(hex.charAt(index), 16);
            if (value < 0) {
                continue;
            }
            int d = nibble / 24;
            int h = nibble % 24;
            for (int bit = 0; bit < 4 && h + bit < 24; bit++) {
                grid[d][h + bit] = (value & (1 << (3 - bit))) != 0;
            }
        }
        return grid;
    }

    /**
     * Projects this schedule onto the 7x24 settings grid. A cell represents a
     * whole hour, so its midpoint is sampled; the built-in presets all use
     * hour-aligned boundaries and therefore map without ambiguity.
     *
     * @return {@code [7][24]} booleans, row 0 = Monday
     */
    public boolean[][] toHourGrid() {
        boolean[][] grid = new boolean[7][24];
        LocalDate monday = LocalDate.of(2026, 8, 17);
        for (int day = 0; day < 7; day++) {
            for (int hour = 0; hour < 24; hour++) {
                grid[day][hour] = isActiveAt(
                        monday.plusDays(day).atTime(hour, 30));
            }
        }
        return grid;
    }

    /**
     * Creates a business hours schedule (9 AM to 5 PM, Monday to Friday).
     *
     * @return A schedule for standard business hours
     */
    public static WeeklySchedule businessHours() {
        WeeklySchedule schedule = new WeeklySchedule();
        TimeRange businessHours = new TimeRange("09:00", "17:00");

        // Add business hours to weekdays only
        for (DayOfWeek day : Arrays.asList(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                                          DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            schedule.addTimeRange(day, businessHours);
        }

        return schedule;
    }

    /**
     * Creates a night hours schedule (10 PM to 6 AM, all days).
     *
     * @return A schedule for night hours
     */
    public static WeeklySchedule nightHours() {
        WeeklySchedule schedule = new WeeklySchedule();
        TimeRange nightHours = new TimeRange("22:00", "06:00");

        for (DayOfWeek day : DayOfWeek.values()) {
            schedule.addTimeRange(day, nightHours);
        }

        return schedule;
    }

    /**
     * Adds a time range to a specific day.
     *
     * @param day The day of the week
     * @param timeRange The time range to add
     * @return This schedule for method chaining
     */
    public WeeklySchedule addTimeRange(DayOfWeek day, TimeRange timeRange) {
        Objects.requireNonNull(day, "Day cannot be null");
        Objects.requireNonNull(timeRange, "Time range cannot be null");

        schedule.get(day).add(timeRange);
        return this;
    }

    /**
     * Adds a time range to multiple days.
     *
     * @param days The days of the week
     * @param timeRange The time range to add
     * @return This schedule for method chaining
     */
    public WeeklySchedule addTimeRange(Collection<DayOfWeek> days, TimeRange timeRange) {
        Objects.requireNonNull(days, "Days cannot be null");
        Objects.requireNonNull(timeRange, "Time range cannot be null");

        for (DayOfWeek day : days) {
            addTimeRange(day, timeRange);
        }
        return this;
    }

    /**
     * Adds a time range to all days of the week.
     *
     * @param timeRange The time range to add
     * @return This schedule for method chaining
     */
    public WeeklySchedule addTimeRangeAllDays(TimeRange timeRange) {
        return addTimeRange(Arrays.asList(DayOfWeek.values()), timeRange);
    }

    /**
     * Removes all time ranges from a specific day.
     *
     * @param day The day of the week
     * @return This schedule for method chaining
     */
    public WeeklySchedule clearDay(DayOfWeek day) {
        Objects.requireNonNull(day, "Day cannot be null");
        schedule.get(day).clear();
        return this;
    }

    /**
     * Removes all time ranges from all days.
     *
     * @return This schedule for method chaining
     */
    public WeeklySchedule clearAll() {
        for (DayOfWeek day : DayOfWeek.values()) {
            clearDay(day);
        }
        return this;
    }

    /**
     * Gets the time ranges for a specific day.
     *
     * @param day The day of the week
     * @return A list of time ranges for the day (defensive copy)
     */
    public List<TimeRange> getTimeRanges(DayOfWeek day) {
        Objects.requireNonNull(day, "Day cannot be null");
        return new ArrayList<>(schedule.get(day));
    }

    /**
     * Sets the time ranges for a specific day, replacing any existing ranges.
     *
     * @param day The day of the week
     * @param timeRanges The time ranges to set
     * @return This schedule for method chaining
     */
    public WeeklySchedule setTimeRanges(DayOfWeek day, List<TimeRange> timeRanges) {
        Objects.requireNonNull(day, "Day cannot be null");
        Objects.requireNonNull(timeRanges, "Time ranges cannot be null");

        schedule.get(day).clear();
        schedule.get(day).addAll(timeRanges);
        return this;
    }

    /**
     * Checks if downloads should be active at the given date and time.
     *
     * @param dateTime The date and time to check
     * @return true if downloads should be active, false otherwise
     */
    public boolean isActiveAt(LocalDateTime dateTime) {
        Objects.requireNonNull(dateTime, "Date time cannot be null");

        if (!enabled) {
            return false;
        }

        DayOfWeek day = dateTime.getDayOfWeek();
        LocalTime time = dateTime.toLocalTime();

        // A range belongs to the calendar day on which it starts. Therefore
        // Monday 22:00-06:00 covers late Monday and early Tuesday, but does
        // not make early Monday active. TimeRange.contains() alone cannot
        // express that day ownership.
        boolean startsToday = schedule.get(day).stream().anyMatch(range ->
                range.spansMidnight()
                        ? !time.isBefore(range.getStartTime())
                        : range.contains(time));
        if (startsToday) {
            return true;
        }
        DayOfWeek previousDay = day.minus(1);
        return schedule.get(previousDay).stream().anyMatch(range ->
                range.spansMidnight() && !time.isAfter(range.getEndTime()));
    }

    /**
     * Checks if downloads should be active at the current time.
     *
     * @return true if downloads should be active now, false otherwise
     */
    public boolean isActiveNow() {
        return isActiveAt(LocalDateTime.now());
    }

    /**
     * Checks if the schedule is enabled.
     *
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the entire schedule.
     *
     * @param enabled true to enable, false to disable
     * @return This schedule for method chaining
     */
    public WeeklySchedule setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Checks if any day has time ranges defined.
     *
     * @return true if any day has time ranges, false otherwise
     */
    public boolean hasAnyTimeRanges() {
        return schedule.values().stream().anyMatch(ranges -> !ranges.isEmpty());
    }

    /**
     * Checks if a specific day has any time ranges defined.
     *
     * @param day The day of the week
     * @return true if the day has time ranges, false otherwise
     */
    public boolean hasTimeRanges(DayOfWeek day) {
        Objects.requireNonNull(day, "Day cannot be null");
        return !schedule.get(day).isEmpty();
    }

    /**
     * Gets a copy of the entire schedule map.
     *
     * @return A defensive copy of the schedule
     */
    @JsonProperty("scheduleMap")
    public Map<DayOfWeek, List<TimeRange>> getScheduleMap() {
        Map<DayOfWeek, List<TimeRange>> copy = new EnumMap<>(DayOfWeek.class);
        for (Map.Entry<DayOfWeek, List<TimeRange>> entry : schedule.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    /** Restores the complete day/range map from persisted JSON. Missing days
     * are intentionally inactive instead of inheriting stale constructor
     * defaults. */
    @JsonProperty("scheduleMap")
    public void setScheduleMap(Map<DayOfWeek, List<TimeRange>> persisted) {
        for (DayOfWeek day : DayOfWeek.values()) {
            List<TimeRange> ranges = persisted != null ? persisted.get(day) : null;
            schedule.put(day, ranges == null ? new ArrayList<>() : new ArrayList<>(ranges));
        }
    }

    /**
     * Creates a copy of this weekly schedule.
     *
     * @return A new WeeklySchedule with the same settings
     */
    public WeeklySchedule copy() {
        WeeklySchedule copy = new WeeklySchedule();
        copy.enabled = this.enabled;

        for (Map.Entry<DayOfWeek, List<TimeRange>> entry : this.schedule.entrySet()) {
            copy.schedule.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        return copy;
    }

    /**
     * Returns a human-readable string representation of this schedule.
     *
     * @return A string describing the schedule
     */
    @Override
    public String toString() {
        if (!enabled) {
            return "WeeklySchedule{disabled}";
        }

        StringBuilder sb = new StringBuilder("WeeklySchedule{\n");
        for (DayOfWeek day : DayOfWeek.values()) {
            List<TimeRange> ranges = schedule.get(day);
            sb.append("  ").append(day).append(": ");
            if (ranges.isEmpty()) {
                sb.append("inactive");
            } else {
                sb.append(ranges);
            }
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        WeeklySchedule that = (WeeklySchedule) obj;
        return enabled == that.enabled && Objects.equals(schedule, that.schedule);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schedule, enabled);
    }
}
