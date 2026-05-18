package org.ytdlp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.ytdlp.YtDlpUrlUtils.Platform;
import org.ytdlp.YtDlpUrlUtils.UrlInfo;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for YtDlpUrlUtils class.
 * Tests URL validation, platform detection, video ID extraction, and utility methods.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("YtDlpUrlUtils Unit Tests")
class YtDlpUrlUtilsTest {

    @Test
    @DisplayName("Should validate supported URLs correctly")
    void testIsSupportedValidUrls() {
        // YouTube URLs
        assertTrue(YtDlpUrlUtils.isSupported("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(YtDlpUrlUtils.isSupported("https://youtu.be/dQw4w9WgXcQ"));
        assertTrue(YtDlpUrlUtils.isSupported("https://m.youtube.com/watch?v=dQw4w9WgXcQ"));

        // Vimeo URLs
        assertTrue(YtDlpUrlUtils.isSupported("https://vimeo.com/123456789"));
        assertTrue(YtDlpUrlUtils.isSupported("https://www.vimeo.com/123456789"));

        // Dailymotion URLs
        assertTrue(YtDlpUrlUtils.isSupported("https://www.dailymotion.com/video/x7abc123"));

        // Twitch URLs
        assertTrue(YtDlpUrlUtils.isSupported("https://www.twitch.tv/videos/123456789"));
        assertTrue(YtDlpUrlUtils.isSupported("https://clips.twitch.tv/ClipName"));

        // TikTok URLs
        assertTrue(YtDlpUrlUtils.isSupported("https://www.tiktok.com/@user/video/123456789"));
        assertTrue(YtDlpUrlUtils.isSupported("https://vm.tiktok.com/abc123"));

        // Social Media URLs
        assertTrue(YtDlpUrlUtils.isSupported("https://www.instagram.com/p/ABC123/"));
        assertTrue(YtDlpUrlUtils.isSupported("https://www.facebook.com/watch/?v=123456789"));
        assertTrue(YtDlpUrlUtils.isSupported("https://twitter.com/user/status/123456789"));

        // Audio platforms
        assertTrue(YtDlpUrlUtils.isSupported("https://soundcloud.com/artist/track"));

        // Generic video patterns
        assertTrue(YtDlpUrlUtils.isSupported("https://example.com/video.mp4"));
        assertTrue(YtDlpUrlUtils.isSupported("https://example.com/watch?v=123"));
        assertTrue(YtDlpUrlUtils.isSupported("https://example.com/player/embed/123"));
    }

    @Test
    @DisplayName("Should reject unsupported URLs")
    void testIsSupportedInvalidUrls() {
        // Null and empty URLs
        assertFalse(YtDlpUrlUtils.isSupported(null));
        assertFalse(YtDlpUrlUtils.isSupported(""));
        assertFalse(YtDlpUrlUtils.isSupported("   "));

        // Invalid URLs
        assertFalse(YtDlpUrlUtils.isSupported("not-a-url"));
        assertFalse(YtDlpUrlUtils.isSupported("://malformed"));

        // Unsupported domains
        assertFalse(YtDlpUrlUtils.isSupported("https://example.com"));
        assertFalse(YtDlpUrlUtils.isSupported("https://google.com"));
        assertFalse(YtDlpUrlUtils.isSupported("https://unknown-site.com/page"));

        // Non-video content
        assertFalse(YtDlpUrlUtils.isSupported("https://example.com/document.pdf"));
        assertFalse(YtDlpUrlUtils.isSupported("https://example.com/image.jpg"));
    }

    @ParameterizedTest
    @CsvSource({
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ, YOUTUBE, dQw4w9WgXcQ, false",
        "https://youtu.be/dQw4w9WgXcQ, YOUTUBE, dQw4w9WgXcQ, false",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLrAXtmRdnEQy8JvnKxpxRk7-bHYWWJNNy, YOUTUBE_PLAYLIST, dQw4w9WgXcQ, true",
        "https://vimeo.com/123456789, VIMEO, 123456789, false",
        "https://www.dailymotion.com/video/x7abc123, DAILYMOTION, x7abc123, false",
        "https://www.twitch.tv/streamer, TWITCH, streamer, false",
        "https://www.tiktok.com/@user/video/123, TIKTOK, , false",
        "https://www.instagram.com/p/ABC123/, INSTAGRAM, , false",
        "https://soundcloud.com/artist/track, SOUNDCLOUD, , false"
    })
    @DisplayName("Should analyze URLs correctly")
    void testAnalyzeUrl(String url, Platform expectedPlatform, String expectedVideoId, boolean expectedIsPlaylist) {
        UrlInfo urlInfo = YtDlpUrlUtils.analyzeUrl(url);

        assertNotNull(urlInfo);
        assertEquals(expectedPlatform, urlInfo.getPlatform());
        assertEquals(expectedVideoId, urlInfo.getVideoId());
        assertEquals(expectedIsPlaylist, urlInfo.isPlaylist());
        assertEquals(url, urlInfo.getOriginalUrl());
    }

    @Test
    @DisplayName("Should handle invalid URLs in analysis")
    void testAnalyzeUrlInvalid() {
        // Null URL
        UrlInfo nullInfo = YtDlpUrlUtils.analyzeUrl(null);
        assertEquals(Platform.UNKNOWN, nullInfo.getPlatform());
        assertNull(nullInfo.getVideoId());
        assertFalse(nullInfo.isPlaylist());

        // Empty URL
        UrlInfo emptyInfo = YtDlpUrlUtils.analyzeUrl("");
        assertEquals(Platform.UNKNOWN, emptyInfo.getPlatform());
        assertNull(emptyInfo.getVideoId());

        // Malformed URL
        UrlInfo malformedInfo = YtDlpUrlUtils.analyzeUrl("not-a-url");
        assertEquals(Platform.UNKNOWN, malformedInfo.getPlatform());
        assertNull(malformedInfo.getDomain());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "http://example.com",
        "https://vimeo.com/123456789",
        "http://www.dailymotion.com/video/x123"
    })
    @DisplayName("Should validate proper URLs")
    void testIsValidUrl(String url) {
        assertTrue(YtDlpUrlUtils.isValidUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "not-a-url",
        "ftp://example.com",
        "file:///local/file.txt",
        "javascript:alert('xss')"
    })
    @DisplayName("Should reject invalid URLs")
    void testIsValidUrlInvalid(String url) {
        assertFalse(YtDlpUrlUtils.isValidUrl(url));
    }

    @Test
    @DisplayName("Should handle null URL validation")
    void testIsValidUrlNull() {
        assertFalse(YtDlpUrlUtils.isValidUrl(null));
    }

    @ParameterizedTest
    @CsvSource({
        "example.com, https://example.com",
        "www.youtube.com/watch?v=123, https://www.youtube.com/watch?v=123",
        "https://already-has-protocol.com, https://already-has-protocol.com",
        "http://already-http.com, http://already-http.com",
        "'', ''",
        "' ', ' '"
    })
    @DisplayName("Should normalize URLs correctly")
    void testNormalizeUrl(String input, String expected) {
        assertEquals(expected, YtDlpUrlUtils.normalizeUrl(input));
    }

    @Test
    @DisplayName("Should handle null URL normalization")
    void testNormalizeUrlNull() {
        assertNull(YtDlpUrlUtils.normalizeUrl(null));
    }

    @ParameterizedTest
    @MethodSource("provideYouTubeVideoIdTestCases")
    @DisplayName("Should extract YouTube video IDs correctly")
    void testYouTubeVideoIdExtraction(String url, String expectedVideoId) {
        UrlInfo urlInfo = YtDlpUrlUtils.analyzeUrl(url);
        assertEquals(expectedVideoId, urlInfo.getVideoId());
    }

    private Stream<Arguments> provideYouTubeVideoIdTestCases() {
        return Stream.of(
            Arguments.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ"),
            Arguments.of("https://youtu.be/dQw4w9WgXcQ", "dQw4w9WgXcQ"),
            Arguments.of("https://www.youtube.com/embed/dQw4w9WgXcQ", "dQw4w9WgXcQ"),
            Arguments.of("https://m.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ"),
            Arguments.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s", "dQw4w9WgXcQ"),
            Arguments.of("https://www.youtube.com/watch?list=PLtest&v=dQw4w9WgXcQ", "dQw4w9WgXcQ")
        );
    }

    @Test
    @DisplayName("Should detect playlists correctly")
    void testPlaylistDetection() {
        // YouTube playlists
        UrlInfo playlistInfo = YtDlpUrlUtils.analyzeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLtest");
        assertTrue(playlistInfo.isPlaylist());
        assertEquals(Platform.YOUTUBE_PLAYLIST, playlistInfo.getPlatform());

        UrlInfo playlistOnlyInfo = YtDlpUrlUtils.analyzeUrl("https://www.youtube.com/playlist?list=PLtest");
        assertTrue(playlistOnlyInfo.isPlaylist());

        // Regular videos should not be playlists
        UrlInfo videoInfo = YtDlpUrlUtils.analyzeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertFalse(videoInfo.isPlaylist());
        assertEquals(Platform.YOUTUBE, videoInfo.getPlatform());

        // Generic playlist detection
        UrlInfo genericPlaylist = YtDlpUrlUtils.analyzeUrl("https://example.com/playlist?id=123");
        assertTrue(genericPlaylist.isPlaylist());
    }

    @Test
    @DisplayName("Should detect live streams correctly")
    void testLiveStreamDetection() {
        UrlInfo liveInfo1 = YtDlpUrlUtils.analyzeUrl("https://www.youtube.com/watch?v=live123");
        assertTrue(liveInfo1.isLiveStream());

        UrlInfo liveInfo2 = YtDlpUrlUtils.analyzeUrl("https://www.twitch.tv/streamer");
        assertTrue(liveInfo2.isLiveStream());

        UrlInfo liveInfo3 = YtDlpUrlUtils.analyzeUrl("https://example.com/live/stream123");
        assertTrue(liveInfo3.isLiveStream());

        UrlInfo regularInfo = YtDlpUrlUtils.analyzeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertFalse(regularInfo.isLiveStream());
    }

    @ParameterizedTest
    @CsvSource({
        "YOUTUBE, best, 'bestvideo+bestaudio/best'",
        "YOUTUBE, audio, 'bestaudio/best'",
        "YOUTUBE, 720p, 'bestvideo[height<=720]+bestaudio/best[height<=720]'",
        "YOUTUBE, 1080p, 'bestvideo[height<=1080]+bestaudio/best[height<=1080]'",
        "TWITCH, best, 'best'",
        "SOUNDCLOUD, best, 'bestaudio/best'",
        "TIKTOK, best, 'best'",
        "GENERIC, audio, 'bestaudio/best'"
    })
    @DisplayName("Should suggest correct formats for platforms")
    void testGetSuggestedFormat(Platform platform, String quality, String expectedFormat) {
        assertEquals(expectedFormat, YtDlpUrlUtils.getSuggestedFormat(platform, quality));
    }

    @Test
    @DisplayName("Should handle null quality in format suggestion")
    void testGetSuggestedFormatNullQuality() {
        String format = YtDlpUrlUtils.getSuggestedFormat(Platform.YOUTUBE, null);
        assertEquals("bestvideo+bestaudio/best", format);
    }

    @ParameterizedTest
    @CsvSource({
        "YOUTUBE, true",
        "SOUNDCLOUD, true",
        "VIMEO, true",
        "DAILYMOTION, true",
        "TWITCH, false",
        "TIKTOK, false",
        "INSTAGRAM, false",
        "UNKNOWN, false"
    })
    @DisplayName("Should correctly identify audio extraction support")
    void testSupportsAudioExtraction(Platform platform, boolean expectedSupport) {
        assertEquals(expectedSupport, YtDlpUrlUtils.supportsAudioExtraction(platform));
    }

    @ParameterizedTest
    @CsvSource({
        "YOUTUBE, true",
        "VIMEO, true",
        "DAILYMOTION, false",
        "TWITCH, false",
        "TIKTOK, false",
        "SOUNDCLOUD, false",
        "UNKNOWN, false"
    })
    @DisplayName("Should correctly identify subtitle support")
    void testSupportsSubtitles(Platform platform, boolean expectedSupport) {
        assertEquals(expectedSupport, YtDlpUrlUtils.supportsSubtitles(platform));
    }

    @ParameterizedTest
    @CsvSource({
        "YOUTUBE, YouTube",
        "YOUTUBE_PLAYLIST, YouTube Playlist",
        "VIMEO, Vimeo",
        "DAILYMOTION, Dailymotion",
        "TWITCH, Twitch",
        "TIKTOK, TikTok",
        "INSTAGRAM, Instagram",
        "FACEBOOK, Facebook",
        "TWITTER, Twitter/X",
        "REDDIT, Reddit",
        "SOUNDCLOUD, SoundCloud",
        "GENERIC, Generic Video Site",
        "UNKNOWN, Unknown"
    })
    @DisplayName("Should provide correct platform display names")
    void testGetPlatformDisplayName(Platform platform, String expectedName) {
        assertEquals(expectedName, YtDlpUrlUtils.getPlatformDisplayName(platform));
    }

    @Test
    @DisplayName("Should handle domain extraction correctly")
    void testDomainExtraction() {
        UrlInfo youtubeInfo = YtDlpUrlUtils.analyzeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals("www.youtube.com", youtubeInfo.getDomain());

        UrlInfo vimeoInfo = YtDlpUrlUtils.analyzeUrl("https://vimeo.com/123456789");
        assertEquals("vimeo.com", vimeoInfo.getDomain());

        UrlInfo shortUrlInfo = YtDlpUrlUtils.analyzeUrl("https://youtu.be/dQw4w9WgXcQ");
        assertEquals("youtu.be", shortUrlInfo.getDomain());
    }

    @Test
    @DisplayName("Should extract various video IDs correctly")
    void testVideoIdExtraction() {
        // Vimeo ID
        UrlInfo vimeoInfo = YtDlpUrlUtils.analyzeUrl("https://vimeo.com/123456789");
        assertEquals("123456789", vimeoInfo.getVideoId());

        // Dailymotion ID
        UrlInfo dailymotionInfo = YtDlpUrlUtils.analyzeUrl("https://www.dailymotion.com/video/x7abc123");
        assertEquals("x7abc123", dailymotionInfo.getVideoId());

        // Twitch ID
        UrlInfo twitchInfo = YtDlpUrlUtils.analyzeUrl("https://www.twitch.tv/videos/123456789");
        assertEquals("123456789", twitchInfo.getVideoId());

        UrlInfo twitchStreamInfo = YtDlpUrlUtils.analyzeUrl("https://www.twitch.tv/streamer");
        assertEquals("streamer", twitchStreamInfo.getVideoId());
    }

    @Test
    @DisplayName("Should handle edge cases in URL analysis")
    void testUrlAnalysisEdgeCases() {
        // URL with fragment
        UrlInfo fragmentInfo = YtDlpUrlUtils.analyzeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ#t=30s");
        assertEquals(Platform.YOUTUBE, fragmentInfo.getPlatform());
        assertEquals("dQw4w9WgXcQ", fragmentInfo.getVideoId());

        // URL with port
        UrlInfo portInfo = YtDlpUrlUtils.analyzeUrl("https://www.youtube.com:443/watch?v=dQw4w9WgXcQ");
        assertEquals(Platform.YOUTUBE, portInfo.getPlatform());

        // URL with multiple parameters
        UrlInfo multiParamInfo = YtDlpUrlUtils.analyzeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s&list=PLtest&index=1");
        assertEquals(Platform.YOUTUBE_PLAYLIST, multiParamInfo.getPlatform());
        assertEquals("dQw4w9WgXcQ", multiParamInfo.getVideoId());
        assertTrue(multiParamInfo.isPlaylist());
    }

    @Test
    @DisplayName("Should handle case insensitive domain matching")
    void testCaseInsensitiveDomainMatching() {
        UrlInfo upperCaseInfo = YtDlpUrlUtils.analyzeUrl("HTTPS://WWW.YOUTUBE.COM/WATCH?V=dQw4w9WgXcQ");
        assertEquals(Platform.YOUTUBE, upperCaseInfo.getPlatform());

        UrlInfo mixedCaseInfo = YtDlpUrlUtils.analyzeUrl("https://Www.YouTube.Com/watch?v=dQw4w9WgXcQ");
        assertEquals(Platform.YOUTUBE, mixedCaseInfo.getPlatform());
    }

    @Test
    @DisplayName("UrlInfo toString should provide meaningful representation")
    void testUrlInfoToString() {
        UrlInfo urlInfo = YtDlpUrlUtils.analyzeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        String toString = urlInfo.toString();

        assertNotNull(toString);
        assertTrue(toString.contains("YOUTUBE"));
        assertTrue(toString.contains("dQw4w9WgXcQ"));
        assertTrue(toString.contains("www.youtube.com"));
        assertTrue(toString.contains("false")); // isPlaylist
    }

    @Test
    @DisplayName("Should detect video file extensions correctly")
    void testVideoFileExtensionDetection() {
        assertTrue(YtDlpUrlUtils.isSupported("https://example.com/video.mp4"));
        assertTrue(YtDlpUrlUtils.isSupported("https://example.com/movie.webm"));
        assertTrue(YtDlpUrlUtils.isSupported("https://example.com/clip.mkv"));
        assertTrue(YtDlpUrlUtils.isSupported("https://example.com/file.avi"));
        assertTrue(YtDlpUrlUtils.isSupported("https://example.com/video.mov"));
        assertTrue(YtDlpUrlUtils.isSupported("https://example.com/stream.m4v"));

        // With query parameters
        assertTrue(YtDlpUrlUtils.isSupported("https://example.com/video.mp4?quality=hd"));
        assertTrue(YtDlpUrlUtils.isSupported("https://example.com/video.mp4#fragment"));

        // Should not match non-video extensions
        assertFalse(YtDlpUrlUtils.isSupported("https://example.com/document.pdf"));
        assertFalse(YtDlpUrlUtils.isSupported("https://example.com/image.jpg"));
        assertFalse(YtDlpUrlUtils.isSupported("https://example.com/audio.mp3"));
    }

    @Test
    @DisplayName("Should detect adult content domains correctly")
    void testAdultContentDomainDetection() {
        assertTrue(YtDlpUrlUtils.isSupported("https://www.pornhub.com/view_video.php?viewkey=123"));
        assertTrue(YtDlpUrlUtils.isSupported("https://www.xvideos.com/video123/title"));
        assertTrue(YtDlpUrlUtils.isSupported("https://xhamster.com/videos/title-123"));

        UrlInfo adultInfo = YtDlpUrlUtils.analyzeUrl("https://www.pornhub.com/view_video.php?viewkey=123");
        assertEquals(Platform.GENERIC, adultInfo.getPlatform());
        assertEquals("www.pornhub.com", adultInfo.getDomain());
    }

    @Test
    @DisplayName("Should handle international domains correctly")
    void testInternationalDomains() {
        // BBC variations
        assertTrue(YtDlpUrlUtils.isSupported("https://www.bbc.co.uk/iplayer/episode/123"));
        assertTrue(YtDlpUrlUtils.isSupported("https://bbc.com/news/video/123"));

        UrlInfo bbcInfo = YtDlpUrlUtils.analyzeUrl("https://www.bbc.co.uk/iplayer/episode/123");
        assertEquals(Platform.GENERIC, bbcInfo.getPlatform());
        assertEquals("www.bbc.co.uk", bbcInfo.getDomain());
    }
}
