package org.manager.schedule;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadScheduler;

/**
 * High-level utility class for managing download scheduling. This class
 * provides convenient methods for setting up and managing schedules for
 * downloads and integrates with the DownloadScheduler service.
 */
public class ScheduleManager {

    private static final Logger LOGGER = Logger.getLogger(ScheduleManager.class.getName());

    private final DownloadManager downloadManager;
    private final DownloadScheduler scheduler;
    private final Map<String, String> schedulePresets;

    /**
     * Creates a new ScheduleManager.
     *
     * @param downloadManager The download manager
     */
    public ScheduleManager(DownloadManager downloadManager) {
        this.downloadManager = Objects.requireNonNull(downloadManager, "Download manager cannot be null");
        this.scheduler = new DownloadScheduler(downloadManager);
        this.schedulePresets = new HashMap<>();
        initializePresets();
    }

    /**
     * Initializes built-in schedule presets.
     */
    private void initializePresets() {
        schedulePresets.put("always", "Always Active - No restrictions");
        schedulePresets.put("never", "Never Active - Downloads disabled");
        schedulePresets.put("business", "Business Hours - 9 AM to 5 PM, weekdays only");
        schedulePresets.put("night", "Night Hours - 10 PM to 6 AM, all days");
        schedulePresets.put("weekend", "Weekends Only - Saturday and Sunday all day");
        schedulePresets.put("weekday", "Weekdays Only - Monday to Friday all day");
    }

    /**
     * Starts the schedule manager and underlying scheduler.
     *
     * @return A future that completes when the manager is started
     */
    public CompletableFuture<Void> start() {
        LOGGER.info("Starting schedule manager");
        return scheduler.start();
    }

    /**
     * Stops the schedule manager and underlying scheduler.
     *
     * @return A future that completes when the manager is stopped
     */
    public CompletableFuture<Void> stop() {
        LOGGER.info("Stopping schedule manager");
        return scheduler.stop();
    }

    /**
     * Shuts down the schedule manager and releases resources.
     *
     * @return A future that completes when shutdown is complete
     */
    public CompletableFuture<Void> shutdown() {
        LOGGER.info("Shutting down schedule manager");
        return scheduler.shutdown();
    }

    /**
     * Gets the underlying scheduler for advanced operations.
     *
     * @return The download scheduler
     */
    public DownloadScheduler getScheduler() {
        return scheduler;
    }

    /**
     * Sets a simple time range schedule for a download.
     *
     * @param downloadId The download ID
     * @param startTime  Start time (e.g., "09:00")
     * @param endTime    End time (e.g., "17:00")
     * @param days       Days of the week to apply the schedule
     * @return This manager for method chaining
     */
    public ScheduleManager setSimpleSchedule(String downloadId, String startTime, String endTime, DayOfWeek... days) {
        Objects.requireNonNull(downloadId, "Download ID cannot be null");
        Objects.requireNonNull(startTime, "Start time cannot be null");
        Objects.requireNonNull(endTime, "End time cannot be null");

        try {
            TimeRange timeRange = new TimeRange(startTime, endTime);
            WeeklySchedule weeklySchedule = new WeeklySchedule();

            if (days.length == 0) {
                // If no days specified, apply to all days
                weeklySchedule.addTimeRangeAllDays(timeRange);
            } else {
                // Apply to specified days
                weeklySchedule.addTimeRange(Arrays.asList(days), timeRange);
            }

            ScheduleSettings settings = new ScheduleSettings(weeklySchedule);
            scheduler.setDownloadSchedule(downloadId, settings);

            LOGGER.info("Set simple schedule for download " + downloadId + ": "
                    + startTime + "-" + endTime + " on " + Arrays.toString(days));

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to set simple schedule for download " + downloadId, e);
            throw new IllegalArgumentException("Invalid schedule parameters", e);
        }

        return this;
    }

