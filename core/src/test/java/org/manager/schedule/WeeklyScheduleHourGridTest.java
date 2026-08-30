package org.manager.schedule;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.DayOfWeek;
import org.junit.jupiter.api.Test;

/**
 * Tests for the uGet-style hour-grid codec and the grid-to-schedule
 * conversion used by the scheduler settings.
 */
class WeeklyScheduleHourGridTest {

    @Test
    void hourGridRoundTripsThroughHex() {
        boolean[][] grid = new boolean[7][24];
        grid[0][9] = grid[0][10] = grid[0][11] = true;  // Mon 9-11
        grid[5][22] = grid[5][23] = true;               // Sat late night
        grid[6][0] = true;                              // Sun midnight

        String hex = WeeklySchedule.hourGridToString(grid);
        assertEquals(42, hex.length());
        assertArrayEquals(grid, WeeklySchedule.hourGridFromString(hex));
    }

    @Test
    void emptyAndNullGridsDecodeToAllInactive() {
        assertArrayEquals(new boolean[7][24], WeeklySchedule.hourGridFromString(null));
        assertArrayEquals(new boolean[7][24], WeeklySchedule.hourGridFromString(""));
        assertArrayEquals(new boolean[7][24], WeeklySchedule.hourGridFromString("zzzz"));
    }

    @Test
    void contiguousHoursBecomeTimeRanges() {
        boolean[][] grid = new boolean[7][24];
        grid[0][9] = grid[0][10] = grid[0][11] = true; // Monday 9,10,11

        WeeklySchedule schedule = WeeklySchedule.fromHourGrid(grid);

        assertTrue(schedule.isActiveAt(LocalDateTime.parse("2026-08-17T09:00:00"))); // Monday
        assertTrue(schedule.isActiveAt(LocalDateTime.parse("2026-08-17T11:30:00")));
        assertFalse(schedule.isActiveAt(LocalDateTime.parse("2026-08-17T08:59:59")));
        assertFalse(schedule.isActiveAt(LocalDateTime.parse("2026-08-17T12:00:00")));
        assertFalse(schedule.isActiveAt(LocalDateTime.parse("2026-08-18T09:00:00"))); // Tuesday
    }

    @Test
    void splitRunsProduceSeparateRanges() {
        boolean[][] grid = new boolean[7][24];
        grid[1][2] = true;
        grid[1][3] = true;
        grid[1][5] = true; // gap at 4

        WeeklySchedule schedule = WeeklySchedule.fromHourGrid(grid);

        assertTrue(schedule.isActiveAt(LocalDateTime.parse("2026-08-18T03:59:59"))); // Tue hour 3
        assertFalse(schedule.isActiveAt(LocalDateTime.parse("2026-08-18T04:00:00"))); // gap
        assertTrue(schedule.isActiveAt(LocalDateTime.parse("2026-08-18T05:10:00"))); // hour 5
    }

    @Test
    void allDayGridIsActiveAroundTheClock() {
        boolean[][] grid = new boolean[7][24];
        for (int d = 0; d < 7; d++) {
            for (int h = 0; h < 24; h++) {
                grid[d][h] = true;
            }
        }
        WeeklySchedule schedule = WeeklySchedule.fromHourGrid(grid);
        for (int hour = 0; hour < 24; hour++) {
            assertTrue(schedule.isActiveAt(LocalDateTime.parse("2026-08-19T"
                    + String.format("%02d", hour) + ":15:00")));
        }
    }

    @Test
    void crossMidnightRangeBelongsToTheDayOnWhichItStarts() {
        WeeklySchedule schedule = new WeeklySchedule()
                .addTimeRange(DayOfWeek.MONDAY, new TimeRange("22:00", "06:00"));

        assertFalse(schedule.isActiveAt(LocalDateTime.parse("2026-08-17T05:00:00")),
                "early Monday must not borrow Monday night's range");
        assertTrue(schedule.isActiveAt(LocalDateTime.parse("2026-08-17T23:00:00")));
        assertTrue(schedule.isActiveAt(LocalDateTime.parse("2026-08-18T05:00:00")),
                "early Tuesday belongs to Monday's cross-midnight range");
        assertFalse(schedule.isActiveAt(LocalDateTime.parse("2026-08-18T06:00:01")));
    }

    @Test
    void neverAndEmptySchedulesAreRestrictions() {
        assertTrue(ScheduleSettings.neverActive().hasRestrictions());
        assertTrue(new ScheduleSettings(new WeeklySchedule()).hasRestrictions());
        assertFalse(ScheduleSettings.alwaysActive().hasRestrictions());
    }
}
