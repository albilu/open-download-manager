package org.manager.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadScheduler;

@DisplayName("ScheduleManager presets, schedules and reporting")
class ScheduleManagerTest {

    private ScheduleManager newManager() {
        DownloadManager manager = mock(DownloadManager.class);
        when(manager.getAllDownloads()).thenReturn(List.of());
        return new ScheduleManager(manager);
    }

    @Test
    @DisplayName("all six built-in presets are advertised")
    void presetsAreAdvertised() {
        Map<String, String> presets = newManager().getAvailablePresets();
        assertEquals(java.util.Set.of("always", "never", "business", "night", "weekend", "weekday"),
                presets.keySet());
        assertTrue(presets.get("business").contains("9 AM"));
    }

    @Test
    @DisplayName("preset 'never' blocks a download while 'always' admits it")
    void neverAndAlwaysPresetsControlActivity() {
        ScheduleManager schedules = newManager();
        schedules.setPresetSchedule("dl-1", "never");
        schedules.setPresetSchedule("dl-2", "ALWAYS");

        assertFalse(schedules.shouldDownloadBeActive("dl-1"));
        assertTrue(schedules.shouldDownloadBeActive("dl-2"));
        assertEquals("Schedule disabled", schedules.getScheduleInfo("dl-1"));
        assertEquals("Always active (no restrictions)", schedules.getScheduleInfo("dl-2"));
    }

