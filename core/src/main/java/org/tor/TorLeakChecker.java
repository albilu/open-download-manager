package org.tor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.manager.tools.BoundedHttpFetcher;

/** Verifies the selected Tor circuit without making any direct Internet request. */
public final class TorLeakChecker {
    private static final URI TOR_CHECK_URL = URI.create("https://check.torproject.org/api/ip");
    private final String socksProxyHost;
    private final int socksProxyPort;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final URI checkUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TorCircuitCheck");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    public TorLeakChecker() { this("127.0.0.1", 9050, 5000, 5000); }

    public TorLeakChecker(String host, int port, int connectMs, int readMs) {
        this(host, port, connectMs, readMs, TOR_CHECK_URL);
    }

    TorLeakChecker(String host, int port, int connectMs, int readMs, URI checkUrl) {
        if (host == null || host.isBlank() || port < 1 || port > 65535 || connectMs < 1 || readMs < 1) {
            throw new IllegalArgumentException("A Tor endpoint and positive timeouts are required");
        }
        this.socksProxyHost = host;
        this.socksProxyPort = port;
        this.connectTimeout = Duration.ofMillis(connectMs);
        this.readTimeout = Duration.ofMillis(readMs);
        this.checkUrl = checkUrl;
    }

    public synchronized CompletableFuture<LeakCheckResult> performLeakCheck() {
        if (executor.isShutdown()) {
            return CompletableFuture.completedFuture(new LeakCheckResult(false, "Tor check stopped", null));
        }
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new LeakCheckResult(false, "Tor check already running", null));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String host = socksProxyHost.contains(":") && !socksProxyHost.startsWith("[")
                        ? "[" + socksProxyHost + "]" : socksProxyHost;
                byte[] response = BoundedHttpFetcher.fetch(checkUrl, 16384, connectTimeout,
                        readTimeout, "socks5h://" + host + ":" + socksProxyPort);
                return parseResponse(response);
            } catch (IOException | RuntimeException failure) {
                return new LeakCheckResult(false, "Unable to verify the Tor circuit", null);
            } finally {
                running.set(false);
            }
        }, executor);
    }

    static LeakCheckResult parseResponse(byte[] response) throws IOException {
        JsonNode json = new ObjectMapper().readTree(response);
        if (json == null || !json.path("IsTor").isBoolean() || !json.path("IP").isTextual()
                || json.path("IP").asText().isBlank()) {
            throw new IOException("Invalid Tor check response");
        }
        boolean usingTor = json.path("IsTor").booleanValue();
        return new LeakCheckResult(usingTor,
                usingTor ? "Tor circuit verified" : "The selected connection is not using Tor",
                json.path("IP").textValue());
    }

    /** The address comes from the same proxied Tor Project check. */
    public CompletableFuture<String> getExternalIp() {
        return performLeakCheck().thenApply(result -> result.exitNodeIp);
    }

    public boolean isTorProxyAccessible() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(socksProxyHost, socksProxyPort),
                    Math.toIntExact(connectTimeout.toMillis()));
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    public synchronized void shutdown() { executor.shutdownNow(); }

    /** This is a circuit verdict, not a claim about every application's traffic. */
    public static final class LeakCheckResult {
        public final boolean isSecure;
        public final String message;
        public final String exitNodeIp;

        public LeakCheckResult(boolean isSecure, String message, String exitNodeIp) {
            this.isSecure = isSecure;
            this.message = message;
            this.exitNodeIp = exitNodeIp;
        }
    }
}
