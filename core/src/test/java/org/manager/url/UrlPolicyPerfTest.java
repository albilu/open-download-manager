package org.manager.url;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.manager.perf.PerfReporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmarks the clipboard hot path: free-text URL extraction and single-URL
 * admission. All inputs are synthetic and structural only (no DNS/network).
 */
@Tag("performance")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("URL policy performance")
class UrlPolicyPerfTest {

    private static String thousandUrlText() {
        StringBuilder sb = new StringBuilder(64_000);
        for (int i = 0; i < 1000; i++) {
            sb.append("file").append(i).append(" https://example").append(i).append(".com/file")
                    .append(i).append(".zip ");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("Extract 1000 URLs from mixed text")
    void extractThousandUrls() {
        String text = thousandUrlText();
        // Warm up the regex/JIT path.
        for (int i = 0; i < 3; i++) {
            DownloadUrlPolicy.extract(text);
        }
        long start = System.nanoTime();
        List<DownloadUrlPolicy.ValidatedSource> found = DownloadUrlPolicy.extract(text);
        long elapsed = System.nanoTime() - start;

        assertEquals(1000, found.size());
        PerfReporter.report("UrlPolicy", "extract-1000-urls", found.size(), elapsed,
                "textChars=" + text.length());
        // Generous trip wire: a healthy run is tens of ms; 5s catches only collapse.
        assertTrue(elapsed < 5_000_000_000L, "extract took " + elapsed / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("Single URL parse throughput")
    void singleParseThroughput() {
        String input = "https://example.com/downloads/file.zip?token=abc123#section";
        for (int i = 0; i < 1000; i++) {
            DownloadUrlPolicy.parse(input);
        }
        int iterations = 20_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            DownloadUrlPolicy.parse(input);
        }
        long elapsed = System.nanoTime() - start;

        assertTrue(DownloadUrlPolicy.parse(input).isPresent());
        PerfReporter.report("UrlPolicy", "parse-single-url", iterations, elapsed, "");
        assertTrue(elapsed < 10_000_000_000L, "parse loop took " + elapsed / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("Scan text with 1000 invalid tokens plus one valid URL")
    void invalidHeavyScan() {
        StringBuilder sb = new StringBuilder(32_000);
        for (int i = 0; i < 1000; i++) {
            sb.append("not-a-url-").append(i).append(' ');
        }
        sb.append("https://example.com/valid.zip");
        String text = sb.toString();

        long start = System.nanoTime();
        List<DownloadUrlPolicy.ValidatedSource> found = DownloadUrlPolicy.extract(text);
        long elapsed = System.nanoTime() - start;

        assertEquals(1, found.size());
        PerfReporter.report("UrlPolicy", "scan-invalid-heavy", 1001, elapsed, "hits=1");
        assertTrue(elapsed < 5_000_000_000L, "invalid scan took " + elapsed / 1_000_000 + "ms");
    }
}
