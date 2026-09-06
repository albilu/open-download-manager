package org.ytdlp;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The progress parser must prefer EXACT byte counts from the custom
 * --progress-template suffix (the legacy "[download] 42.3% of ~10.00MiB"
 * output only carries rounded values — deriving downloadedBytes from
 * percent x total drifted), and keep parsing legacy lines from older
 * yt-dlp versions.
 */
@DisplayName("yt-dlp progress parsing: exact template bytes + legacy fallback")
class YtDlpProgressParseTest {

    private static final class Capture implements YtDlpClient.ProgressCallback {
        final AtomicReference<Float> percent = new AtomicReference<>();
        final AtomicLong downloaded = new AtomicLong(-1);
        final AtomicLong total = new AtomicLong(-1);
        final AtomicReference<Float> speed = new AtomicReference<>();

        @Override
        public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speedBps) {
            percent.set(percentage);
            downloaded.set(downloadedBytes);
            total.set(totalBytes);
            speed.set(speedBps);
        }

        @Override
        public void onStart(String filename) {
        }

        @Override
        public void onComplete(String filename) {
        }

        @Override
        public void onError(String error) {
        }
    }

    @Test
    @DisplayName("Template line yields exact downloaded/total bytes")
    void exactBytesFromTemplate() {
        YtDlpClient client = new YtDlpClient("yt-dlp");
        Capture capture = new Capture();

        // 4,718,592 of 10,485,760 bytes = exactly 45% — the legacy line
        // would have derived 10MiB * 42.3% = 4,430,307 (wrong)
        client.parseProgressForTest(
                "[download]  45.0% of ~10.00MiB at 1.50MiB/s |odmbytes|4718592|10485760",
                capture);

        assertEquals(4_718_592L, capture.downloaded.get(), "exact downloaded bytes");
        assertEquals(10_485_760L, capture.total.get(), "exact total bytes");
        assertEquals(45.0f, capture.percent.get(), 0.01f, "percent derived from exact bytes");
        // 1.50MiB/s = 1,572,864 B/s
        assertEquals(1_572_864f, capture.speed.get(), 1f, "speed parsed from the prefix");
    }

    @Test
    @DisplayName("Template line with unknown total (NA) reports total 0")
    void unknownTotalTemplate() {
        YtDlpClient client = new YtDlpClient("yt-dlp");
        Capture capture = new Capture();

        client.parseProgressForTest(
                "[download]  12.3% of ~N/A at 512.00KiB/s |odmbytes|524288|NA",
                capture);

        assertEquals(524_288L, capture.downloaded.get());
        assertEquals(0L, capture.total.get(), "unknown estimate must surface as 0, not a parse error");
    }

    @Test
    void fractionalEstimatePreservesExactDownloadedBytes() {
        YtDlpClient client = new YtDlpClient("yt-dlp");
        try {
            Capture capture = new Capture();
            client.parseProgressForTest(
                    "[download] 42.3% of ~1.00MiB at 1.00MiB/s |odmbytes|471859|1048576.5",
                    capture);
            assertEquals(471_859, capture.downloaded.get());
            assertEquals(1_048_576, capture.total.get());
        } finally {
            client.shutdown();
        }
    }

    @Test
    @DisplayName("Legacy progress line (older yt-dlp) still parses")
    void legacyLineStillParses() {
        YtDlpClient client = new YtDlpClient("yt-dlp");
        Capture capture = new Capture();

        client.parseProgressForTest("[download]  42.3% of ~10.00MiB at 1.23MiB/s", capture);

        assertEquals(10 * 1024 * 1024L, capture.total.get());
        assertEquals((long) (10 * 1024 * 1024L * 42.3 / 100.0), capture.downloaded.get());
        assertEquals(42.3f, capture.percent.get(), 0.01f);
    }
}
