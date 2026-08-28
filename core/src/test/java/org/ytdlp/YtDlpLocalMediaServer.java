package org.ytdlp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/**
 * Hermetic media source for yt-dlp tests: a local HTTP server serving a
 * small real MP4 fixture as {@code video.mp4}. yt-dlp's generic extractor
 * downloads such direct media URLs without any platform access, so
 * download/progress/format-parsing tests stay hermetic while still
 * exercising the full pipeline (including the ffmpeg metadata
 * postprocessor engaged by the default settings). The dispatcher honors
 * Range requests (the aria2c external downloader issues them) and can
 * throttle the body so a download stays active long enough for task state
 * assertions.
 */
final class YtDlpLocalMediaServer implements AutoCloseable {

    static final String MEDIA_PATH = "/video.mp4";
    private static final String FIXTURE_RESOURCE = "/media/ytdlp-test-video.mp4";

    private final MockWebServer server;

    private YtDlpLocalMediaServer(byte[] content, long bytesPerSecond) throws IOException {
        this.server = new MockWebServer();
        this.server.setDispatcher(new MediaDispatcher(content, bytesPerSecond));
        this.server.start();
    }

    static YtDlpLocalMediaServer start() throws IOException {
        return new YtDlpLocalMediaServer(loadFixture(), 0);
    }

    static YtDlpLocalMediaServer startThrottled(long bytesPerSecond) throws IOException {
        return new YtDlpLocalMediaServer(loadFixture(), bytesPerSecond);
    }

    private static byte[] loadFixture() {
        try (InputStream in = YtDlpLocalMediaServer.class.getResourceAsStream(FIXTURE_RESOURCE);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) {
                throw new IOException("test media fixture not found on the classpath: " + FIXTURE_RESOURCE);
            }
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load test media fixture", e);
        }
    }

    String mediaUrl() {
        return server.url(MEDIA_PATH).toString();
    }

    int requestCount() {
        return server.getRequestCount();
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }

    private static final class MediaDispatcher extends Dispatcher {

        private final byte[] content;
        private final long bytesPerSecond;

        MediaDispatcher(byte[] content, long bytesPerSecond) {
            this.content = content;
            this.bytesPerSecond = bytesPerSecond;
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath() == null ? "" : request.getPath();
            if (!path.startsWith(MEDIA_PATH)) {
                return new MockResponse().setResponseCode(404);
            }

            String range = request.getHeader("Range");
            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring("bytes=".length());
                int dash = spec.indexOf('-');
                if (dash > 0) {
                    try {
                        int from = Integer.parseInt(spec.substring(0, dash));
                        String upperRaw = spec.substring(dash + 1);
                        int upper = upperRaw.isEmpty() ? content.length - 1 : Integer.parseInt(upperRaw);
                        upper = Math.min(upper, content.length - 1);
                        if (from >= 0 && from <= upper) {
                            byte[] slice = Arrays.copyOfRange(content, from, upper + 1);
                            return mediaResponse(slice)
                                    .setResponseCode(206)
                                    .setHeader("Content-Range",
                                            "bytes " + from + "-" + upper + "/" + content.length);
                        }
                    } catch (NumberFormatException e) {
                        // Fall through to the full-body response
                    }
                }
            }

            return mediaResponse(content)
                    .setResponseCode(200)
                    .setHeader("Accept-Ranges", "bytes");
        }

        private MockResponse mediaResponse(byte[] body) {
            MockResponse response = new MockResponse()
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Length", String.valueOf(body.length))
                    .setBody(new Buffer().write(body));
            if (bytesPerSecond > 0) {
                response.throttleBody(bytesPerSecond, 1, TimeUnit.SECONDS);
            }
            return response;
        }
    }
}
