package org.jackett;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;

class JackettClientTest {
    @TempDir Path directory;
    MockWebServer server;
    JackettClient client;
    Path config;

    @BeforeEach void setup() throws Exception {
        server = new MockWebServer(); server.start();
        config = Files.writeString(directory.resolve("ServerConfig.json"), "{\"APIKey\":\"private-test-key\"}");
        client = new JackettClient(server.getPort(), config);
    }

    @AfterEach void close() throws Exception { server.close(); }
    private static MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }

    @Test void searchEncodesSelectionAndUsesJackettLeecherAndStatusSemantics() throws Exception {
        server.enqueue(json("""
                {"Results":[{"Title":"Linux image","Size":5368709120,"Seeders":21,"Peers":7,
                  "PublishDate":"2026-09-06T12:30:00+02:00","Tracker":"Public",
                  "MagnetUri":"magnet:?xt=urn:btih:0123456789012345678901234567890123456789"}],
                 "Indexers":[{"Name":"Public","Status":2},{"Name":"Broken","Status":1,"Error":"timeout"}]}
                """));
        var found = client.search("linux & café", 4000, Set.of("linuxtracker"));
        var row = found.results().getFirst();
        assertEquals(5368709120L, row.size()); assertEquals(21, row.seeders()); assertEquals(7, row.leechers());
        assertEquals(Instant.parse("2026-09-06T10:30:00Z"), row.published());
        assertEquals(List.of("Broken: timeout"), found.warnings());
        RecordedRequest request = server.takeRequest();
        assertEquals("linux & café", request.getRequestUrl().queryParameter("query"));
        assertEquals("linuxtracker", request.getRequestUrl().queryParameter("Tracker[]"));
        assertEquals("4000", request.getRequestUrl().queryParameter("Category[]"));
        assertEquals("private-test-key", request.getRequestUrl().queryParameter("apikey"));
    }

    @Test void emptySelectionsAndInvalidIdsNeverBecomeAnAggregateSearch() {
        assertThrows(IllegalArgumentException.class, () -> client.search("linux", 0, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> client.search("linux", 0, Set.of("../all")));
        assertThrows(IllegalArgumentException.class, () -> client.search(" ", 0, Set.of("linuxtracker")));
        assertEquals(0, server.getRequestCount());
    }

    @Test void apiKeyIsReadAgainAfterRegenerationAndMalformedConfigDoesNotLeakIt() throws Exception {
        server.enqueue(json("{\"Results\":[],\"Indexers\":[]}"));
        Files.writeString(config, "{\"APIKey\":\"new-secret\"}");
        client.search("linux", 0, Set.of("linuxtracker"));
        assertEquals("new-secret", server.takeRequest().getRequestUrl().queryParameter("apikey"));
        Files.writeString(config, "{\"APIKey\":\"new-secret");
        IOException error = assertThrows(IOException.class, client::apiKey);
        assertFalse(error.toString().contains("new-secret"));
        assertNull(error.getCause());
        Files.delete(config);
        assertFalse(client.isReady());
    }

    @Test void administrationLogsInAndOnlyListsPublicIndexers() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/UI/Login"));
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/UI/Dashboard")
                .setHeader("Set-Cookie", "Jackett=session; Path=/; HttpOnly"));
        server.enqueue(json("""
                [{"id":"private","name":"Private","type":"private","configured":true},
                 {"id":"linux","name":"Linux","type":"public","configured":true,"last_error":"",
                   "caps":[{"ID":"4000","Name":"PC"}]},
                 {"id":"new","name":"New","type":"public","configured":false}]
                """));
        var indexers = client.publicIndexers();
        assertEquals(List.of("linux", "new"), indexers.stream().map(JackettClient.Indexer::id).toList());
        assertEquals(4000, indexers.getFirst().categories().getFirst().id());
        server.takeRequest();
        assertEquals("/UI/Login?cookiesChecked=1", server.takeRequest().getPath());
        assertTrue(server.takeRequest().getHeader("Cookie").contains("Jackett=session"));
        GlobalSettings settings = new GlobalSettings();
        assertEquals(Set.of("linux"), JackettSettings.selectedIndexers(settings, indexers));
        settings.setProperty(JackettSettings.INDEXERS, "");
        assertTrue(JackettSettings.selectedIndexers(settings, indexers).isEmpty());
        settings.setProperty(JackettSettings.INDEXERS, "private,new,linux");
        assertEquals(Set.of("linux"), JackettSettings.selectedIndexers(settings, indexers));
    }

    @Test void configurationAndTestsUseJackettMethodsAndPreserveTypedFields() throws Exception {
        String fields = "[{\"id\":\"enabled\",\"type\":\"inputbool\",\"value\":true}]";
        server.enqueue(json(JackettClient.JSON.writeValueAsString(fields)));
        ArrayNode config = client.configuration("linux");
        assertTrue(config.get(0).path("value").asBoolean());
        server.enqueue(new MockResponse().setResponseCode(204)); client.configure("linux", config);
        server.enqueue(new MockResponse().setResponseCode(204)); client.test("linux");
        server.enqueue(new MockResponse().setResponseCode(204)); client.unconfigure("linux");
        assertTrue(server.takeRequest().getPath().startsWith("/api/v2.0/indexers/linux/config?"));
        RecordedRequest post = server.takeRequest();
        assertEquals("POST", post.getMethod()); assertEquals(config, JackettClient.JSON.readTree(post.getBody().readUtf8()));
        RecordedRequest test = server.takeRequest();
        assertEquals("POST", test.getMethod()); assertTrue(test.getPath().contains("/linux/test?"));
        RecordedRequest removal = server.takeRequest();
        assertEquals("DELETE", removal.getMethod());
        assertEquals("/api/v2.0/indexers/linux", removal.getRequestUrl().encodedPath());
        assertEquals("private-test-key", removal.getRequestUrl().queryParameter("apikey"));
    }

    @Test void versionComesFromTheRunningServerAndRejectsUnexpectedValues() throws Exception {
        server.enqueue(json("{\"app_version\":\"v0.24.2541\",\"api_key\":\"private-test-key\"}"));
        assertEquals("0.24.2541", client.version());
        assertTrue(server.takeRequest().getPath().startsWith("/api/v2.0/server/config?"));
        server.enqueue(json("{\"app_version\":\"private-test-key\"}"));
        assertFalse(assertThrows(IOException.class, client::version).getMessage().contains("private-test-key"));
    }

    @Test void descriptorPreviewDoesNotStageAnythingAndForeignLinksAreRejected() throws Exception {
        server.enqueue(new MockResponse().setBody("d4:infod6:lengthi12e4:name9:linux.isoe e".replace(" ", "")));
        var row = new JackettClient.TorrentResult("Linux", 12, 2, 1, null, "Linux", local("/dl/test"), null);
        var source = client.resolve(row);
        assertNull(source.magnet()); assertEquals("linux.iso", source.files().getFirst().path());
        assertEquals(12, source.files().getFirst().length());
        assertThrows(IOException.class, () -> client.resolve(new JackettClient.TorrentResult("Bad", 0, 0, 0,
                null, "", URI.create("http://example.org/?apikey=private-test-key"), null)));
        assertEquals(1, server.getRequestCount());
    }

    @Test void redirectsSupportMagnetsWithoutSendingCookiesOrKeysToOtherServers() throws Exception {
        String magnet = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789";
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", magnet));
        var row = new JackettClient.TorrentResult("Linux", 0, 0, 0, null, "", local("/dl/test"), null);
        assertEquals(URI.create(magnet), client.resolve(row).magnet());
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://example.org/torrent"));
        assertThrows(IOException.class, () -> client.resolve(row));
        assertEquals(2, server.getRequestCount());
    }

    @Test void failureMessagesAndInvalidPayloadsAreSafeAndActionable() {
        server.enqueue(json("{\"error\":\"Failed apikey=private-test-key&x=1\"}").setResponseCode(500));
        IOException error = assertThrows(IOException.class, () -> client.test("linux"));
        assertFalse(error.getMessage().contains("private-test-key")); assertTrue(error.getMessage().contains("500"));
        server.enqueue(json("[]"));
        assertThrows(IOException.class, () -> client.search("linux", 0, Set.of("linux")));
        server.enqueue(new MockResponse().setBody("not a torrent"));
        assertThrows(IOException.class, () -> client.resolve(new JackettClient.TorrentResult("", 0, 0, 0,
                null, "", local("/dl/test"), null)));
    }

    private URI local(String path) { return URI.create("http://127.0.0.1:" + server.getPort() + path); }
}
