# Download Scheduling System

The Download Scheduling System provides comprehensive time-based control over when downloads should be active. This allows users to specify time ranges during the week when downloads can run, helping to manage bandwidth usage and system resources.

## Overview

The scheduling system consists of several key components:

- **TimeRange**: Represents a time period within a day (e.g., 9:00 AM to 5:00 PM)
- **WeeklySchedule**: Manages time ranges for each day of the week
- **ScheduleSettings**: Configuration for download scheduling behavior
- **DownloadScheduler**: Service that monitors and enforces schedules
- **ScheduleManager**: High-level utility for easy schedule management

## Key Features

- **Flexible Time Ranges**: Support for any time range, including overnight periods that span midnight
- **Weekly Scheduling**: Different schedules for each day of the week
- **Global and Per-Download Schedules**: Set system-wide defaults with per-download overrides
- **Multiple Schedule Policies**: Control how strictly schedules are enforced
- **Preset Schedules**: Built-in common schedules (business hours, night hours, etc.)
- **Event Notifications**: Listen for schedule-triggered pause/resume events
- **Automatic Management**: Downloads are automatically paused/resumed based on schedules

## Basic Usage

### Setting Up the Schedule Manager

```java
DownloadManager downloadManager = // ... your download manager
ScheduleManager scheduleManager = new ScheduleManager(downloadManager);

// Start the scheduler
scheduleManager.start().get();
```

### Using Preset Schedules

```java
// Set business hours schedule (9 AM - 5 PM, weekdays only)
scheduleManager.setPresetSchedule(downloadId, "business");

// Set night hours schedule (10 PM - 6 AM, all days)
scheduleManager.setPresetSchedule(downloadId, "night");

// Available presets: "always", "never", "business", "night", "weekend", "weekday"
```

### Creating Custom Schedules

```java
// Simple schedule: Monday to Friday, 9:00 AM to 5:00 PM
scheduleManager.setSimpleSchedule(downloadId, "09:00", "17:00", 
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, 
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

// Different schedules for weekdays vs weekends
scheduleManager.setWeekdayWeekendSchedule(downloadId,
    "09:00", "17:00",  // Weekday hours
    "10:00", "22:00"   // Weekend hours
);
```

### Advanced Custom Schedules

```java
// Create a complex weekly schedule
WeeklySchedule weeklySchedule = new WeeklySchedule();

// Multiple time ranges for weekdays
weeklySchedule.addTimeRange(DayOfWeek.MONDAY, new TimeRange("09:00", "12:00"));
weeklySchedule.addTimeRange(DayOfWeek.MONDAY, new TimeRange("14:00", "18:00"));

// Weekend scheduling
weeklySchedule.addTimeRange(DayOfWeek.SATURDAY, TimeRange.allDay());
weeklySchedule.addTimeRange(DayOfWeek.SUNDAY, new TimeRange("08:00", "12:00"));

// Configure schedule settings
ScheduleSettings settings = new ScheduleSettings(weeklySchedule);
settings.setPolicy(ScheduleSettings.SchedulePolicy.GRACEFUL);
settings.setPauseOnScheduleEnd(true);
settings.setResumeOnScheduleStart(true);

// Apply to download
scheduleManager.getScheduler().setDownloadSchedule(downloadId, settings);
```

## Time Range Syntax

Time ranges support various formats:

```java
// 24-hour format with minutes
new TimeRange("09:00", "17:00")

// With seconds
new TimeRange("09:00:00", "17:30:15")

// Single digit hours
new TimeRange("9:00", "17:00")

// Parse from string
TimeRange range = TimeRange.parse("09:00-17:00");

// Overnight ranges (spans midnight)
new TimeRange("22:00", "06:00")  // 10 PM to 6 AM next day
```

## Schedule Policies

The system supports three enforcement policies:

### STRICT Policy
- Immediately pauses downloads when outside schedule
- Immediately resumes downloads when schedule becomes active
- Most restrictive, ensures downloads only run during allowed times

### GRACEFUL Policy (Default)
- Allows currently downloading files to continue
- Prevents new downloads from starting when outside schedule
- Balances restriction with user experience

### NEW_ONLY Policy
- Only prevents new downloads from starting
- Never affects currently running downloads
- Least restrictive, for advisory scheduling

```java
ScheduleSettings settings = new ScheduleSettings(weeklySchedule);
settings.setPolicy(ScheduleSettings.SchedulePolicy.GRACEFUL);
```

## Global Scheduling

Set a default schedule that applies to all downloads without specific schedules:

```java
// Set global night hours schedule
scheduleManager.setGlobalPresetSchedule("night");

// Downloads can respect both their own schedule AND the global schedule
ScheduleSettings downloadSettings = ScheduleSettings.businessHours();
downloadSettings.setRespectGlobalSchedule(true);  // Must satisfy both schedules
```

## Monitoring and Events

### Schedule Event Listener