    /**
     * Sets a weekday/weekend schedule for a download.
     *
     * @param downloadId   The download ID
     * @param weekdayStart Weekday start time (e.g., "09:00")
     * @param weekdayEnd   Weekday end time (e.g., "17:00")
     * @param weekendStart Weekend start time (e.g., "10:00")
     * @param weekendEnd   Weekend end time (e.g., "22:00")
     * @return This manager for method chaining
     */
    public ScheduleManager setWeekdayWeekendSchedule(String downloadId,
            String weekdayStart, String weekdayEnd, String weekendStart, String weekendEnd) {
        Objects.requireNonNull(downloadId, "Download ID cannot be null");

        try {
            List<TimeRange> weekdayRanges = new ArrayList<>();
            List<TimeRange> weekendRanges = new ArrayList<>();

            if (weekdayStart != null && weekdayEnd != null) {
                weekdayRanges.add(new TimeRange(weekdayStart, weekdayEnd));
            }

            if (weekendStart != null && weekendEnd != null) {
                weekendRanges.add(new TimeRange(weekendStart, weekendEnd));
            }

            WeeklySchedule weeklySchedule = new WeeklySchedule(weekdayRanges, weekendRanges);
            ScheduleSettings settings = new ScheduleSettings(weeklySchedule);
            scheduler.setDownloadSchedule(downloadId, settings);

            LOGGER.info("Set weekday/weekend schedule for download " + downloadId);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to set weekday/weekend schedule for download " + downloadId, e);
            throw new IllegalArgumentException("Invalid schedule parameters", e);
        }

        return this;
    }

    /**
     * Sets a preset schedule for a download.
     *
     * @param downloadId The download ID
     * @param preset     The preset name ("always", "never", "business", "night",
     *                   "weekend", "weekday")
     * @return This manager for method chaining
     */
    public ScheduleManager setPresetSchedule(String downloadId, String preset) {
        Objects.requireNonNull(downloadId, "Download ID cannot be null");
        Objects.requireNonNull(preset, "Preset cannot be null");

        ScheduleSettings settings = switch (preset.toLowerCase()) {
            case "always" ->
                ScheduleSettings.alwaysActive();
            case "never" ->
                ScheduleSettings.neverActive();
            case "business" ->
                ScheduleSettings.businessHours();
            case "night" ->
                ScheduleSettings.nightHours();
            case "weekend" ->
                createWeekendOnlySchedule();
            case "weekday" ->
                createWeekdayOnlySchedule();
            default ->
                throw new IllegalArgumentException("Unknown preset: " + preset
                        + ". Available presets: " + String.join(", ", schedulePresets.keySet()));
        };

        scheduler.setDownloadSchedule(downloadId, settings);
        LOGGER.info("Set preset schedule '" + preset + "' for download " + downloadId);

        return this;
    }

    /**
     * Sets the global schedule using a preset.
     *
     * @param preset The preset name
     * @return This manager for method chaining
     */
    public ScheduleManager setGlobalPresetSchedule(String preset) {
        Objects.requireNonNull(preset, "Preset cannot be null");

        ScheduleSettings settings = switch (preset.toLowerCase()) {
            case "always" ->
                ScheduleSettings.alwaysActive();
            case "never" ->
                ScheduleSettings.neverActive();
            case "business" ->
                ScheduleSettings.businessHours();
            case "night" ->
                ScheduleSettings.nightHours();
            case "weekend" ->
                createWeekendOnlySchedule();
            case "weekday" ->
                createWeekdayOnlySchedule();
            default ->
                throw new IllegalArgumentException("Unknown preset: " + preset
                        + ". Available presets: " + String.join(", ", schedulePresets.keySet()));
        };

        scheduler.setGlobalSchedule(settings);
        LOGGER.info("Set global preset schedule: " + preset);

        return this;
    }

    /**
     * Removes the schedule for a download, falling back to global schedule.
     *
     * @param downloadId The download ID
     * @return This manager for method chaining
     */
    public ScheduleManager removeDownloadSchedule(String downloadId) {
        Objects.requireNonNull(downloadId, "Download ID cannot be null");

        scheduler.removeDownloadSchedule(downloadId);
        LOGGER.info("Removed schedule for download " + downloadId);

        return this;
    }

    /**
     * Gets available schedule presets.
     *
     * @return A map of preset names to descriptions
     */
    public Map<String, String> getAvailablePresets() {
        return new HashMap<>(schedulePresets);
    }

    /**
     * Checks if a download should be active based on its schedule.
     *
     * @param downloadId The download ID
     * @return true if the download should be active, false otherwise
     */
    public boolean shouldDownloadBeActive(String downloadId) {
        return scheduler.shouldDownloadBeActive(downloadId);
    }

