# Download Scheduling System - Integration Guide

This guide explains how to integrate the new scheduling functionality into your download manager application.

## Overview

The scheduling system allows users to specify time ranges during the week when downloads should be active. It provides flexible scheduling options, automatic pause/resume functionality, and comprehensive monitoring capabilities.

## Quick Start

### 1. Basic Integration

```java
// Initialize the download manager (existing code)
DownloadManager downloadManager = new DownloadManagerImpl();
downloadManager.initialize().get();

// Create and start the schedule manager
ScheduleManager scheduleManager = new ScheduleManager(downloadManager);
scheduleManager.start().get();

// Set a global schedule (optional)
scheduleManager.setGlobalPresetSchedule("night"); // Downloads only at night

// Create a download with scheduling
Download download = downloadManager.createDownload(
    URI.create("https://example.com/file.zip"),
    Paths.get("/downloads")
);

// Set business hours schedule for this download
scheduleManager.setPresetSchedule(download.getId(), "business");
```

### 2. Application Shutdown

```java
// Proper cleanup when shutting down your application
scheduleManager.shutdown().get();
downloadManager.shutdown().get();
```

## Integration Points

### 1. Download Manager Factory Integration

Update your download manager factory to include scheduling:

```java
public class EnhancedDownloadManagerFactory {
    
    public static DownloadManager createWithScheduling() {
        DownloadManager downloadManager = new DownloadManagerImpl();
        ScheduleManager scheduleManager = new ScheduleManager(downloadManager);
        
        // Store schedule manager for later access
        ((DownloadManagerImpl) downloadManager).setScheduleManager(scheduleManager);
        
        return downloadManager;
    }
}
```

### 2. Configuration Integration

Add scheduling configuration to your settings:

```java
public class ApplicationSettings {
    private boolean schedulingEnabled = true;
    private String defaultSchedulePreset = "always";
    private int scheduleCheckInterval = 30; // seconds
    private ScheduleSettings.SchedulePolicy defaultPolicy = ScheduleSettings.SchedulePolicy.GRACEFUL;
    
    // Getters and setters...
}
```

### 3. UI Integration Points

#### Schedule Configuration UI

```java
public class ScheduleConfigurationPanel {
    
    private ScheduleManager scheduleManager;
    private String currentDownloadId;
    
    public void showScheduleDialog(String downloadId) {
        this.currentDownloadId = downloadId;
        
        // Get current schedule
        ScheduleSettings current = scheduleManager.getScheduler()
            .getDownloadSchedule(downloadId);
        
        // Populate UI with current settings
        if (current != null) {
            populateScheduleUI(current);
        }
        
        // Show dialog
        dialog.setVisible(true);
    }
    
    private void applySchedule() {
        // Gather settings from UI
        if (presetSelected) {
            scheduleManager.setPresetSchedule(currentDownloadId, selectedPreset);
        } else {
            // Custom schedule
            scheduleManager.setSimpleSchedule(currentDownloadId, 
                startTime, endTime, selectedDays);
        }
    }
}
```

#### Download Status Display

```java
public class DownloadStatusPanel {
    
    public void updateDownloadDisplay(Download download) {
        // Existing status update code...
        
        // Add schedule information
        if (download.hasScheduleSettings()) {
            String scheduleInfo = scheduleManager.getScheduleInfo(download.getId());
            boolean shouldBeActive = scheduleManager.shouldDownloadBeActive(download.getId());
            
            scheduleLabel.setText(scheduleInfo);
            scheduleStatusLabel.setText(shouldBeActive ? "Active Period" : "Inactive Period");
            scheduleStatusLabel.setForeground(shouldBeActive ? Color.GREEN : Color.RED);
        } else {
            scheduleLabel.setText("No schedule restrictions");
            scheduleStatusLabel.setText("Always Active");
            scheduleStatusLabel.setForeground(Color.GREEN);
        }
    }
}
```

### 4. Persistence Integration

#### Save/Load Schedules

