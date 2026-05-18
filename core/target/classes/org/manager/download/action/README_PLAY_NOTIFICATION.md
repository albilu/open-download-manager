# PlayNotificationAction

A comprehensive after-completion action that plays sound notifications when downloads complete. This implementation supports multiple audio playback methods and provides extensive configuration options for different operating systems and audio setups.

## Overview

The `PlayNotificationAction` is designed to provide reliable sound notifications across different platforms and audio configurations. It supports:

-   System beep notifications
-   Built-in generated sounds
-   Custom sound files (WAV, AIFF, AU formats)
-   System command execution for audio playback
-   Volume control and repeat functionality
-   Automatic fallback mechanisms

## Features

### Multiple Sound Types

-   **System Beep**: Uses the system's built-in beep sound
-   **Built-in Sounds**: Generated tones for success, notification, and completion
-   **Custom Files**: Play any supported audio file
-   **System Commands**: Execute platform-specific audio commands

### Advanced Configuration

-   **Volume Control**: Adjustable volume from 0.0 to 1.0
-   **Repeat Functionality**: Play sounds multiple times with customizable intervals
-   **Timeout Control**: Configurable timeout for long-running audio operations
-   **Cancellation Support**: Stop audio playback at any time

### Platform Support

-   **Linux**: PulseAudio (paplay), ALSA (aplay), OSS support
-   **macOS**: Built-in afplay command and system sounds
-   **Windows**: PowerShell integration and system sounds
-   **Cross-platform**: Java Audio API fallback

## Usage Examples

### Basic Usage

```java
// Simple system beep
PlayNotificationAction beep = new PlayNotificationAction(
    PlayNotificationAction.NotificationSound.SYSTEM_BEEP);

// Built-in success sound
PlayNotificationAction success = new PlayNotificationAction(
    PlayNotificationAction.NotificationSound.SUCCESS);

// Execute the action
boolean result = beep.execute(completedDownload);
```

### Custom Sound File

```java
// Use a custom WAV file
Path soundFile = Paths.get("/usr/share/sounds/alsa/Front_Left.wav");
PlayNotificationAction custom = new PlayNotificationAction(soundFile);

// Configure volume and repeat
custom.setVolume(0.8f)
      .setRepeat(true, 2)
      .setTimeout(10);

boolean result = custom.execute(completedDownload);
```

### System Command

```java
// Use PulseAudio on Linux
PlayNotificationAction paplay = new PlayNotificationAction(
    "paplay /usr/share/sounds/ubuntu/stereo/bell.ogg");

// Use afplay on macOS
PlayNotificationAction afplay = new PlayNotificationAction(
    "afplay /System/Library/Sounds/Ping.aiff");

boolean result = paplay.execute(completedDownload);
```

### Auto-Detection

```java
// Automatically detect the best sound method for the current system
PlayNotificationAction optimal = SoundSystemUtils.createOptimalNotificationAction();
boolean result = optimal.execute(completedDownload);
```

## Sound System Detection

The package includes `SoundSystemUtils` for detecting and testing audio capabilities:

```java
// Detect system capabilities
SoundSystemUtils.SoundSystemInfo info = SoundSystemUtils.detectSoundSystem();

System.out.println("Java Audio Support: " + info.isJavaAudioSupported());
System.out.println("Available Commands: " + info.getAvailableCommands());
System.out.println("Sound Files Found: " + info.getAvailableSoundFiles().size());

// Run comprehensive tests
Map<String, Boolean> testResults = SoundSystemUtils.runComprehensiveTest();
```

### Detection Tool

Use the `SoundSystemDetector` to analyze your system:

```bash
java org.manager.download.action.SoundSystemDetector
```

This will output detailed information about:

-   Available audio devices and formats
-   System sound commands
-   Usable sound files
-   Test results for different methods
-   Platform-specific recommendations

## Configuration Options

### Volume Control

```java
action.setVolume(0.5f);  // 50% volume
action.setVolume(1.0f);  // Full volume
action.setVolume(0.0f);  // Muted
```

### Repeat Settings

```java
// Play 3 times with default intervals
action.setRepeat(true, 3);

// Single playback (default)
action.setRepeat(false, 1);
```

### Timeout Configuration

```java
// 5 second timeout
action.setTimeout(5);

// No timeout (use with caution)
action.setTimeout(0);
```

## Platform-Specific Guidance

### Linux

**Recommended Methods:**

1. PulseAudio: `paplay /path/to/sound.wav`
2. ALSA: `aplay /path/to/sound.wav`
3. Java Audio API for built-in sounds

**Common Sound Locations:**

-   `/usr/share/sounds/alsa/`
-   `/usr/share/sounds/freedesktop/stereo/`
-   `/usr/share/sounds/ubuntu/stereo/`

**Setup Tips:**

```bash
# Install sound packages
sudo apt-get install pulseaudio-utils alsa-utils sound-theme-freedesktop

# Test audio
paplay /usr/share/sounds/alsa/Front_Left.wav
```

### macOS

**Recommended Methods:**

1. afplay: `afplay /System/Library/Sounds/Ping.aiff`
2. Java Audio API

**Common Sound Locations:**

-   `/System/Library/Sounds/`

**Example:**

```java
PlayNotificationAction mac = new PlayNotificationAction(
    "afplay /System/Library/Sounds/Glass.aiff");
```

### Windows

**Recommended Methods:**

