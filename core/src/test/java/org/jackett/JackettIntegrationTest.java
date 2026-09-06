package org.jackett;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;

/** Opt-in real release check: -Pintegration -Dodm.jackettArchive=/path/to/release.tar.gz. */
class JackettIntegrationTest {
    @TempDir Path directory;

    @Test void officialReleaseInstallsStartsAndServesAuthenticatedConfiguration() throws Exception {
        String archive = System.getProperty("odm.jackettArchive");
        assumeTrue(archive != null, "Supply the official Linux archive to exercise Jackett");
        GlobalSettings settings = new GlobalSettings();
        settings.setProperty(JackettSettings.PORT, Integer.toString(JackettServiceTest.freePort()));
        var tracker = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tracker.createContext("/", exchange -> {
            String response = exchange.getRequestURI().getPath().endsWith(".torrent")
                    ? "d4:infod6:lengthi12e4:name9:linux.iso12:piece lengthi16384e6:pieces20:00000000000000000000ee"
                    : "<html><table><tbody><tr><td class='title'><a href='/details'>Linux fixture</a></td>"
                            + "<td class='download'><a href='/file.torrent'>Torrent</a></td></tr></tbody></table></html>";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        tracker.start();
        JackettClient client;
        try (JackettService service = new JackettService(settings, directory.resolve("bin"), directory.resolve("config"),
                directory.resolve("log"), Duration.ofSeconds(40))) {
            service.install(Path.of(archive));
            // Real Jackett parses a local tracker fixture, making the whole API flow deterministic.
            Files.writeString(directory.resolve("bin/Jackett/Definitions/odmfixture.yml"), """
                    ---
                    id: odmfixture
                    name: ODM Fixture
                    description: Local torrent metadata fixture
                    language: en-US
                    type: public
                    encoding: UTF-8
                    requestDelay: 0
                    links:
                      - http://127.0.0.1:%d/
                    caps:
                      categories:
                        1: PC/ISO
                      modes:
                        search: [q]
                    settings: []
                    search:
                      paths:
                        - path: /
                      rows:
                        selector: tbody tr
                      fields:
                        category:
                          text: 1
                        title:
                          selector: td.title a
                        details:
                          selector: td.title a
                          attribute: href
                        download:
                          selector: td.download a
                          attribute: href
                        size:
                          text: 12
                        seeders:
                          text: 7
                        leechers:
                          text: 3
                        date:
                          text: "2026-09-06T00:00:00Z"
                    """.formatted(tracker.getAddress().getPort()));
            service.start();
            client = service.client();
            assertTrue(client.isReady());
            assertEquals(client.version() + " — Running", service.status().message());
            var indexers = client.publicIndexers();
            assertFalse(indexers.isEmpty());
            assertTrue(indexers.stream().anyMatch(row -> row.id().equals("linuxtracker")));
            assertFalse(client.configuration("linuxtracker").isEmpty());
            assertFalse(client.apiKey().isBlank());
            client.test("odmfixture");
            assertTrue(client.publicIndexers().stream().anyMatch(row -> row.id().equals("odmfixture") && !row.configured()),
                    "automatic probes must not configure unchecked indexers");
            client.configure("odmfixture", client.configuration("odmfixture"));
            client.test("odmfixture");
            assertTrue(client.publicIndexers().stream().anyMatch(row -> row.id().equals("odmfixture") && row.configured()));
            var search = client.search("Linux", 4000, Set.of("odmfixture"));
            assertTrue(search.warnings().isEmpty(), search.warnings().toString());
            assertEquals(1, search.results().size());
            var torrent = search.results().getFirst();
            assertEquals("Linux fixture", torrent.title());
            assertEquals(7, torrent.seeders()); assertEquals(3, torrent.leechers());
            assertEquals("linux.iso", client.resolve(torrent).files().getFirst().path());
            client.unconfigure("odmfixture");
            assertTrue(client.publicIndexers().stream().anyMatch(row -> row.id().equals("odmfixture") && !row.configured()));
            assertFalse(Files.exists(directory.resolve("config/Indexers/odmfixture.json")),
                    "unchecking must remove Jackett's persisted indexer configuration");
            client.configure("odmfixture", client.configuration("odmfixture"));
            assertTrue(client.publicIndexers().stream().anyMatch(row -> row.id().equals("odmfixture") && row.configured()),
                    "the same indexer can be configured again after removal");
            service.stop(); assertFalse(client.isReady());
        } finally { tracker.stop(0); }
    }
}