```java
public class SchedulePersistence {
    
    public void saveSchedules(ScheduleManager scheduleManager, String configPath) {
        try {
            Map<String, Object> config = new HashMap<>();
            
            // Save global schedule
            ScheduleSettings globalSchedule = scheduleManager.getScheduler().getGlobalSchedule();
            config.put("globalSchedule", serializeScheduleSettings(globalSchedule));
            
            // Save individual download schedules
            Map<String, Object> downloadSchedules = new HashMap<>();
            for (Download download : downloadManager.getAllDownloads()) {
                ScheduleSettings schedule = scheduleManager.getScheduler()
                    .getDownloadSchedule(download.getId());
                if (schedule != null) {
                    downloadSchedules.put(download.getId(), serializeScheduleSettings(schedule));
                }
            }
            config.put("downloadSchedules", downloadSchedules);
            
            // Save to file (JSON, XML, or properties)
            saveConfigToFile(config, configPath);
            
        } catch (Exception e) {
            logger.error("Failed to save schedules", e);
        }
    }
    
    public void loadSchedules(ScheduleManager scheduleManager, String configPath) {
        try {
            Map<String, Object> config = loadConfigFromFile(configPath);
            
            // Load global schedule
            if (config.containsKey("globalSchedule")) {
                ScheduleSettings globalSchedule = deserializeScheduleSettings(
                    (Map<String, Object>) config.get("globalSchedule"));
                scheduleManager.getScheduler().setGlobalSchedule(globalSchedule);
            }
            
            // Load individual download schedules
            if (config.containsKey("downloadSchedules")) {
                Map<String, Object> downloadSchedules = 
                    (Map<String, Object>) config.get("downloadSchedules");
                
                for (Map.Entry<String, Object> entry : downloadSchedules.entrySet()) {
                    String downloadId = entry.getKey();
                    ScheduleSettings schedule = deserializeScheduleSettings(
                        (Map<String, Object>) entry.getValue());
                    scheduleManager.getScheduler().setDownloadSchedule(downloadId, schedule);
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to load schedules", e);
        }
    }
}
```

## Event Handling

### 1. Schedule Event Listener

```java
public class ApplicationScheduleListener implements DownloadScheduler.DownloadSchedulerListener {
    
    private final NotificationService notificationService;
    private final UIUpdateService uiUpdateService;
    
    @Override
    public void onDownloadPausedBySchedule(String downloadId, ScheduleSettings schedule) {
        // Show notification
        notificationService.showInfo("Download paused by schedule: " + downloadId);
        
        // Update UI
        uiUpdateService.updateDownloadStatus(downloadId);
        
        // Log event
        logger.info("Download {} paused by schedule policy {}", downloadId, schedule.getPolicy());
    }
    
    @Override
    public void onDownloadResumedBySchedule(String downloadId, ScheduleSettings schedule) {
        // Show notification
        notificationService.showInfo("Download resumed by schedule: " + downloadId);
        
        // Update UI
        uiUpdateService.updateDownloadStatus(downloadId);
        
        // Log event
        logger.info("Download {} resumed by schedule", downloadId);
    }
    
    @Override
    public void onGlobalScheduleChanged(ScheduleSettings oldSchedule, ScheduleSettings newSchedule) {
        // Update UI to reflect new global schedule
        uiUpdateService.updateGlobalScheduleDisplay(newSchedule);
        
        // Notify user
        notificationService.showInfo("Global download schedule updated");
    }
}
```

### 2. Register Event Listener

```java
public class ApplicationInitializer {
    
    public void initializeScheduling() {
        // Create and start schedule manager
        scheduleManager = new ScheduleManager(downloadManager);
        scheduleManager.start().get();
        
        // Register event listener
        ApplicationScheduleListener listener = new ApplicationScheduleListener(
            notificationService, uiUpdateService);
        scheduleManager.addListener(listener);
    }
}
```

## Menu Integration

### 1. Main Menu Items

