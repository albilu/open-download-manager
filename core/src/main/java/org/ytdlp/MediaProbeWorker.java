package org.ytdlp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.WaitUntilState;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.manager.url.DownloadUrlPolicy;

/** Runs in an ODM-owned child JVM so cancellation also stops the driver/browser tree. */
public final class MediaProbeWorker {
    static final String OUTPUT_PREFIX = "ODM_MEDIA_PROBE:";
    record Input(String url, String proxy, String userAgent, String referer,
            String cookieHeader, String cookies, int timeoutMillis) { }
    record Output(List<MediaCandidate> candidates, String error) { }

    private MediaProbeWorker() { }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Output output;
        try {
            var inputStream = new java.io.DataInputStream(System.in);
            int length = inputStream.readInt();
            if (length < 0 || length > 2 * 1024 * 1024) {
                throw new IllegalArgumentException("Media probe input is too large");
            }
            byte[] input = inputStream.readNBytes(length);
            AtomicBoolean cancelled = new AtomicBoolean();
            Thread.ofPlatform().daemon().name("media-probe-cancel").start(() -> {
                try { inputStream.read(); }
                catch (java.io.IOException ignored) { }
                cancelled.set(true);
            });
            output = new Output(probe(mapper.readValue(input, Input.class), cancelled), null);
        } catch (IllegalArgumentException failure) {
            output = new Output(List.of(), failure.getMessage());
        } catch (Exception failure) {
            output = new Output(List.of(), "Could not probe this page with Chromium");
        }
        System.out.println(OUTPUT_PREFIX + mapper.writeValueAsString(output));
    }

    static List<MediaCandidate> probe(Input input, AtomicBoolean cancelled) {
        if (cancelled.get()) { throw new CancellationException(); }
        String url = DownloadUrlPolicy.require(input.url()).requireWeb().uri().toString();
        Proxy proxy = proxy(input.proxy());
        try (Playwright playwright = Playwright.create()) {
            var arguments = new ArrayList<>(List.of("--disable-quic", "--disable-background-networking"));
            if (proxy == null) {
                arguments.add("--no-proxy-server");
            } else {
                // URL proxying alone does not cover DNS prefetching or WebRTC UDP.
                // Only the proxy hostname may resolve locally; website DNS belongs to the proxy.
                arguments.add("--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE "
                        + URI.create(proxy.server).getHost());
                arguments.add("--webrtc-ip-handling-policy=disable_non_proxied_udp");
            }
            var launch = new BrowserType.LaunchOptions().setHeadless(true).setTimeout(15_000)
                    .setArgs(arguments);
            Path executable = browserExecutable(playwright);
            if (executable == null) {
                throw new IllegalArgumentException("Media probing requires Chromium to be installed");
            }
            launch.setExecutablePath(executable);
            // Explicit direct mode avoids inheriting a desktop/system proxy.
            if (proxy != null) { launch.setProxy(proxy); }
            try (Browser browser = playwright.chromium().launch(launch)) {
                if (cancelled.get()) { throw new CancellationException(); }
                var options = new Browser.NewContextOptions().setAcceptDownloads(false);
                if (input.userAgent() != null && !input.userAgent().isBlank()) {
                    options.setUserAgent(input.userAgent());
                }
                try (BrowserContext context = browser.newContext(options)) {
                    context.addCookies(parseCookies(input.cookies()));
                    addHeaderCookies(context, url, input.cookieHeader());
                    MediaCandidates candidates = new MediaCandidates();
                    long[] lastCandidate = {0};
                    context.onPage(page -> page.onDialog(dialog -> dialog.dismiss()));
                    context.onResponse(response -> {
                        try {
                            var request = response.request();
                            String type = response.headerValue("content-type");
                            if (!"GET".equals(request.method()) || MediaCandidates.confidence(
                                    response.url(), response.status(), type, request.resourceType()) == 0) { return; }
                            long size = number(response.headerValue("content-length"));
                            String range = response.headerValue("content-range");
                            if (range != null && range.contains("/")) {
                                size = number(range.substring(range.lastIndexOf('/') + 1));
                            }
                            candidates.observe(response.url(), response.status(), type,
                                    request.resourceType(), size, request.allHeaders());
                            lastCandidate[0] = System.nanoTime();
                        } catch (PlaywrightException ignored) {
                            // Requests may disappear when a frame redirects or closes.
                        }
                    });
                    context.onRequestFinished(request -> {
                        try {
                            var response = request.response();
                            if (response == null) { return; }
                            String type = response.headerValue("content-type");
                            if (MediaCandidates.confidence(response.url(), response.status(), type,
                                    request.resourceType()) < 95) { return; }
                            double size = request.sizes().responseBodySize;
                            if (size > 0 && size <= 512 * 1024) {
                                candidates.manifest(response.url(), response.text());
                            }
                        } catch (PlaywrightException ignored) { }
                    });
                    Page page = context.newPage();
                    long deadline = System.nanoTime() + Math.max(1000, Math.min(20_000,
                            input.timeoutMillis())) * 1_000_000L;
                    try {
                        var navigation = new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT)
                                .setTimeout(Math.min(10_000, input.timeoutMillis()));
                        if (input.referer() != null && !input.referer().isBlank()) {
                            navigation.setReferer(input.referer());
                        }
                        page.navigate(url, navigation);
                    } catch (PlaywrightException navigationFailure) {
                        // A failed navigation can still have emitted usable media requests.
                    }
                    while (!cancelled.get() && !page.isClosed() && System.nanoTime() < deadline) {
                        if (!candidates.isEmpty() && System.nanoTime() - lastCandidate[0] > 2_000_000_000L) {
                            break;
                        }
                        // This pumps Playwright's synchronous event loop while awaiting late JS requests.
                        page.waitForTimeout(100);
                    }
                    if (cancelled.get()) { throw new CancellationException(); }
                    Set<String> playerSources = new HashSet<>();
                    for (var frame : page.frames()) {
                        try {
                            Object sources = frame.evaluate("() => Array.from(document.querySelectorAll('video,audio,source'))"
                                    + ".flatMap(e => [e.currentSrc, e.src]).filter(Boolean)");
                            if (sources instanceof List<?> list) {
                                list.forEach(value -> playerSources.add(String.valueOf(value)));
                            }
                        } catch (PlaywrightException ignored) { }
                    }
                    return candidates.results(url, String.valueOf(page.evaluate("navigator.userAgent")),
                            cookieJar(context.cookies(candidates.cookieUrls(url))), playerSources);
                }
            }
        }
    }

    private static Path browserExecutable(Playwright playwright) {
        String configured = System.getenv("ODM_CHROMIUM_PATH");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (!Files.isExecutable(path)) {
                throw new IllegalArgumentException("The configured Chromium executable is unavailable");
            }
            return path;
        }
        Path managed = Path.of(playwright.chromium().executablePath());
        if (Files.isExecutable(managed)) { return managed; }
        for (String path : List.of("/usr/bin/chromium", "/usr/bin/chromium-browser",
                "/usr/bin/google-chrome", "/opt/google/chrome/chrome")) {
            if (Files.isExecutable(Path.of(path))) { return Path.of(path); }
        }
        return null;
    }

    static Proxy proxy(String address) {
        if (address == null || address.isBlank()) { return null; }
        final URI uri;
        try { uri = URI.create(address); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid media proxy address"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if ("socks5h".equals(scheme)) { scheme = "socks5"; }
        if (!Set.of("http", "https", "socks5").contains(scheme) || uri.getHost() == null
                || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("Media probing does not support the selected proxy type");
        }
        if (scheme.startsWith("socks") && uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Chromium does not support authenticated SOCKS proxies");
        }
        Proxy proxy = new Proxy(scheme + "://" + uri.getHost() + ":" + uri.getPort());
        // Chromium otherwise bypasses a proxy for loopback targets implicitly.
        proxy.setBypass("<-loopback>");
        if (uri.getRawUserInfo() != null) {
            String[] credentials = uri.getRawUserInfo().split(":", 2);
            proxy.setUsername(decode(credentials[0]));
            proxy.setPassword(credentials.length == 2 ? decode(credentials[1]) : "");
        }
        return proxy;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    static List<Cookie> parseCookies(String jar) {
        if (jar == null || jar.isBlank()) { return List.of(); }
        var cookies = new ArrayList<Cookie>();
        for (String line : jar.lines().toList()) {
            boolean httpOnly = line.startsWith("#HttpOnly_");
            if (httpOnly) { line = line.substring("#HttpOnly_".length()); }
            if (line.isBlank() || line.startsWith("#")) { continue; }
            String[] fields = line.split("\t", -1);
            if (fields.length != 7) { throw new IllegalArgumentException("Invalid browser cookie file"); }
            long expires = number(fields[4]);
            if (expires > 0 && expires < System.currentTimeMillis() / 1000) { continue; }
            var cookie = new Cookie(fields[5], fields[6]).setDomain(fields[0])
                    .setPath(fields[2].isEmpty() ? "/" : fields[2])
                    .setSecure(Boolean.parseBoolean(fields[3])).setHttpOnly(httpOnly);
            if (expires > 0) { cookie.setExpires(expires); }
            cookies.add(cookie);
        }
        return cookies;
    }

    private static void addHeaderCookies(BrowserContext context, String url, String header) {
        if (header == null || header.isBlank()) { return; }
        String value = header.trim().replaceFirst("(?i)^Cookie:\\s*", "");
        URI uri = URI.create(url);
        var cookies = new ArrayList<Cookie>();
        for (String pair : value.split(";")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length == 2) {
                cookies.add(new Cookie(parts[0], parts[1]).setUrl(uri.getScheme() + "://" + uri.getAuthority() + "/"));
            }
        }
        context.addCookies(cookies);
    }

    static String cookieJar(List<Cookie> cookies) {
        var jar = new StringBuilder("# Netscape HTTP Cookie File\n");
        for (Cookie cookie : cookies) {
            if (List.of(cookie.domain, cookie.path, cookie.name, cookie.value).stream()
                    .anyMatch(value -> value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
                continue;
            }
            jar.append(Boolean.TRUE.equals(cookie.httpOnly) ? "#HttpOnly_" : "")
                    .append(cookie.domain).append('\t').append(cookie.domain.startsWith(".") ? "TRUE" : "FALSE")
                    .append('\t').append(cookie.path).append('\t').append(Boolean.TRUE.equals(cookie.secure) ? "TRUE" : "FALSE")
                    .append('\t').append(cookie.expires != null && cookie.expires > 0 ? cookie.expires.longValue() : 0)
                    .append('\t').append(cookie.name).append('\t').append(cookie.value).append('\n');
        }
        return jar.toString();
    }

    private static long number(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
