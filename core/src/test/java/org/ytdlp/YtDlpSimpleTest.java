package org.ytdlp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.manager.GlobalSettings;
import org.ytdlp.YtDlpUrlUtils.Platform;
import org.ytdlp.YtDlpUrlUtils.UrlInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple compilation-safe tests for the ytdlp package.
 * This test class validates basic functionality without complex mocking or external dependencies.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("YtDlp Simple Tests")
class YtDlpSimpleTest {

    private YtDlpFactory factory;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("ytdlp-simple-test");

        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(tempDir);

        YtDlpFactory.clearInstance();
        factory = YtDlpFactory.getInstance(globalSettings);
    }

    @Test
    @DisplayName("Should create YtDlpSettings with default values")
    void testYtDlpSettingsDefaults() {
        YtDlpSettings settings = new YtDlpSettings();

        assertNotNull(settings);
        assertFalse(settings.isEmbedThumbnail());
        assertFalse(settings.isExtractAudio());
        assertEquals(10, settings.getFragmentRetries());
    }

    @Test
    @DisplayName("Should support method chaining in YtDlpSettings")
    void testYtDlpSettingsChaining() {
        YtDlpSettings settings = new YtDlpSettings()
            .setFormat("best")
            .setEmbedThumbnail(true)
            .setExtractAudio(true)
            .setAudioFormat("mp3");

        assertEquals("best", settings.getFormat());
        assertTrue(settings.isEmbedThumbnail());
        assertTrue(settings.isExtractAudio());
        assertEquals("mp3", settings.getAudioFormat());
    }

    @Test
    @DisplayName("Should handle subtitle languages correctly")
    void testSubtitleLanguages() {
        YtDlpSettings settings = new YtDlpSettings();

        List<String> languages = Arrays.asList("en", "es", "fr");
        settings.setSubtitleLanguages(languages);

        assertEquals(languages, settings.getSubtitleLanguages());
        assertEquals(3, settings.getSubtitleLanguages().size());
    }

    @Test
    @DisplayName("Should validate YouTube URLs correctly")
    void testYouTubeUrlValidation() {
        assertTrue(YtDlpUrlUtils.isSupported("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(YtDlpUrlUtils.isSupported("https://youtu.be/dQw4w9WgXcQ"));
        assertFalse(YtDlpUrlUtils.isSupported("https://example.com"));
        assertFalse(YtDlpUrlUtils.isSupported("not-a-url"));
    }

    @Test
    @DisplayName("Should analyze URLs correctly")
    void testUrlAnalysis() {
        String youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        UrlInfo urlInfo = YtDlpUrlUtils.analyzeUrl(youtubeUrl);

        assertNotNull(urlInfo);
        assertEquals(Platform.YOUTUBE, urlInfo.getPlatform());
        assertEquals("dQw4w9WgXcQ", urlInfo.getVideoId());
        assertFalse(urlInfo.isPlaylist());
    }

    @Test
    @DisplayName("Should detect playlists correctly")
    void testPlaylistDetection() {
        String playlistUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLtest";
        UrlInfo urlInfo = YtDlpUrlUtils.analyzeUrl(playlistUrl);

        assertEquals(Platform.YOUTUBE_PLAYLIST, urlInfo.getPlatform());
        assertTrue(urlInfo.isPlaylist());
    }

    @Test
    @DisplayName("Should suggest formats for different platforms")
    void testFormatSuggestions() {
        assertEquals("bestvideo+bestaudio/best",
                    YtDlpUrlUtils.getSuggestedFormat(Platform.YOUTUBE, "best"));
        assertEquals("bestaudio/best",
                    YtDlpUrlUtils.getSuggestedFormat(Platform.YOUTUBE, "audio"));
        assertEquals("best",
                    YtDlpUrlUtils.getSuggestedFormat(Platform.TWITCH, "best"));
    }

    @Test
    @DisplayName("Should identify audio extraction support")
    void testAudioExtractionSupport() {
        assertTrue(YtDlpUrlUtils.supportsAudioExtraction(Platform.YOUTUBE));
        assertTrue(YtDlpUrlUtils.supportsAudioExtraction(Platform.SOUNDCLOUD));
        assertFalse(YtDlpUrlUtils.supportsAudioExtraction(Platform.TWITCH));
    }

    @Test
    @DisplayName("Should create YtDlpFactory instance")
    void testFactoryCreation() {
        assertNotNull(factory);
        assertNotNull(factory.getGlobalSettings());
    }

    @Test
    @DisplayName("Should create clients through factory")
    void testClientCreation() {
        YtDlpClient client = factory.createClient();
        assertNotNull(client);
        client.shutdown();
    }

    @Test
    @DisplayName("Should create different settings types")
    void testSettingsCreation() {
        YtDlpSettings defaultSettings = factory.createDefaultSettings();
        YtDlpSettings audioSettings = factory.createAudioSettings();
        YtDlpSettings videoSettings = factory.createHighQualityVideoSettings();

        assertNotNull(defaultSettings);
        assertNotNull(audioSettings);
        assertNotNull(videoSettings);

        assertTrue(audioSettings.isExtractAudio());
        assertEquals("mp3", audioSettings.getAudioFormat());
    }

    @Test
    @DisplayName("Should create download tasks")
    void testDownloadTaskCreation() {
        String taskId = "test-task";
        String url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        YtDlpSettings settings = factory.createDefaultSettings();

        YtDlpDownloadTask task = factory.createDownloadTask(taskId, url, settings, tempDir);

        assertNotNull(task);
        assertEquals(taskId, task.getTaskId());
        assertEquals(url, task.getUrl());
        assertEquals(YtDlpDownloadTask.Status.PENDING, task.getStatus());

        factory.removeDownloadTask(taskId);
    }

    @Test
    @DisplayName("Should copy settings correctly")
    void testSettingsCopy() {
        YtDlpSettings original = new YtDlpSettings()
            .setFormat("best")
            .setEmbedThumbnail(true)
            .setExtractAudio(true);

        YtDlpSettings copy = (YtDlpSettings) original.copy();

        assertNotSame(original, copy);
        assertEquals(original.getFormat(), copy.getFormat());
        assertEquals(original.isEmbedThumbnail(), copy.isEmbedThumbnail());
        assertEquals(original.isExtractAudio(), copy.isExtractAudio());
    }

    @Test
    @DisplayName("Should build Aria2c arguments")
    void testAria2cArguments() {
        YtDlpSettings settings = new YtDlpSettings()
            .setUseAria2c(true)
            .setAria2cConnections(16)
            .setAria2cSplitConnections(8)
            .setAria2cMinSplitSize("1M");

        String args = settings.buildAria2cArgs();

        assertNotNull(args);
        assertFalse(args.isEmpty());
        assertTrue(args.contains("-x 16"));
        assertTrue(args.contains("-s 8"));
        assertTrue(args.contains("-k 1M"));
    }

    @Test
    @DisplayName("Should handle factory shutdown")
    void testFactoryShutdown() {
        factory.createClient().shutdown();
        assertDoesNotThrow(() -> factory.shutdown());
    }
}