```java
public void createScheduleMenus() {
    JMenu scheduleMenu = new JMenu("Schedule");
    
    // Global schedule submenu
    JMenu globalScheduleMenu = new JMenu("Global Schedule");
    for (String preset : scheduleManager.getAvailablePresets().keySet()) {
        JMenuItem item = new JMenuItem(preset);
        item.addActionListener(e -> scheduleManager.setGlobalPresetSchedule(preset));
        globalScheduleMenu.add(item);
    }
    scheduleMenu.add(globalScheduleMenu);
    
    // Schedule management
    JMenuItem manageSchedulesItem = new JMenuItem("Manage Schedules...");
    manageSchedulesItem.addActionListener(e -> showScheduleManagementDialog());
    scheduleMenu.add(manageSchedulesItem);
    
    // Schedule status
    JMenuItem scheduleStatusItem = new JMenuItem("Schedule Status...");
    scheduleStatusItem.addActionListener(e -> showScheduleStatusDialog());
    scheduleMenu.add(scheduleStatusItem);
    
    menuBar.add(scheduleMenu);
}
```

### 2. Context Menu Integration

```java
public void createDownloadContextMenu(Download download) {
    JPopupMenu contextMenu = new JPopupMenu();
    
    // Existing menu items...
    
    // Schedule submenu
    JMenu scheduleSubmenu = new JMenu("Schedule");
    
    // Quick presets
    for (String preset : scheduleManager.getAvailablePresets().keySet()) {
        JMenuItem item = new JMenuItem(preset);
        item.addActionListener(e -> 
            scheduleManager.setPresetSchedule(download.getId(), preset));
        scheduleSubmenu.add(item);
    }
    
    scheduleSubmenu.addSeparator();
    
    // Custom schedule
    JMenuItem customItem = new JMenuItem("Custom Schedule...");
    customItem.addActionListener(e -> showCustomScheduleDialog(download.getId()));
    scheduleSubmenu.add(customItem);
    
    // Remove schedule
    JMenuItem removeItem = new JMenuItem("Remove Schedule");
    removeItem.addActionListener(e -> scheduleManager.removeDownloadSchedule(download.getId()));
    scheduleSubmenu.add(removeItem);
    
    contextMenu.add(scheduleSubmenu);
}
```

## Configuration Examples

### 1. Business Environment

```java
public void setupBusinessEnvironment() {
    // Only allow downloads during business hours by default
    scheduleManager.setGlobalPresetSchedule("business");
    
    // Use strict policy to enforce bandwidth limits
    ScheduleSettings strictBusiness = ScheduleSettings.businessHours();
    strictBusiness.setPolicy(ScheduleSettings.SchedulePolicy.STRICT);
    scheduleManager.getScheduler().setGlobalSchedule(strictBusiness);
    
    // Check more frequently in business environment
    scheduleManager.setCheckInterval(15); // 15 seconds
}
```

### 2. Home Environment

```java
public void setupHomeEnvironment() {
    // Allow downloads during night hours to avoid peak usage
    scheduleManager.setGlobalPresetSchedule("night");
    
    // Use graceful policy for better user experience
    ScheduleSettings gracefulNight = ScheduleSettings.nightHours();
    gracefulNight.setPolicy(ScheduleSettings.SchedulePolicy.GRACEFUL);
    scheduleManager.getScheduler().setGlobalSchedule(gracefulNight);
    
    // Less frequent checking for home use
    scheduleManager.setCheckInterval(60); // 1 minute
}
```

### 3. Server Environment

```java
public void setupServerEnvironment() {
    // Custom server schedule: avoid peak hours (9 AM - 6 PM)
    WeeklySchedule serverSchedule = new WeeklySchedule();
    
    // Early morning hours (6 AM - 9 AM)
    serverSchedule.addTimeRangeAllDays(new TimeRange("06:00", "09:00"));
    
    // Evening hours (6 PM - 11 PM)
    serverSchedule.addTimeRangeAllDays(new TimeRange("18:00", "23:00"));
    
    // Overnight (11 PM - 6 AM)
    serverSchedule.addTimeRangeAllDays(new TimeRange("23:00", "06:00"));
    
    ScheduleSettings serverSettings = new ScheduleSettings(serverSchedule);
    serverSettings.setPolicy(ScheduleSettings.SchedulePolicy.STRICT);
    
    scheduleManager.getScheduler().setGlobalSchedule(serverSettings);
}
```

## Best Practices

### 1. Error Handling