```java
scheduleManager.addListener(new DownloadScheduler.DownloadSchedulerListener() {
    @Override
    public void onDownloadPausedBySchedule(String downloadId, ScheduleSettings schedule) {
        System.out.println("Download " + downloadId + " paused by schedule");
    }

    @Override
    public void onDownloadResumedBySchedule(String downloadId, ScheduleSettings schedule) {
        System.out.println("Download " + downloadId + " resumed by schedule");
    }

    @Override
    public void onGlobalScheduleChanged(ScheduleSettings oldSchedule, ScheduleSettings newSchedule) {
        System.out.println("Global schedule changed");
    }
});
```

### Checking Schedule Status

```java
// Check if a download should be active now
boolean shouldBeActive = scheduleManager.shouldDownloadBeActive(downloadId);

// Get human-readable schedule information
String scheduleInfo = scheduleManager.getScheduleInfo(downloadId);

// Get status for all downloads
Map<String, String> allStatus = scheduleManager.getScheduleStatus();
```

## Integration with Downloads

Downloads can have schedules attached directly:

```java
Download download = downloadManager.createDownload(uri, destination);

// Set schedule settings on the download object
ScheduleSettings settings = ScheduleSettings.businessHours();
download.setScheduleSettings(settings);

// Check if download should be active
boolean shouldBeActive = download.shouldBeActiveNow();
```

## Configuration Options

### Check Interval

Controls how often the scheduler checks and enforces schedules:

```java
// Check every 30 seconds (default)
scheduleManager.setCheckInterval(30);

// Minimum is 5 seconds
scheduleManager.setCheckInterval(5);
```

### Pause/Resume Behavior

```java
ScheduleSettings settings = new ScheduleSettings();

// Automatically pause when schedule ends
settings.setPauseOnScheduleEnd(true);

// Automatically resume when schedule starts
settings.setResumeOnScheduleStart(true);
```

## Examples

### Business Hours Only
```java
// Downloads only during business hours (9 AM - 5 PM, weekdays)
scheduleManager.setPresetSchedule(downloadId, "business");
```

### Night Downloads
```java
// Large files during night hours to avoid peak usage
scheduleManager.setPresetSchedule(downloadId, "night");
```

### Weekend Projects
```java
// Personal downloads only on weekends
scheduleManager.setPresetSchedule(downloadId, "weekend");
```

### Custom Work Schedule
```java
// Custom schedule: 8:30 AM - 12:00 PM and 1:00 PM - 6:00 PM, Monday-Friday
WeeklySchedule schedule = new WeeklySchedule();
List<DayOfWeek> workdays = Arrays.asList(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
);

schedule.addTimeRange(workdays, new TimeRange("08:30", "12:00"));
schedule.addTimeRange(workdays, new TimeRange("13:00", "18:00"));

ScheduleSettings settings = new ScheduleSettings(schedule);
scheduleManager.getScheduler().setDownloadSchedule(downloadId, settings);
```

### Overnight Downloads
```java
// Downloads from 11 PM to 7 AM (spans midnight)
scheduleManager.setSimpleSchedule(downloadId, "23:00", "07:00");
```

## Best Practices

1. **Use Appropriate Policies**: Choose GRACEFUL for most use cases, STRICT for bandwidth-critical environments
2. **Set Reasonable Check Intervals**: 30-60 seconds is usually sufficient; shorter intervals use more CPU
3. **Consider Global Schedules**: Set sensible defaults and override per-download as needed
4. **Monitor Events**: Use listeners to log or notify about schedule actions
5. **Test Overnight Ranges**: Verify that ranges spanning midnight work as expected
6. **Plan for Edge Cases**: Consider what happens during daylight saving time transitions

## Lifecycle Management

```java
// Start the schedule manager
scheduleManager.start().get();

// Perform operations...

// Stop scheduling (but keep configuration)
scheduleManager.stop().get();

// Restart if needed
scheduleManager.start().get();

// Clean shutdown (releases all resources)
scheduleManager.shutdown().get();
```

## Thread Safety

All scheduling components are thread-safe and can be used concurrently:

- Multiple threads can set/modify schedules simultaneously
- Schedule checks run on a dedicated background thread
- All collections use concurrent implementations where appropriate
- Synchronization is handled internally

## Performance Considerations

- Schedule checks are lightweight and run in background threads
- Time range calculations are optimized for frequent checking
- Memory usage scales linearly with the number of downloads
- CPU usage is minimal with reasonable check intervals (30+ seconds)

## Error Handling

The scheduling system is designed to be resilient:

- Invalid time formats throw `IllegalArgumentException` with clear messages
- Missing downloads are automatically cleaned up from schedule maps
- Scheduler continues operating even if individual download operations fail
- All errors are logged with appropriate levels

## Migration and Compatibility

When upgrading existing installations:

- Downloads without schedules continue to work normally (always active)
- The global schedule defaults to "always active" for backward compatibility
- Existing download configurations are preserved
- No database schema changes are required