1. Java Audio API
2. PowerShell: `powershell -c (New-Object Media.SoundPlayer 'C:\Windows\Media\ding.wav').PlaySync()`

**Common Sound Locations:**

-   `C:\Windows\Media\`

**Example:**

```java
PlayNotificationAction windows = new PlayNotificationAction(
    "powershell -c (New-Object Media.SoundPlayer 'C:\\Windows\\Media\\Windows Ding.wav').PlaySync()");
```

## Integration with Download Manager

### Using with AfterCompletionActionManager

```java
// Create the action
PlayNotificationAction soundAction = new PlayNotificationAction(
    PlayNotificationAction.NotificationSound.COMPLETE);
soundAction.setVolume(0.7f);

// Add to download manager
AfterCompletionActionManager actionManager = new AfterCompletionActionManager();
actionManager.addAction(soundAction);

// The action will automatically execute when downloads complete
```

### Custom Integration

```java
// In your download completion handler
public void onDownloadComplete(Download download) {
    PlayNotificationAction notification = new PlayNotificationAction(
        PlayNotificationAction.NotificationSound.SUCCESS);

    boolean played = notification.execute(download);
    if (!played) {
        logger.warning("Failed to play notification sound");
    }
}
```

## Error Handling

The implementation includes comprehensive error handling:

```java
PlayNotificationAction action = new PlayNotificationAction(
    Paths.get("/non/existent/file.wav"));

boolean success = action.execute(download);
if (!success) {
    // Fallback to system beep
    PlayNotificationAction fallback = new PlayNotificationAction(
        PlayNotificationAction.NotificationSound.SYSTEM_BEEP);
    fallback.execute(download);
}
```

### Graceful Degradation

```java
// This method tries multiple approaches automatically
PlayNotificationAction robust = PlayNotificationAction.createWithFallbacks();
robust.execute(download); // Will always try to make some sound
```

## Advanced Features

### Cancellation

```java
PlayNotificationAction longSound = new PlayNotificationAction(soundFile);
longSound.setRepeat(true, 10);

// Start playback
longSound.execute(download);

// Cancel after 2 seconds
Thread.sleep(2000);
boolean cancelled = longSound.cancel();
```

### Status Monitoring

```java
PlayNotificationAction action = new PlayNotificationAction(soundFile);
action.execute(download);

// Check if still playing
while (action.isPlaying()) {
    Thread.sleep(100);
}
```

### Testing Audio Methods

```java
// Test a specific method
boolean works = SoundSystemUtils.testSoundMethod(action, 5000);

// Test all available methods
Map<String, Boolean> results = SoundSystemUtils.runComprehensiveTest();
```

## Troubleshooting

### No Sound Output

1. **Check Audio System**: Run `SoundSystemDetector` to verify audio setup
2. **Test Java Audio**: Ensure Java has access to audio devices
3. **Check Permissions**: Verify file permissions for custom sound files
4. **Volume Settings**: Check both system and application volume levels

### Common Issues

| Issue                | Solution                                     |
| -------------------- | -------------------------------------------- |
| "Line not supported" | Try different audio format or system command |
| "File not found"     | Verify sound file path and permissions       |
| "Command failed"     | Check if audio command is installed          |
| "No audio device"    | Install audio drivers or use remote audio    |

### Debug Mode

Enable detailed logging:

```java
Logger.getLogger(PlayNotificationAction.class.getName()).setLevel(Level.FINE);
```

## Performance Considerations

-   **Memory Usage**: Built-in sounds use minimal memory
-   **CPU Impact**: Audio playback is handled in separate threads
-   **Startup Time**: System commands may have slight overhead
-   **Resource Cleanup**: All audio resources are properly closed

## Dependencies

### Required

-   Java 21+ (for basic functionality)
-   Java Audio API (included in JRE)

### Optional

-   Platform-specific audio commands (paplay, aplay, afplay, etc.)
-   System sound files
-   Audio drivers and devices

## Examples and Testing

### Complete Example

```java
public class NotificationExample {
    public static void main(String[] args) throws Exception {
        // Create a test download
        Download download = new Download(new URI("https://example.com/file.zip"));
        download.setName("example-file.zip");
        download.setDestination(Paths.get("/tmp"));
        download.setStatus(Download.Status.COMPLETED);

        // Create and configure notification
        PlayNotificationAction notification = new PlayNotificationAction(
            PlayNotificationAction.NotificationSound.SUCCESS);
        notification.setVolume(0.8f)
                   .setRepeat(true, 2)
                   .setTimeout(10);

        // Execute
        boolean success = notification.execute(download);
        System.out.println("Notification played: " + success);
    }
}
```

### Batch Testing

See `PlayNotificationActionExample.java` for comprehensive examples including:

-   All notification types
-   Platform detection
-   Error handling scenarios
-   Performance testing
-   Integration examples

## Future Enhancements

Potential future improvements:

-   Support for additional audio formats (MP3, OGG)
-   Audio visualization during playback
-   Playlist support for multiple sounds
-   Network audio streaming
-   Integration with desktop notification systems
-   Custom sound themes and profiles

## Contributing

When contributing to the PlayNotificationAction:

1. Test on multiple platforms
2. Ensure graceful fallbacks
3. Add appropriate logging
4. Update documentation
5. Test with various audio setups

---

_Note: This implementation prioritizes reliability and cross-platform compatibility. While it may not support every audio format or exotic setup, it provides robust functionality for the vast majority of use cases._
