package org.ytdlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("YtDlpSettings property map")
class YtDlpSettingsMapTest {

    @Test
    @DisplayName("defaults produce a baseline map without optional flags")
    void defaultMap() {
        YtDlpSettings settings = new YtDlpSettings();
        Map<String, String> map = settings.toMap();

        assertFalse(map.containsKey("ytdlp.format"),
                "automatic format selection should emit no override");
        assertFalse(map.containsKey("ytdlp.output-template"), "no template set by default");
        assertFalse(map.containsKey("ytdlp.write-thumbnail"), "thumbnail file off by default");
        assertFalse(map.containsKey("ytdlp.embed-thumbnail"), "thumbnail embedding off by default");
        assertFalse(map.containsKey("ytdlp.embed-metadata"), "metadata embedding off by default");
        assertFalse(map.containsKey("ytdlp.fragment-retries"),
                "zero leaves yt-dlp's fragment retry policy intact");
        assertEquals("true", map.get("ytdlp.skip-unavailable-fragments"));
        assertFalse(map.containsKey("ytdlp.ignore-errors"));
    }

    @Test
    @DisplayName("every configured option is reflected in the map")
    void configuredMap() {
        YtDlpSettings settings = new YtDlpSettings()
                .setFormat("worst")
                .setOutputTemplate("%(title)s.%(ext)s")
                .setWriteThumbnail(true)
                .setEmbedThumbnail(true)
                .setEmbedSubs(true)
                .setWriteAutoSubs(true)
                .setWriteSubtitles(true)
                .setSubtitleLanguages(List.of("en", "de"))
                .setExtractAudio(true)
                .setAudioFormat("opus")
                .setAudioQuality("160")
                .setLimitRate(true)
                .setRateLimit(500)
                .setSkipUnavailableFragments(false)
                .setIgnoreErrors(false)
                .setNoPlaylist(true)
                .setPlaylistEnd(true)
                .setPlaylistItems(3)
                .setContainerProfile(YtDlpSettings.ContainerProfile.MKV)
                .setSponsorBlockMode(YtDlpSettings.SponsorBlockMode.MARK)
                .setSponsorBlockCategories("sponsor,intro")
                .setGeoBypass(true)
                .setCookieFile("/tmp/cookies.txt")
                .setVerboseOutput(true)
                .setUseAria2c(true);

        Map<String, String> map = settings.toMap();
        assertEquals("worst", map.get("ytdlp.format"));
        assertEquals("%(title)s.%(ext)s", map.get("ytdlp.output-template"));
        assertEquals("true", map.get("ytdlp.write-thumbnail"));
        assertEquals("true", map.get("ytdlp.embed-thumbnail"));
        assertEquals("true", map.get("ytdlp.embed-subs"));
        assertEquals("true", map.get("ytdlp.write-auto-subs"));
        assertEquals("true", map.get("ytdlp.write-subs"));
        assertEquals("en,de", map.get("ytdlp.sub-langs"));
        assertEquals("true", map.get("ytdlp.extract-audio"));
        assertEquals("opus", map.get("ytdlp.audio-format"));
        assertEquals("160", map.get("ytdlp.audio-quality"));
        assertEquals("500K", map.get("ytdlp.limit-rate"));
        assertNull(map.get("ytdlp.skip-unavailable-fragments"),
                "a disabled flag must be absent, not 'false'");
        assertNull(map.get("ytdlp.ignore-errors"));
        assertEquals("true", map.get("ytdlp.no-playlist"));
        assertEquals("1:3", map.get("ytdlp.playlist-items"));
        assertEquals("mkv", map.get("ytdlp.container-profile"));
        assertEquals("sponsor,intro", map.get("ytdlp.sponsorblock-mark"));
        assertEquals("true", map.get("ytdlp.geo-bypass"));
        assertEquals("/tmp/cookies.txt", map.get("ytdlp.cookies"));
        assertEquals("true", map.get("ytdlp.verbose"));
        assertEquals("true", map.get("use-aria2c"));
    }

    @Test
    @DisplayName("limit-rate without a positive rate emits nothing")
    void limitRateRequiresPositiveRate() {
        YtDlpSettings settings = new YtDlpSettings().setLimitRate(true).setRateLimit(0);
        assertFalse(settings.toMap().containsKey("ytdlp.limit-rate"));
    }

    @Test
    @DisplayName("browser cookie source is serialized unless a cookie file overrides it")
    void browserCookiePrecedence() {
        YtDlpSettings settings = new YtDlpSettings()
                .setBrowserCookieSource(YtDlpSettings.BrowserCookieSource.FIREFOX)
                .setBrowserCookieProfile("work");

        assertEquals("firefox:work", settings.toMap().get("ytdlp.cookies-from-browser"));

        settings.setCookieFile("/tmp/cookies.txt");
        assertEquals("/tmp/cookies.txt", settings.toMap().get("ytdlp.cookies"));
        assertFalse(settings.toMap().containsKey("ytdlp.cookies-from-browser"));
    }

    @Test
    @DisplayName("playlist expressions and SponsorBlock categories are validated")
    void selectionValidation() {
        YtDlpSettings settings = new YtDlpSettings().setPlaylistItemSpec("1:10, 12, 15:20:2");
        assertEquals("1:10,12,15:20:2", settings.getPlaylistItemSpec());
        assertEquals("1:10,12,15:20:2", settings.toMap().get("ytdlp.playlist-items"));

        settings.setPlaylistItemSpec("-1,-5::-2");
        assertEquals("-1,-5::-2", settings.getPlaylistItemSpec());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> settings.setPlaylistItemSpec("1,broken"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> settings.setPlaylistItemSpec("1:5:0"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> settings.setSponsorBlockCategories("sponsor,unknown"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new YtDlpSettings()
                        .setSponsorBlockCategories("chapter")
                        .setSponsorBlockMode(YtDlpSettings.SponsorBlockMode.REMOVE));
    }
}
