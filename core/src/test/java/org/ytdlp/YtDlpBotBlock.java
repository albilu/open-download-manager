package org.ytdlp;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Detects YouTube's anonymous-access bot block ("Sign in to confirm you're
 * not a bot") in yt-dlp output so WAN-dependent tests can abort as
 * assumption skips instead of reporting product defects. Only the
 * distinctive block text triggers a skip; every other failure surfaces as
 * a real test error.
 */
final class YtDlpBotBlock {

    private static final String SKIP_REASON = "YouTube bot-block in this environment";
    private static final long PROBE_TIMEOUT_SECONDS = 45;
    private static final int PROBE_OUTPUT_CAP = 64_000;

    /** Memoized probe so a forked test class probes the platform once. */
    private static volatile ProbeResult cachedProbe;

    private record ProbeResult(String url, int exitCode, String output) {
    }

    private YtDlpBotBlock() {
    }

    /**
     * Returns true when the text carries YouTube's bot-check indication.
     * Matches the curly (U+2019) and straight apostrophe phrasings yt-dlp
     * has emitted, case-insensitively.
     */
    static boolean isBotBlockIndication(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.replace('\u2019', '\'').toLowerCase(Locale.ROOT);
        return normalized.contains("sign in to confirm you're not a bot")
                || normalized.contains("sign in to confirm you are not a bot");
    }

    /**
     * A probe counts as bot-blocked only when yt-dlp failed AND the output
     * carries the block text; a failing probe with any other error must
     * not skip the test.
     */
    static boolean isBotBlockedProbeResult(int exitCode, String output) {
        return exitCode != 0 && isBotBlockIndication(output);
    }

    /**
     * Aborts the current test as an assumption skip when the given probe
     * result is the specific external bot block.
     */
    static void assumePlatformAccessible(int exitCode, String output) {
        if (isBotBlockedProbeResult(exitCode, output)) {
            assumeTrue(false, SKIP_REASON + ": " + firstErrorLine(output));
        }
    }

    /**
     * Probes the platform once per forked test class with a metadata-only
     * yt-dlp invocation and aborts the current test as an assumption skip
     * when YouTube answers with the bot block. Probe failures that are not
     * the block (network down, tool hang, unrelated extractor error) do
     * not skip; the test itself must then fail on its own merits.
     */
    static void assumeNotBotBlocked(String url) {
        ProbeResult probe = cachedProbeFor(url);
        assumePlatformAccessible(probe.exitCode(), probe.output());
    }

    private static ProbeResult cachedProbeFor(String url) {
        ProbeResult cached = cachedProbe;
        if (cached != null && cached.url().equals(url)) {
            return cached;
        }
        ProbeResult result = probe(url);
        cachedProbe = result;
        return result;
    }

    private static ProbeResult probe(String url) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp", "--dump-json", "--no-download", "--no-warnings", url);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                // Inconclusive probe: never skip because the probe hung
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String output = drain(process);
            int exitCode = finished ? process.exitValue() : -1;
            return new ProbeResult(url, exitCode, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(url, -1, "");
        } catch (IOException e) {
            return new ProbeResult(url, -1, "");
        }
    }

    private static String drain(Process process) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                if (output.length() > PROBE_OUTPUT_CAP) {
                    break;
                }
            }
        } catch (IOException e) {
            // Partial output is fine for detection purposes
        }
        return output.toString();
    }

    private static String firstErrorLine(String output) {
        return output.lines()
                .filter(line -> line.contains("ERROR:"))
                .findFirst()
                .map(line -> line.length() > 200 ? line.substring(0, 200) + "..." : line)
                .orElse("yt-dlp bot-check indication");
    }
}
