package org.ytdlp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Unit tests for the YouTube bot-block detector used by the WAN-dependent
 * yt-dlp tests. The matcher must recognize only the distinctive external
 * block text ("Sign in to confirm you're not a bot") and must let every
 * other failure surface as a real test error.
 */
@DisplayName("YtDlp Bot-Block Detection")
class YtDlpBotBlockTest {

    private static final String REAL_BLOCKED_OUTPUT = """
            WARNING: Your yt-dlp version (2026.03.17) is older than 90 days!
            WARNING: [youtube] No supported JavaScript runtime could be found. Only deno is enabled by default.
            WARNING: [youtube] No title found in player responses; falling back to title from initial data.
            ERROR: [youtube] nulrKPsBTi4: Sign in to confirm you\u2019re not a bot. Use --cookies-from-browser or --cookies for the authentication. See  https://github.com/yt-dlp/yt-dlp/wiki/FAQ#how-do-i-pass-cookies-to-yt-dlp  for how to manually pass cookies.
            """;

    private static final String STRAIGHT_APOSTROPHE_OUTPUT = """
            ERROR: [youtube] dQw4w9WgXcQ: Sign in to confirm you're not a bot.
            """;

    private static final String LONG_FORM_OUTPUT = """
            ERROR: [youtube] dQw4w9WgXcQ: Sign in to confirm you are not a bot
            """;

    @Test
    @DisplayName("Should detect the real captured bot-block output with curly apostrophe")
    void shouldDetectRealCapturedBotBlockOutput() {
        assertTrue(YtDlpBotBlock.isBotBlockIndication(REAL_BLOCKED_OUTPUT));
    }

    @Test
    @DisplayName("Should detect bot-block with straight apostrophe")
    void shouldDetectStraightApostropheVariant() {
        assertTrue(YtDlpBotBlock.isBotBlockIndication(STRAIGHT_APOSTROPHE_OUTPUT));
    }

    @Test
    @DisplayName("Should detect the long-form bot-block phrasing")
    void shouldDetectLongFormPhrasing() {
        assertTrue(YtDlpBotBlock.isBotBlockIndication(LONG_FORM_OUTPUT));
    }

    @Test
    @DisplayName("Should detect bot-block case-insensitively")
    void shouldDetectCaseInsensitively() {
        assertTrue(YtDlpBotBlock.isBotBlockIndication("ERROR: SIGN IN TO CONFIRM YOU'RE NOT A BOT"));
    }

    @Test
    @DisplayName("Should not match unrelated yt-dlp errors")
    void shouldNotMatchUnrelatedErrors() {
        assertFalse(YtDlpBotBlock.isBotBlockIndication(
                "ERROR: Unsupported URL: https://example.com/nonexistent-video"));
        assertFalse(YtDlpBotBlock.isBotBlockIndication(
                "ERROR: Unable to download webpage: <urlopen error [Errno -3] Temporary failure in name resolution>"));
        assertFalse(YtDlpBotBlock.isBotBlockIndication(
                "ERROR: [youtube] nulrKPsBTi4: Video unavailable"));
        assertFalse(YtDlpBotBlock.isBotBlockIndication(
                "ERROR: Requested format is not available"));
    }

    @Test
    @DisplayName("Should not match null or empty output")
    void shouldNotMatchNullOrEmpty() {
        assertFalse(YtDlpBotBlock.isBotBlockIndication(null));
        assertFalse(YtDlpBotBlock.isBotBlockIndication(""));
        assertFalse(YtDlpBotBlock.isBotBlockIndication("   "));
    }

    @Test
    @DisplayName("Should not flag successful probe output as bot-block")
    void shouldNotFlagSuccessfulProbe() {
        assertFalse(YtDlpBotBlock.isBotBlockedProbeResult(0, REAL_BLOCKED_OUTPUT.replace("ERROR", "WARNING")));
        assertFalse(YtDlpBotBlock.isBotBlockedProbeResult(0, "{\"id\": \"video\", \"title\": \"video\"}"));
    }

    @Test
    @DisplayName("Should flag failed probe only when the block text is present")
    void shouldFlagFailedProbeOnlyWithBlockText() {
        assertTrue(YtDlpBotBlock.isBotBlockedProbeResult(1, REAL_BLOCKED_OUTPUT));
        assertFalse(YtDlpBotBlock.isBotBlockedProbeResult(1, "ERROR: Unsupported URL: https://example.com/x"));
        assertFalse(YtDlpBotBlock.isBotBlockedProbeResult(1, ""));
    }

    @Test
    @DisplayName("Guard should abort as assumption skip on bot-block output")
    void guardShouldAbortOnBotBlock() {
        TestAbortedException aborted = assertThrows(TestAbortedException.class,
                () -> YtDlpBotBlock.assumePlatformAccessible(1, REAL_BLOCKED_OUTPUT));
        assertTrue(aborted.getMessage().contains("YouTube bot-block"),
                "skip reason must explain the bot-block, was: " + aborted.getMessage());
    }

    @Test
    @DisplayName("Guard must not abort on success or unrelated failure")
    void guardMustNotAbortOtherwise() {
        assertDoesNotThrow(() -> YtDlpBotBlock.assumePlatformAccessible(0, "{\"id\": \"video\"}"));
        assertDoesNotThrow(() -> YtDlpBotBlock.assumePlatformAccessible(
                1, "ERROR: Unsupported URL: https://example.com/nonexistent-video"));
        assertDoesNotThrow(() -> YtDlpBotBlock.assumePlatformAccessible(1, ""));
    }
}