    /**
     * Gets schedule information for a download.
     *
     * @param downloadId The download ID
     * @return Schedule information string
     */
    public String getScheduleInfo(String downloadId) {
        Objects.requireNonNull(downloadId, "Download ID cannot be null");

        ScheduleSettings schedule = scheduler.getEffectiveSchedule(downloadId);
        if (schedule == null) {
            return "No schedule configured";
        }

        if (!schedule.isEnabled()) {
            return "Schedule disabled";
        }

        if (!schedule.hasRestrictions()) {
            return "Always active (no restrictions)";
        }

        StringBuilder info = new StringBuilder();
        WeeklySchedule weeklySchedule = schedule.getWeeklySchedule();

        info.append("Schedule: ");
        boolean hasAnyRanges = false;

        for (DayOfWeek day : DayOfWeek.values()) {
            List<TimeRange> ranges = weeklySchedule.getTimeRanges(day);
            if (!ranges.isEmpty()) {
                if (hasAnyRanges) {
                    info.append(", ");
                }
                info.append(day.name().substring(0, 3)).append(": ");
                for (int i = 0; i < ranges.size(); i++) {
                    if (i > 0) {
                        info.append(", ");
                    }
                    info.append(ranges.get(i).toString());
                }
                hasAnyRanges = true;
            }
        }

        if (!hasAnyRanges) {
            info.append("No active time ranges");
        }

        info.append(" | Policy: ").append(schedule.getPolicy());

        return info.toString();
    }

    /**
     * Gets the current status for all downloads with schedules.
     *
     * @return A map of download IDs to their schedule status
     */
    public Map<String, String> getScheduleStatus() {
        Map<String, String> status = new HashMap<>();

        try {
            List<Download> downloads = downloadManager.getAllDownloads();
            LocalDateTime now = LocalDateTime.now();

            for (Download download : downloads) {
                String downloadId = download.getId();
                boolean shouldBeActive = scheduler.shouldDownloadBeActive(downloadId, now);
                String scheduleInfo = getScheduleInfo(downloadId);

                status.put(downloadId, String.format("%s | Should be active: %s",
                        scheduleInfo, shouldBeActive ? "Yes" : "No"));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error getting schedule status", e);
        }

        return status;
    }

    /**
     * Sets the check interval for the scheduler.
     *
     * @param seconds The interval in seconds (minimum 5)
     * @return This manager for method chaining
     */
    public ScheduleManager setCheckInterval(int seconds) {
        scheduler.setCheckInterval(seconds);
        return this;
    }

    /**
     * Manually triggers a schedule check for all downloads.
     *
     * @return This manager for method chaining
     */
    public ScheduleManager checkSchedulesNow() {
        scheduler.checkSchedulesNow();
        return this;
    }

    /**
     * Adds a listener for schedule events.
     *
     * @param listener The listener to add
     * @return This manager for method chaining
     */
    public ScheduleManager addListener(DownloadScheduler.DownloadSchedulerListener listener) {
        scheduler.addListener(listener);
        return this;
    }

    /**
     * Removes a schedule event listener.
     *
     * @param listener The listener to remove
     * @return This manager for method chaining
     */
    public ScheduleManager removeListener(DownloadScheduler.DownloadSchedulerListener listener) {
        scheduler.removeListener(listener);
        return this;
    }

    /**
     * Creates a weekend-only schedule.
     */
    private ScheduleSettings createWeekendOnlySchedule() {
        WeeklySchedule schedule = new WeeklySchedule();
        TimeRange allDay = TimeRange.allDay();

        schedule.addTimeRange(DayOfWeek.SATURDAY, allDay);
        schedule.addTimeRange(DayOfWeek.SUNDAY, allDay);

        return new ScheduleSettings(schedule);
    }

    /**
     * Creates a weekday-only schedule.
     */
    private ScheduleSettings createWeekdayOnlySchedule() {
        WeeklySchedule schedule = new WeeklySchedule();
        TimeRange allDay = TimeRange.allDay();

        List<DayOfWeek> weekdays = Arrays.asList(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

        schedule.addTimeRange(weekdays, allDay);

        return new ScheduleSettings(schedule);
    }

    /**
     * Checks if the schedule manager is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return scheduler.isRunning();
    }
}
