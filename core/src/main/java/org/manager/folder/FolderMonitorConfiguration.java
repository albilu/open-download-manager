package org.manager.folder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration class for persisting and managing folder monitoring settings.
 * This class handles saving and loading folder monitoring configurations
 * to/from JSON files, allowing settings to persist across application restarts.
 */
public class FolderMonitorConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(FolderMonitorConfiguration.class);
    private static final String DEFAULT_CONFIG_FILE = "folder-monitor-config.json";

    private ObjectMapper objectMapper;
    private Path configFilePath;

    @JsonProperty("enabled")
    private boolean enabled = false;

    @JsonProperty("autoStartDefault")
    private boolean autoStartDefault = false;

    @JsonProperty("monitoredFolders")
    private Map<String, FolderConfig> monitoredFolders = new HashMap<>();

    @JsonProperty("globalSettings")
    private GlobalFolderSettings globalSettings = new GlobalFolderSettings();

    /**
     * Creates a new FolderMonitorConfiguration with default config file
     * location.
     */
    public FolderMonitorConfiguration() {
        this(getDefaultConfigPath());
    }

    /**
     * Creates a new FolderMonitorConfiguration with custom config file path.
     *
     * @param configFilePath The path to the configuration file
     */
    public FolderMonitorConfiguration(Path configFilePath) {
        this.configFilePath = configFilePath;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Gets the default configuration file path.
     *
     * @return The default config file path in user's home directory
     */
    private static Path getDefaultConfigPath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".odm", DEFAULT_CONFIG_FILE);
    }

    /**
     * Checks if folder monitoring is globally enabled.
     *
     * @return true if folder monitoring is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether folder monitoring is globally enabled.
     *
     * @param enabled true to enable folder monitoring
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Checks if the default Downloads folder should be monitored automatically
     * on startup.
     *
     * @return true if auto-start default monitoring is enabled
     */
    public boolean isAutoStartDefault() {
        return autoStartDefault;
    }

    /**
     * Sets whether to automatically start monitoring the default Downloads
     * folder on startup.
     *
     * @param autoStartDefault true to enable auto-start
     */
    public void setAutoStartDefault(boolean autoStartDefault) {
        this.autoStartDefault = autoStartDefault;
    }

    /**
     * Gets all configured monitored folders.
     *
     * @return A map of folder path strings to their configurations
     */
    public Map<String, FolderConfig> getMonitoredFolders() {
        return new HashMap<>(monitoredFolders);
    }

    /**
     * Adds or updates a folder configuration.
     *
     * @param folderPath The folder path to monitor
     * @param config     The folder configuration
     */
    public void addFolderConfig(Path folderPath, FolderConfig config) {
        monitoredFolders.put(folderPath.toString(), config);
    }

    /**
     * Removes a folder configuration.
     *
     * @param folderPath The folder path to stop monitoring
     */
    public void removeFolderConfig(Path folderPath) {
        monitoredFolders.remove(folderPath.toString());
    }

    /**
     * Gets the configuration for a specific folder.
     *
     * @param folderPath The folder path
     * @return The folder configuration, or null if not found
     */
    public FolderConfig getFolderConfig(Path folderPath) {
        return monitoredFolders.get(folderPath.toString());
    }

    /**
     * Checks if a folder is configured for monitoring.
     *
     * @param folderPath The folder path to check
     * @return true if the folder is configured
     */
    public boolean hasFolderConfig(Path folderPath) {
        return monitoredFolders.containsKey(folderPath.toString());
    }

    /**
     * Gets the global folder monitoring settings.
     *
     * @return The global settings
     */
    public GlobalFolderSettings getGlobalSettings() {
        return globalSettings;
    }

    /**
     * Sets the global folder monitoring settings.
     *
     * @param globalSettings The global settings
     */
    public void setGlobalSettings(GlobalFolderSettings globalSettings) {
        this.globalSettings = globalSettings;
    }

    /**
     * Saves the configuration to the config file.
     *
     * @return A future that completes when the save operation is done
     */
    public CompletableFuture<Void> save() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Ensure parent directory exists
                Path parentDir = configFilePath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }

                // Write configuration to file
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(configFilePath.toFile(), this);

                LOGGER.info("Saved folder monitor configuration to: " + configFilePath);

            } catch (IOException e) {
                LOGGER.error("Failed to save folder monitor configuration", e);
                throw new RuntimeException("Failed to save configuration", e);
            }
        });
    }

    /**
     * Loads the configuration from the config file.
     *
     * @return A future that completes with the loaded configuration
     */
    public static CompletableFuture<FolderMonitorConfiguration> load() {
        return load(getDefaultConfigPath());
    }

    /**
     * Loads the configuration from a specific config file.
     *
     * @param configFilePath The path to the configuration file
     * @return A future that completes with the loaded configuration
     */
    public static CompletableFuture<FolderMonitorConfiguration> load(Path configFilePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(configFilePath)) {
                    LOGGER.info("Configuration file not found, creating default: " + configFilePath);
                    FolderMonitorConfiguration defaultConfig = new FolderMonitorConfiguration(configFilePath);
                    defaultConfig.save().join();
                    return defaultConfig;
                }

                ObjectMapper mapper = new ObjectMapper();
                FolderMonitorConfiguration config = mapper.readValue(
                        configFilePath.toFile(),
                        FolderMonitorConfiguration.class);
                config.configFilePath = configFilePath;
                config.objectMapper = mapper;

                LOGGER.info("Loaded folder monitor configuration from: " + configFilePath);
                return config;

            } catch (IOException e) {
                LOGGER.error("Failed to load folder monitor configuration", e);
                throw new RuntimeException("Failed to load configuration", e);
            }
        });
    }

    /**
     * Creates a default configuration with common torrent monitoring settings.
     *
     * @return A default configuration
     */
    public static FolderMonitorConfiguration createDefault() {
        FolderMonitorConfiguration config = new FolderMonitorConfiguration();
        config.setEnabled(true);
        config.setAutoStartDefault(true);

        // Add default Downloads folder configuration
        String userHome = System.getProperty("user.home");
        Path downloadsFolder = Paths.get(userHome, "Downloads");

        FolderConfig defaultFolderConfig = new FolderConfig();
        defaultFolderConfig.setEnabled(true);
        defaultFolderConfig.setSettings(TorrentFolderMonitor.createDefaultTorrentSettings());

        config.addFolderConfig(downloadsFolder, defaultFolderConfig);

        return config;
    }

    /**
     * Converts this configuration to FolderMonitorSettings for a specific
     * folder.
     *
     * @param folderPath The folder path
     * @return The folder monitor settings, or null if folder not configured
     */
    public FolderMonitorSettings toFolderMonitorSettings(Path folderPath) {
        FolderConfig folderConfig = getFolderConfig(folderPath);
        if (folderConfig == null || !folderConfig.isEnabled()) {
            return null;
        }

        return folderConfig.getSettings();
    }

    /**
     * Gets the configuration file path.
     *
     * @return The configuration file path
     */
    public Path getConfigFilePath() {
        return configFilePath;
    }

    @Override
    public String toString() {
        return "FolderMonitorConfiguration{"
                + "enabled=" + enabled
                + ", autoStartDefault=" + autoStartDefault
                + ", monitoredFolders=" + monitoredFolders.size()
                + ", configFilePath=" + configFilePath
                + '}';
    }

    /**
     * Configuration for a specific folder.
     */
    public static class FolderConfig {

        @JsonProperty("enabled")
        private boolean enabled = true;

        @JsonProperty("settings")
        private FolderMonitorSettings settings;

        @JsonProperty("description")
        private String description = "";

        @JsonProperty("lastModified")
        private long lastModified = System.currentTimeMillis();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            this.lastModified = System.currentTimeMillis();
        }

        public FolderMonitorSettings getSettings() {
            return settings;
        }

        public void setSettings(FolderMonitorSettings settings) {
            this.settings = settings;
            this.lastModified = System.currentTimeMillis();
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
            this.lastModified = System.currentTimeMillis();
        }

        public long getLastModified() {
            return lastModified;
        }

        @Override
        public String toString() {
            return "FolderConfig{"
                    + "enabled=" + enabled
                    + ", description='" + description + '\''
                    + ", lastModified=" + lastModified
                    + '}';
        }
    }

    /**
     * Global settings for folder monitoring.
     */
    public static class GlobalFolderSettings {

        @JsonProperty("maxConcurrentFolders")
        private int maxConcurrentFolders = 10;

        @JsonProperty("defaultDebounceDelay")
        private long defaultDebounceDelayMs = 2000;

        @JsonProperty("defaultFileAction")
        private FolderMonitorSettings.FileAction defaultFileAction = FolderMonitorSettings.FileAction.MOVE_TO_TRASH;

        @JsonProperty("enableLogging")
        private boolean enableLogging = true;

        @JsonProperty("statisticsEnabled")
        private boolean statisticsEnabled = true;

        public int getMaxConcurrentFolders() {
            return maxConcurrentFolders;
        }

        public void setMaxConcurrentFolders(int maxConcurrentFolders) {
            this.maxConcurrentFolders = maxConcurrentFolders;
        }

        public Duration getDefaultDebounceDelay() {
            return Duration.ofMillis(defaultDebounceDelayMs);
        }

        public void setDefaultDebounceDelay(Duration defaultDebounceDelay) {
            this.defaultDebounceDelayMs = defaultDebounceDelay.toMillis();
        }

        public FolderMonitorSettings.FileAction getDefaultFileAction() {
            return defaultFileAction;
        }

        public void setDefaultFileAction(FolderMonitorSettings.FileAction defaultFileAction) {
            this.defaultFileAction = defaultFileAction;
        }

        public boolean isEnableLogging() {
            return enableLogging;
        }

        public void setEnableLogging(boolean enableLogging) {
            this.enableLogging = enableLogging;
        }

        public boolean isStatisticsEnabled() {
            return statisticsEnabled;
        }

        public void setStatisticsEnabled(boolean statisticsEnabled) {
            this.statisticsEnabled = statisticsEnabled;
        }

        @Override
        public String toString() {
            return "GlobalFolderSettings{"
                    + "maxConcurrentFolders=" + maxConcurrentFolders
                    + ", defaultDebounceDelayMs=" + defaultDebounceDelayMs
                    + ", defaultFileAction=" + defaultFileAction
                    + ", enableLogging=" + enableLogging
                    + ", statisticsEnabled=" + statisticsEnabled
                    + '}';
        }
    }
}
