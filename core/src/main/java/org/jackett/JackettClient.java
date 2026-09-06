package org.jackett;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.manager.download.DescriptorFileInspector;
import org.manager.download.Download;
import org.manager.download.DownloadFileInfo;
import org.manager.url.DownloadUrlPolicy;
import org.manager.util.DescriptorStaging;

/** Jackett's local JSON API. Network methods are blocking and belong on an I/O worker. */
public final class JackettClient {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_JSON_BYTES = 32 * 1024 * 1024;
    private final URI base;
    private final Path configFile;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);

    public record Category(int id, String name) { }
    public record Indexer(String id, String name, boolean configured, String error,
            List<Category> categories) { }
    public record TorrentResult(String title, long size, long seeders, long leechers,
            Instant published, String tracker, URI link, URI magnet) { }
    public record SearchResults(List<TorrentResult> results, List<String> warnings) { }

    /** Descriptor bytes stay in memory during preview; only accepted downloads need durable staging. */
    public record TorrentSource(URI magnet, byte[] descriptor) {
        public TorrentSource {
            descriptor = descriptor == null ? null : descriptor.clone();
        }

        @Override
        public byte[] descriptor() { return descriptor == null ? null : descriptor.clone(); }

        public List<DownloadFileInfo> files() throws IOException {
            return descriptor == null ? List.of()
                    : DescriptorFileInspector.inspect(descriptor, Download.Protocol.TORRENT);
        }

        public URI stageForDownload() throws IOException {
            if (descriptor == null) {
                return DownloadUrlPolicy.require(magnet).uri();
            }
            Path root = DescriptorStaging.manualStagingRoot();
            Files.createDirectories(root);
            Path file = Files.createTempFile(root, "jackett-", ".torrent");
            try {
                Files.write(file, descriptor);
                return file.toUri();
            } catch (IOException failure) {
                Files.deleteIfExists(file);
                throw failure;
            }
        }
    }

    public JackettClient(int port, Path configFile) {
        if (port < 1 || port > 65535) { throw new IllegalArgumentException("Invalid Jackett port"); }
        this.base = URI.create("http://127.0.0.1:" + port + "/");
        this.configFile = configFile;
    }

    public URI dashboard() { return base.resolve("UI/Dashboard"); }

    /** Version reported by the running server, independent of the installation archive. */
    public String version() throws IOException {
        String version = admin("GET", "api/v2.0/server/config", null, 5000).path("app_version").asText("").strip();
        if (!version.matches("v?[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?")) {
            throw new IOException("Jackett did not report a valid version");
        }
        return version.startsWith("v") ? version.substring(1) : version;
    }

    /** Read on every request so Jackett API-key regeneration takes effect immediately. */
    String apiKey() throws IOException {
        if (!Files.isRegularFile(configFile)) {
            throw new IOException("Start Jackett to create ServerConfig.json");
        }
        JsonNode config;
        try (InputStream input = Files.newInputStream(configFile)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) { throw new IOException("Jackett configuration is too large"); }
            config = JSON.readTree(bytes);
        } catch (IOException invalid) {
            throw new IOException("Could not read Jackett ServerConfig.json");
        }
        String key = config == null ? "" : config.path("APIKey").asText("").strip();
        if (key.isEmpty()) { throw new IOException("Jackett has not generated its API key yet"); }
        return key;
    }

    public boolean isReady() {
        try {
            // Authenticated, local-only and never queries a tracker.
            request("GET", endpoint("api/v2.0/indexers/all/results/torznab/api",
                    "t=caps"), null, 1024 * 1024, 1000);
            return true;
        } catch (IOException failure) { return false; }
    }

    public List<Indexer> publicIndexers() throws IOException {
        JsonNode response = admin("GET", "api/v2.0/indexers", null);
        if (!response.isArray()) { throw new IOException("Jackett returned an invalid indexer list"); }
        List<Indexer> result = new ArrayList<>();
        for (JsonNode row : response) {
            if (!"public".equalsIgnoreCase(row.path("type").asText())) { continue; }
            String id = row.path("id").asText();
            if (!validId(id)) { continue; }
            List<Category> categories = new ArrayList<>();
            for (JsonNode cap : row.path("caps")) {
                int category = cap.path("ID").asInt(-1);
                if (category > 0) { categories.add(new Category(category, cap.path("Name").asText())); }
            }
            result.add(new Indexer(id, row.path("name").asText(id),
                    row.path("configured").asBoolean(), safeMessage(row.path("last_error").asText("")),
                    List.copyOf(categories)));
        }
        result.sort(Comparator.comparing(Indexer::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public ArrayNode configuration(String indexer) throws IOException {
        JsonNode config = admin("GET", indexerPath(indexer) + "/config", null);
        // Some Jackett versions serialize the configuration array as a JSON string.
        if (config.isTextual()) { config = parse(config.asText().getBytes(StandardCharsets.UTF_8)); }
        if (!(config instanceof ArrayNode array)) {
            throw new IOException("Jackett returned an invalid indexer configuration");
        }
        return array;
    }

    public void configure(String indexer, ArrayNode fields) throws IOException {
        admin("POST", indexerPath(indexer) + "/config", JSON.writeValueAsBytes(fields));
    }

    public void unconfigure(String indexer) throws IOException {
        admin("DELETE", indexerPath(indexer), null);
    }

    public void test(String indexer) throws IOException {
        admin("POST", indexerPath(indexer) + "/test", new byte[0]);
    }

    public SearchResults search(String query, int category, Set<String> indexers) throws IOException {
        if (query == null || query.isBlank()) { throw new IllegalArgumentException("Enter a search term"); }
        if (indexers == null || indexers.isEmpty()) {
            throw new IllegalArgumentException("Select indexers in Settings → Search Engine");
        }
        if (category < 0 || indexers.stream().anyMatch(id -> !validId(id))) {
            throw new IllegalArgumentException("Invalid category or indexer selection");
        }
        String parameters = "query=" + encode(query.strip()) + "&Tracker%5B%5D="
                + encode(String.join(",", indexers))
                + (category == 0 ? "" : "&Category%5B%5D=" + category);
        JsonNode response = parse(request("GET", endpoint(
                "api/v2.0/indexers/all/results", parameters), null, MAX_JSON_BYTES, 90000).body());
        if (!response.path("Results").isArray()) {
            throw new IOException("Jackett returned an invalid search response");
        }
        List<TorrentResult> results = new ArrayList<>();
        for (JsonNode row : response.path("Results")) {
            URI magnet = supportedUri(row.path("MagnetUri").asText(), true);
            URI link = supportedUri(row.path("Link").asText(), false);
            if (link == null && magnet == null) { continue; }
            // TrackerCacheResult.Peers already means leechers, unlike Torznab's total peers.
            results.add(new TorrentResult(row.path("Title").asText("Unnamed torrent"),
                    nonNegative(row, "Size"), nonNegative(row, "Seeders"), nonNegative(row, "Peers"),
                    date(row.path("PublishDate").asText()), row.path("Tracker").asText(""), link, magnet));
        }
        List<String> warnings = new ArrayList<>();
        for (JsonNode indexer : response.path("Indexers")) {
            JsonNode status = indexer.path("Status");
            if (!(status.isInt() && status.asInt() == 2) && !"OK".equalsIgnoreCase(status.asText())) {
                warnings.add(safeMessage(indexer.path("Name").asText("Indexer") + ": "
                        + indexer.path("Error").asText("Search failed")));
            }
        }
        return new SearchResults(List.copyOf(results), List.copyOf(warnings));
    }

    public TorrentSource resolve(TorrentResult result) throws IOException {
        if (result.link() != null) {
            // Jackett rewrites torrent links to its own /dl endpoint. Never forward its API key
            // or administration cookies to a tracker or a redirect on another origin.
            requireLocal(result.link());
            Response response = request("GET", result.link(), null,
                    DescriptorFileInspector.MAX_DESCRIPTOR_BYTES, 60000);
            if (response.redirect() != null) {
                URI magnet = supportedUri(response.redirect().toString(), true);
                if (magnet != null) { return new TorrentSource(magnet, null); }
                throw new IOException("Jackett returned an unsupported torrent redirect");
            }
            DescriptorFileInspector.inspect(response.body(), Download.Protocol.TORRENT);
            return new TorrentSource(null, response.body());
        }
        URI magnet = result.magnet();
        if (magnet == null) { throw new IOException("This result has no torrent or magnet link"); }
        return new TorrentSource(DownloadUrlPolicy.require(magnet).uri(), null);
    }

    private JsonNode admin(String method, String path, byte[] body) throws IOException {
        return admin(method, path, body, 90000);
    }

    private JsonNode admin(String method, String path, byte[] body, int timeout) throws IOException {
        URI uri = endpoint(path, "");
        Response response = request(method, uri, body, MAX_JSON_BYTES, timeout);
        if (response.redirect() != null) {
            // Jackett's admin endpoints require a session cookie even with no admin password.
            Response login = request("GET", base.resolve("UI/Login?cookiesChecked=1"),
                    null, 1024 * 1024, 5000);
            if (login.redirect() == null || !login.redirect().getPath().endsWith("/Dashboard")) {
                throw new IOException("Unlock Jackett administration in its dashboard; an admin password is set");
            }
            response = request(method, uri, body, MAX_JSON_BYTES, timeout);
            if (response.redirect() != null) {
                throw new IOException("Jackett administration authentication failed");
            }
        }
        return response.body().length == 0 ? JSON.nullNode() : parse(response.body());
    }

    private URI endpoint(String path, String query) throws IOException {
        return base.resolve(path + "?apikey=" + encode(apiKey()) + (query.isBlank() ? "" : "&" + query));
    }

    private record Response(byte[] body, URI redirect) { }

    private Response request(String method, URI uri, byte[] body, int limit, int timeout) throws IOException {
        requireLocal(uri);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(Math.min(timeout, 3000));
        connection.setReadTimeout(timeout);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", "Open Download Manager");
        synchronized (cookies) {
            cookies.get(uri, java.util.Map.of()).forEach((key, values) ->
                    connection.setRequestProperty(key, String.join("; ", values)));
        }
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(body.length);
                try (var out = connection.getOutputStream()) { out.write(body); }
            }
            int status = connection.getResponseCode();
            synchronized (cookies) { cookies.put(uri, connection.getHeaderFields()); }
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                if (location == null) { throw new IOException("Jackett returned an empty redirect"); }
                return new Response(new byte[0], uri.resolve(location));
            }
            if (connection.getContentLengthLong() > limit) {
                throw new IOException("Jackett response exceeds the size limit");
            }
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes;
            try (stream) { bytes = stream == null ? new byte[0] : stream.readNBytes(limit + 1); }
            if (bytes.length > limit) { throw new IOException("Jackett response exceeds the size limit"); }
            if (status < 200 || status >= 300) {
                String detail = "";
                try {
                    JsonNode error = JSON.readTree(bytes);
                    if (error != null) { detail = error.path("error").asText(error.path("message").asText("")); }
                } catch (IOException ignored) { }
                throw new IOException("Jackett HTTP " + status + (detail.isBlank() ? "" : ": " + safeMessage(detail)));
            }
            return new Response(bytes, null);
        } finally { connection.disconnect(); }
    }

    private void requireLocal(URI uri) throws IOException {
        if (!base.getScheme().equals(uri.getScheme()) || !base.getHost().equals(uri.getHost())
                || base.getPort() != uri.getPort() || uri.getUserInfo() != null) {
            throw new IOException("Jackett returned a link outside its local service");
        }
    }

    private static JsonNode parse(byte[] bytes) throws IOException {
        try {
            JsonNode value = JSON.readTree(bytes);
            if (value != null) { return value; }
        } catch (IOException ignored) { }
        throw new IOException("Jackett returned invalid JSON");
    }

    private static boolean validId(String id) { return id != null && id.matches("[a-zA-Z0-9_-]+"); }
    private static String indexerPath(String id) {
        if (!validId(id)) { throw new IllegalArgumentException("Invalid indexer ID"); }
        return "api/v2.0/indexers/" + id;
    }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static long nonNegative(JsonNode node, String key) { return Math.max(0, node.path(key).asLong()); }
    private static Instant date(String value) {
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (RuntimeException invalid) { return null; }
    }
    private static URI supportedUri(String text, boolean magnet) {
        try {
            URI uri = URI.create(text);
            if (magnet) {
                return "magnet".equalsIgnoreCase(uri.getScheme()) ? DownloadUrlPolicy.require(uri).uri() : null;
            }
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null ? uri : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    public static String safeMessage(String text) {
        String clean = text.replaceAll("(?i)(apikey|passkey|password)=([^\\s&\"<>]+)", "$1=[redacted]")
                .replaceAll("<[^>]*>", " ").replaceAll("[\\r\\n\\t]+", " ");
        return clean.length() > 500 ? clean.substring(0, 500) + "…" : clean;
    }
}