```java
public void safeScheduleOperation(Runnable operation) {
    try {
        operation.run();
    } catch (IllegalArgumentException e) {
        // Handle invalid schedule parameters
        showErrorMessage("Invalid schedule configuration: " + e.getMessage());
    } catch (Exception e) {
        // Handle unexpected errors
        logger.error("Schedule operation failed", e);
        showErrorMessage("Schedule operation failed. Please try again.");
    }
}
```

### 2. Performance Monitoring

```java
public class SchedulePerformanceMonitor {
    
    private final ScheduledExecutorService monitor = 
        Executors.newSingleThreadScheduledExecutor();
    
    public void startMonitoring(ScheduleManager scheduleManager) {
        monitor.scheduleAtFixedRate(() -> {
            try {
                // Monitor schedule manager performance
                boolean isRunning = scheduleManager.isRunning();
                int checkInterval = scheduleManager.getScheduler().getCheckInterval();
                
                // Log performance metrics
                logger.debug("Schedule manager running: {}, check interval: {}s", 
                    isRunning, checkInterval);
                
                // Check for any stuck operations
                if (isRunning) {
                    scheduleManager.checkSchedulesNow(); // Force a check
                }
                
            } catch (Exception e) {
                logger.warn("Schedule monitoring failed", e);
            }
        }, 5, 5, TimeUnit.MINUTES);
    }
}
```

### 3. Testing Integration

```java
public class SchedulingIntegrationTest {
    
    @Test
    public void testScheduleIntegration() {
        // Create test download manager
        DownloadManager testManager = createTestDownloadManager();
        
        // Create schedule manager
        ScheduleManager scheduleManager = new ScheduleManager(testManager);
        scheduleManager.start().get();
        
        try {
            // Test basic scheduling
            Download download = testManager.createDownload(
                URI.create("https://test.com/file.zip"), 
                Paths.get("/tmp")
            );
            
            scheduleManager.setPresetSchedule(download.getId(), "business");
            
            String scheduleInfo = scheduleManager.getScheduleInfo(download.getId());
            assertThat(scheduleInfo).contains("Business Hours");
            
            // Test schedule enforcement
            boolean shouldBeActive = scheduleManager.shouldDownloadBeActive(download.getId());
            assertThat(shouldBeActive).isNotNull();
            
        } finally {
            scheduleManager.shutdown().get();
        }
    }
}
```

## Migration Guide

### From Existing System

If you have an existing download manager without scheduling:

1. **Add Dependencies**: Include the scheduling classes in your project
2. **Update Download Creation**: Modify download creation to optionally include schedules
3. **Add UI Components**: Create schedule configuration dialogs
4. **Update Persistence**: Extend your save/load mechanism to include schedules
5. **Add Event Handling**: Implement schedule event listeners
6. **Test Thoroughly**: Verify that existing downloads continue to work

### Gradual Rollout

1. **Phase 1**: Add scheduling infrastructure without enforcement
2. **Phase 2**: Enable scheduling for new downloads only
3. **Phase 3**: Allow users to add schedules to existing downloads
4. **Phase 4**: Enable automatic schedule enforcement

## Troubleshooting

### Common Issues

1. **Downloads not pausing/resuming**: Check if schedule manager is started and running
2. **Incorrect time calculations**: Verify system timezone and time format parsing
3. **Performance issues**: Adjust check interval or review schedule complexity
4. **Memory leaks**: Ensure proper cleanup of schedule listeners and resources

### Debug Information

```java
public void printScheduleDebugInfo(ScheduleManager scheduleManager) {
    logger.info("=== Schedule Debug Information ===");
    logger.info("Schedule manager running: {}", scheduleManager.isRunning());
    logger.info("Check interval: {}s", scheduleManager.getScheduler().getCheckInterval());
    
    ScheduleSettings globalSchedule = scheduleManager.getScheduler().getGlobalSchedule();
    logger.info("Global schedule: {}", globalSchedule);
    
    Map<String, String> allStatus = scheduleManager.getScheduleStatus();
    allStatus.forEach((id, status) -> 
        logger.info("Download {}: {}", id, status));
}
```

This integration guide provides a comprehensive overview of how to incorporate the scheduling system into your download manager application. Follow the examples and best practices to ensure smooth integration and optimal user experience.