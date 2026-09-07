package org.manager.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Pattern;

/**
 * Sanitizes user-supplied "additional options" before they are appended to
 * external-tool command lines. Imported settings files can carry arbitrary
 * option maps, and several tools have execution-capable flags (yt-dlp
 * {@code --exec}, aria2 {@code --on-download-complete}, curl
 * {@code --config}): an unknown key is therefore treated as hostile and
 * dropped, with a warning, instead of passed through.
 *
 * <p>Keys must be plain option names (letters, digits, {@code -}, {@code _});
 * values must not smuggle a new flag (leading {@code --}) or contain line
 * breaks/NUL.
 */
public final class ToolOptionFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolOptionFilter.class);

    /** Tool whose option surface is being filtered. */
    public enum Tool {
        YTDLP, ARIA2, CURL, HTTRACK
    }

    private static final Pattern KEY_SYNTAX = Pattern.compile("%?[A-Za-z][A-Za-z0-9_-]*");

    /**
     * Safe yt-dlp option keys. Deliberately excludes every execution or
     * config-loading flag (exec*, postprocessor-args, downloader-args,
     * config-locations, plugin-dirs, use-postprocessor).
     */
    private static final Set<String> YTDLP_KEYS = Set.of(
            "format", "audio-format", "audio-quality", "merge-output-format",
            "subtitle-langs", "subtitles-format", "subtitles-langs", "write-subs",
            "write-auto-subs", "embed-subs", "embed-thumbnail", "embed-metadata",
            "add-metadata", "add-chapters", "parse-metadata", "convert-subs",
            "playlist-items", "playlist-reverse", "playlist-start", "playlist-end",
            "limit-rate", "retries", "fragment-retries", "buffer-size",
            "concurrent-fragments", "no-part", "no-mtime", "no-check-certificate",
            "no-playlist", "yes-playlist", "ignore-errors", "no-ignore-errors",
            "skip-unavailable-fragments", "abort-on-unavailable-fragment",
            "geo-bypass", "no-geo-bypass", "force-ipv4", "force-ipv6",
            "min-sleep-interval", "max-sleep-interval", "sleep-requests",
            "cookies", "user-agent", "referer", "add-header",
            "age-limit", "restrict-filenames", "windows-filenames",
            "trim-filenames", "encoding", "no-progress", "quiet", "verbose");

    /**
     * Safe aria2 option keys (also used for the proxychains-wrapped aria2).
     * Excludes all on-download-* hooks (shell execution) and rpc options
     * (daemon control).
     */
    private static final Set<String> ARIA2_KEYS = Set.of(
            "max-connection-per-server", "split", "min-split-size", "max-tries",
            "retry-wait", "timeout", "connect-timeout", "max-overall-download-limit",
            "max-download-limit", "max-overall-upload-limit", "max-upload-limit",
            "referer", "user-agent", "header",
            "load-cookies", "check-certificate", "lowest-speed-limit",
            "max-connection", "piece-length", "optimize-concurrent-downloads",
            "file-allocation", "allow-overwrite", "auto-file-renaming",
            "continue", "check-integrity", "dir", "out", "max-file-not-found", "select-file",
            "seed-ratio", "seed-time", "bt-max-peers", "bt-request-peer-speed-limit",
            "bt-detach-seed-only", "bt-enable-lpd", "enable-peer-exchange",
            "bt-require-crypto", "bt-min-crypto-level", "bt-force-encryption",
            "ssh-host-key-md", "follow-torrent", "follow-metalink",
            "metalink-preferred-protocol", "remote-time", "conditional-get");

    /** Safe curl option keys; excludes config/K, output/o, write-out, exec-adjacent flags. */
    private static final Set<String> CURL_KEYS = Set.of(
            "header", "user-agent", "referer", "max-time", "connect-timeout",
            "limit-rate", "retry", "retry-delay", "retry-max-time", "range",
            "keepalive-time", "max-redirs", "compressed", "insecure",
            "silent", "show-error", "location", "location-trusted",
            "time-cond", "continue-at", "speed-time", "speed-limit",
            "proto", "proto-redir", "cookie",
            "upload-rate", "interface", "ipv4", "ipv6");

    /**
     * Safe httrack flag keys (httrack's quirky single-letter flags). The
     * {@code #} filter-command flag and {@code +}/{@code -} filter syntax are
     * excluded; URL filters belong to the settings' filter lists.
     */
    private static final Set<String> HTTRACK_KEYS = Set.of(
            "w", "W", "v", "q", "i", "I", "r", "x", "s", "m", "c", "f", "n",
            "N", "S", "K", "k", "A", "g", "G", "b", "d", "D", "j", "L",
            "a", "u", "%P", "%F", "%L", "%v", "%s", "p", "T", "C", "R", "M",
            "t", "e", "z", "Z", "h", "B", "O", "o", "X", "Y", "E", "%R",
            "%X", "%K", "%c", "%G");

    private ToolOptionFilter() {
    }

    /**
     * Filters an option map down to known-safe keys for the tool.
     *
     * @param tool   the tool the options will be passed to
     * @param option the raw option map (may be null)
     * @return a new ordered map containing only permitted entries
     */
    public static Map<String, String> filter(Tool tool, Map<String, String> option) {
        Map<String, String> result = new LinkedHashMap<>();
        if (option == null || option.isEmpty()) {
            return result;
        }
        Set<String> allowed = switch (tool) {
            case YTDLP -> YTDLP_KEYS;
            case ARIA2 -> ARIA2_KEYS;
            case CURL -> CURL_KEYS;
            case HTTRACK -> HTTRACK_KEYS;
        };
        for (Map.Entry<String, String> entry : option.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            // Engine-neutral bridge values are intentionally stored beside
            // native options; the engine-specific builder has already read
            // them and they are never command-line candidates.
            if (key != null && key.startsWith("odm.")) {
                continue;
            }
            if (key == null || !KEY_SYNTAX.matcher(key).matches() || !allowed.contains(key)) {
                LOGGER.warn("Dropped untrusted " + tool + " option key '" + key
                        + "' (not on the allowlist)");
                continue;
            }
            if (value != null && (!isSafeValue(value)
                    || (tool == Tool.HTTRACK && !isSafeHttrackValue(key, value)))) {
                LOGGER.warn("Dropped " + tool + " option '" + key
                        + "': value attempts flag smuggling or contains line breaks");
                continue;
            }
            result.put(key, value);
        }
        return result;
    }

    private static boolean isSafeValue(String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            return false;
        }
        // A leading -- turns the value into a fresh flag on tools that pass
        // options and values as separate arguments
        return !value.startsWith("-");
    }

    private static boolean isSafeHttrackValue(String key, String value) {
        if (value.isEmpty()) { return true; }
        // HTTrack combines short options: w=Pproxy becomes -wPproxy and
        // executes a second flag. Only text-consuming controls accept text.
        if (Set.of("O", "%F", "%L", "%R", "%X", "%K").contains(key)) { return true; }
        if (key.equals("N") && !Character.isDigit(value.charAt(0))) { return true; }
        return value.matches("[0-9]+(?:[.,:][0-9]+)*");
    }
}