    @Test
    @DisplayName("unknown presets are rejected with the list of valid names")
    void unknownPresetRejected() {
        ScheduleManager schedules = newManager();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> schedules.setPresetSchedule("dl-1", "weekdays_only"));
        assertTrue(ex.getMessage().contains("weekdays_only"));
        assertTrue(ex.getMessage().contains("always"));
    }

    @Test
    @DisplayName("business hours preset is active only on weekdays within 09:00-17:00")
    void businessHoursPresetBoundaries() {
        ScheduleManager schedules = newManager();
        schedules.setPresetSchedule("dl-1", "business");

        DownloadScheduler scheduler = schedules.getScheduler();
        LocalDateTime wednesdayNoon = LocalDateTime.of(2026, 9, 2, 12, 0);
        LocalDateTime wednesdayEvening = LocalDateTime.of(2026, 9, 2, 20, 0);
        LocalDateTime saturdayNoon = LocalDateTime.of(2026, 9, 5, 12, 0);

        assertTrue(scheduler.shouldDownloadBeActive("dl-1", wednesdayNoon), "Wednesday noon is business time");
        assertFalse(scheduler.shouldDownloadBeActive("dl-1", wednesdayEvening), "after 17:00 is off");
        assertFalse(scheduler.shouldDownloadBeActive("dl-1", saturdayNoon), "Saturday is not a business day");

        String info = schedules.getScheduleInfo("dl-1");
        assertTrue(info.contains("MON"));
        assertTrue(info.contains("FRI"));
        assertFalse(info.contains("SAT"));
        assertTrue(info.contains("09:00-17:00"));
        assertTrue(info.contains("Policy:"));
    }

    @Test
    @DisplayName("night hours preset spans midnight on every day")
    void nightHoursPresetSpansMidnight() {
        ScheduleManager schedules = newManager();
        schedules.setPresetSchedule("dl-1", "night");

        DownloadScheduler scheduler = schedules.getScheduler();
        assertTrue(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 2, 23, 30)));
        assertTrue(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 3, 2, 0)));
        assertFalse(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 3, 12, 0)));
    }

    @Test
    @DisplayName("weekend preset runs all day on Saturday and Sunday only")
    void weekendPresetCoversOnlyWeekend() {
        ScheduleManager schedules = newManager();
        schedules.setPresetSchedule("dl-1", "weekend");

        DownloadScheduler scheduler = schedules.getScheduler();
        LocalDateTime sundayDawn = LocalDateTime.of(2026, 9, 6, 0, 30);
        LocalDateTime mondayMorning = LocalDateTime.of(2026, 9, 7, 8, 0);
        assertTrue(scheduler.shouldDownloadBeActive("dl-1", sundayDawn));
        assertFalse(scheduler.shouldDownloadBeActive("dl-1", mondayMorning));
    }

    @Test
    @DisplayName("weekday preset runs Monday through Friday")
    void weekdayPresetCoversOnlyWeekdays() {
        ScheduleManager schedules = newManager();
        schedules.setPresetSchedule("dl-1", "weekday");

        DownloadScheduler scheduler = schedules.getScheduler();
        assertTrue(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 4, 10, 0)));
        assertFalse(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 5, 10, 0)));
    }

    @Test
    @DisplayName("global preset applies to downloads without their own schedule")
    void globalPresetAppliesAsFallback() {
        ScheduleManager schedules = newManager();
        schedules.setGlobalPresetSchedule("never");
        assertFalse(schedules.shouldDownloadBeActive("unconfigured-download"));
    }

    @Test
    @DisplayName("per-download and global schedules combine conjunctively by default")
    void perDownloadAndGlobalSchedulesCombine() {
        ScheduleManager schedules = newManager();
        schedules.setGlobalPresetSchedule("never");
        schedules.setPresetSchedule("dl-1", "always");

        // dl-1 'always' respects the global 'never' by default: the global
        // gate must win so downloads cannot out-schedule a global stop
        assertFalse(schedules.shouldDownloadBeActive("dl-1"));
        assertFalse(schedules.shouldDownloadBeActive("dl-2"));

        // opting out of the global gate (per-download flag) restores 'always'
        ScheduleSettings independent = ScheduleSettings.alwaysActive().setRespectGlobalSchedule(false);
        schedules.getScheduler().setDownloadSchedule("dl-1", independent);
        assertTrue(schedules.shouldDownloadBeActive("dl-1"),
                "respectGlobalSchedule=false must bypass the global gate");

        // global 'always' + download 'never': the stronger restriction wins
        schedules.setGlobalPresetSchedule("always");
        schedules.setPresetSchedule("dl-3", "never");
        assertFalse(schedules.shouldDownloadBeActive("dl-3"));

        schedules.removeDownloadSchedule("dl-1");
        assertTrue(schedules.shouldDownloadBeActive("dl-1"),
                "after removal the current global 'always' applies again");
    }

    @Test
    @DisplayName("simple schedule applies to all days when none are specified")
    void simpleScheduleWithoutDaysAppliesDaily() {
        ScheduleManager schedules = newManager();
        schedules.setSimpleSchedule("dl-1", "01:00", "05:00");

        DownloadScheduler scheduler = schedules.getScheduler();
        for (DayOfWeek day : DayOfWeek.values()) {
            LocalDateTime inside = LocalDateTime.of(2026, 9, 7, 3, 0).with(java.time.temporal.TemporalAdjusters.nextOrSame(day));
            assertTrue(scheduler.shouldDownloadBeActive("dl-1", inside), day + " 03:00 must be inside the window");
        }
        assertFalse(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 7, 12, 0)));
    }

    @Test
    @DisplayName("simple schedule with explicit days restricts to those days")
    void simpleScheduleWithDays() {
        ScheduleManager schedules = newManager();
        schedules.setSimpleSchedule("dl-1", "10:00", "12:00", DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);

        DownloadScheduler scheduler = schedules.getScheduler();
        assertTrue(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 2, 11, 0)));
        assertFalse(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 3, 11, 0)),
                "Thursday must be excluded");
    }

    @Test
    @DisplayName("invalid simple schedule parameters are rejected")
    void invalidSimpleScheduleRejected() {
        ScheduleManager schedules = newManager();
        assertThrows(IllegalArgumentException.class,
                () -> schedules.setSimpleSchedule("dl-1", "not-a-time", "05:00"));
        assertThrows(NullPointerException.class,
                () -> schedules.setSimpleSchedule(null, "01:00", "05:00"));
    }

    @Test
    @DisplayName("weekday/weekend schedule supports independent windows")
    void weekdayWeekendSchedule() {
        ScheduleManager schedules = newManager();
        schedules.setWeekdayWeekendSchedule("dl-1", "09:00", "17:00", "20:00", "23:00");

        DownloadScheduler scheduler = schedules.getScheduler();
        assertTrue(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 2, 10, 0)));
        assertFalse(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 2, 21, 0)),
                "Wednesday 21:00 is outside the weekday window");
        assertTrue(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 5, 21, 0)),
                "Saturday 21:00 is inside the weekend window");
        assertFalse(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 5, 10, 0)),
                "Saturday 10:00 is outside the weekend window");
    }

    @Test
    @DisplayName("weekday/weekend windows may be partially omitted")
    void weekdayWeekendScheduleWithNulls() {
        ScheduleManager schedules = newManager();
        schedules.setWeekdayWeekendSchedule("dl-1", "09:00", "17:00", null, null);

        DownloadScheduler scheduler = schedules.getScheduler();
        assertTrue(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 2, 10, 0)));
        assertFalse(scheduler.shouldDownloadBeActive("dl-1", LocalDateTime.of(2026, 9, 5, 12, 0)),
                "weekend with no window stays inactive all day");
    }

    @Test
    @DisplayName("schedule info reports the effective schedule, defaulting to global always")
    void scheduleInfoReportsStates() {
        ScheduleManager schedules = newManager();
        assertEquals("Always active (no restrictions)",
                schedules.getScheduleInfo("dl-unknown"),
                "with no global and no per-download schedule, the default is permissive");

        schedules.setGlobalPresetSchedule("never");
        assertEquals("Schedule disabled", schedules.getScheduleInfo("dl-unknown"),
                "the global schedule is the effective one for unconfigured downloads");
        assertThrows(NullPointerException.class, () -> schedules.getScheduleInfo(null));
    }

    @Test
    @DisplayName("schedule status covers every download the manager knows")
    void scheduleStatusCoversDownloads() {
        DownloadManager manager = mock(DownloadManager.class);
        Download download = new Download(URI.create("https://example.test/a.zip"));
        when(manager.getAllDownloads()).thenReturn(List.of(download));

        ScheduleManager schedules = new ScheduleManager(manager);
        schedules.setPresetSchedule(download.getId(), "never");

        Map<String, String> status = schedules.getScheduleStatus();
        assertEquals(1, status.size());
        String line = status.get(download.getId());
        assertTrue(line.contains("Schedule disabled"));
        assertTrue(line.contains("Should be active: No"));
    }

    @Test
    @DisplayName("start/stop lifecycle reflects the running state")
    void lifecycleStartStop() throws Exception {
        ScheduleManager schedules = newManager();
        schedules.setCheckInterval(5);
        assertFalse(schedules.isRunning());

        schedules.start().get(10, java.util.concurrent.TimeUnit.SECONDS);
        try {
            assertTrue(schedules.isRunning());
        } finally {
            schedules.stop().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertFalse(schedules.isRunning());
    }

    @Test
    @DisplayName("manual schedule checks and listener (de)registration are safe no-ops")
    void manualCheckAndListeners() throws Exception {
        ScheduleManager schedules = newManager();
        DownloadScheduler.DownloadSchedulerListener listener = new DownloadScheduler.DownloadSchedulerListener() { };
        schedules.addListener(listener).removeListener(listener);
        schedules.checkSchedulesNow().setCheckInterval(6);

        schedules.shutdown().get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("manager rejects a null download manager")
    void nullManagerRejected() {
        assertThrows(NullPointerException.class, () -> new ScheduleManager(null));
    }
}
