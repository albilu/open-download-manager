package org.manager.download.handler;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.aria2.Aria2Client;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;

class Aria2CancellationRpcTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedActiveCompletedAndMissingGidsAreRemovedIdempotently(boolean deleteFiles) throws Exception {
        verifyCancellation(deleteFiles, 0);
    }

    @ParameterizedTest
    @CsvSource({"false, 1", "true, 1", "false, 503", "true, 503"})
    void genuineRpcFailureDoesNotDiscardTheRemainingActiveGid(boolean deleteFiles, int failureCode) throws Exception {
        verifyCancellation(deleteFiles, failureCode);
    }

    private void verifyCancellation(boolean deleteFiles, int failureCode) throws Exception {
        var denied = new AtomicBoolean(failureCode != 0);
        var calls = new ConcurrentLinkedQueue<String>();
        var json = new ObjectMapper();
        try (var server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    try {
                        var rpc = json.readTree(request.getBody().readUtf8());
                        String method = rpc.path("method").asText();
                        String gid = rpc.path("params").get(1).asText();
                        if (!rpc.path("params").get(0).asText().equals("token:fixture-secret")) {
                            return new MockResponse().setResponseCode(403);
                        }
                        calls.add(method + ":" + gid);
                        Map<String, Object> response = new java.util.LinkedHashMap<>();
                        response.put("id", rpc.get("id"));
                        response.put("jsonrpc", "2.0");
                        if (denied.get() && gid.equals("completed")) {
                            if (failureCode == 503) { return new MockResponse().setResponseCode(503); }
                            response.put("error", Map.of("code", 1, "message", "Unauthorized"));
                        } else if (method.equals("aria2.tellStatus")) {
                            response.put("result", Map.of("files", List.of()));
                        } else if (gid.equals("missing") || gid.equals("completed")
                                && !method.equals("aria2.removeDownloadResult")) {
                            response.put("error", Map.of("code", 1, "message", "Active Download not found for GID#" + gid));
                        } else {
                            response.put("result", gid);
                        }
                        return new MockResponse().setHeader("Content-Type", "application/json")
                                .setBody(json.writeValueAsString(response));
                    } catch (Exception error) { return new MockResponse().setResponseCode(500); }
                }
            });
            server.start();
            var client = new Aria2Client("aria2c", server.url("/jsonrpc").toString(), "fixture-secret");
            var executor = Executors.newSingleThreadExecutor();
            var poller = Executors.newSingleThreadScheduledExecutor();
            var settings = new GlobalSettings().setDefaultDownloadDirectory(directory);
            var handler = new Aria2DownloadHandler(settings, new DownloadSettingsFactory(settings),
                    executor, client, poller) { { initialized = true; } };
            try {
                var download = new Download(URI.create("http://download.invalid/file.bin"));
                download.setDestination(directory);
                download.setStatus(Download.Status.DOWNLOADING);
                handler.registerTrackedDownload(download, List.of("active", "completed", "missing"));
                if (failureCode != 0) {
                    var error = assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> handler.cancelDownload(download, deleteFiles).get(5, TimeUnit.SECONDS));
                    if (failureCode == 503) {
                        assertTrue(error.getCause().getCause() instanceof java.io.IOException);
                    } else {
                        assertTrue(error.getCause().getCause() instanceof Aria2Client.Aria2RpcException);
                        assertTrue(error.getCause().getCause().getMessage().contains("Unauthorized"));
                    }
                    assertEquals(Download.Status.DOWNLOADING, download.getStatus());
                    assertFalse(calls.stream().anyMatch(call -> call.startsWith("aria2.removeDownloadResult")));
                    denied.set(false);
                    calls.clear();
                }
                handler.cancelDownload(download, deleteFiles).get(5, TimeUnit.SECONDS);
                assertEquals(Download.Status.CANCELED, download.getStatus());
                String removal = deleteFiles ? "aria2.forceRemove:" : "aria2.remove:";
                for (String gid : List.of("active", "completed", "missing")) {
                    assertTrue(calls.contains(removal + gid), gid + " must remain tracked until cancellation succeeds");
                    assertTrue(calls.contains("aria2.removeDownloadResult:" + gid));
                }
                handler.cancelDownload(download, deleteFiles).get(5, TimeUnit.SECONDS);
                assertEquals(Download.Status.CANCELED, download.getStatus());
            } finally {
                handler.shutdown().get(5, TimeUnit.SECONDS);
                executor.shutdownNow();
                poller.shutdownNow();
            }
        }
    }
}
