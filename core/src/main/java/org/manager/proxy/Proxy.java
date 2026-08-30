package org.manager.proxy;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a proxy server with its configuration and health status.
 */
public class Proxy {

    public enum Type {
        HTTP,
        HTTPS,
        SOCKS4,
        SOCKS5
    }

    public enum Status {
        UNKNOWN,     // Not tested yet
        HEALTHY,     // Working properly
        UNHEALTHY,   // Failed recent tests
        BLOCKED      // Permanently blocked/blacklisted
    }

    private final String host;
    private final int port;
    private final Type type;
    private final String username;
    private final String password;

    // Health tracking
    private volatile Status status = Status.UNKNOWN;
    private volatile int failureCount = 0;
    private volatile int successCount = 0;
    private volatile Instant lastUsed = null;
    private volatile Instant lastTested = null;
    private volatile String lastError = null;
    private volatile long averageResponseTime = 0; // in milliseconds

    /**
     * Creates a new proxy without authentication.
     */
    public Proxy(String host, int port, Type type) {
        this(host, port, type, null, null);
    }

    /**
     * Creates a new proxy with authentication.
     */
    public Proxy(String host, int port, Type type, String username, String password) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        if (type == null) {
            throw new IllegalArgumentException("Type cannot be null");
        }

        this.host = host.trim();
        this.port = port;
        this.type = type;
        this.username = username != null ? username.trim() : null;
        this.password = password;
    }

    /**
     * Creates a proxy from a URL string (e.g., "http://user:pass@host:port").
     */
    public static Proxy fromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL cannot be null or empty");
        }

        url = url.trim();

        // Parse protocol
        Type type;
        if (url.startsWith("http://")) {
            type = Type.HTTP;
            url = url.substring(7);
        } else if (url.startsWith("https://")) {
            type = Type.HTTPS;
            url = url.substring(8);
        } else if (url.startsWith("socks4://")) {
            type = Type.SOCKS4;
            url = url.substring(9);
        } else if (url.startsWith("socks5h://")) {
            type = Type.SOCKS5;
            url = url.substring(10);
        } else if (url.startsWith("socks5://")) {
            type = Type.SOCKS5;
            url = url.substring(9);
        } else {
            // Default to HTTP if no protocol specified
            type = Type.HTTP;
        }

        String username = null;
        String password = null;

        // Parse authentication if present
        if (url.contains("@")) {
            String[] parts = url.split("@", 2);
            String auth = parts[0];
            url = parts[1];

            if (auth.contains(":")) {
                String[] authParts = auth.split(":", 2);
                username = authParts[0];
                password = authParts[1];
            } else {
                username = auth;
            }
        }

        // Parse host and port
        String host;
        int port;

        if (url.contains(":")) {
            String[] parts = url.split(":", 2);
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port in URL: " + parts[1]);
            }
        } else {
            host = url;
            // Default ports based on type
            port = switch (type) {
                case HTTP -> 8080;
                case HTTPS -> 8443;
                case SOCKS4, SOCKS5 -> 1080;
                default -> 8080;
            };
        }

        return new Proxy(host, port, type, username, password);
    }

    // Getters
    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public Type getType() {
        return type;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean hasAuthentication() {
        return username != null && !username.isEmpty();
    }

    public Status getStatus() {
        return status;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public Instant getLastUsed() {
        return lastUsed;
    }

    public Instant getLastTested() {
        return lastTested;
    }

    public String getLastError() {
        return lastError;
    }

    public long getAverageResponseTime() {
        return averageResponseTime;
    }

    // Health management methods
    public void recordSuccess(long responseTime) {
        this.status = Status.HEALTHY;
        this.successCount++;
        this.lastUsed = Instant.now();
        this.lastTested = Instant.now();
        this.lastError = null;

        // Update average response time
        if (this.averageResponseTime == 0) {
            this.averageResponseTime = responseTime;
        } else {
            this.averageResponseTime = (this.averageResponseTime + responseTime) / 2;
        }
    }

    public void recordFailure(String error) {
        this.failureCount++;
        this.lastTested = Instant.now();
        this.lastError = error;

        // Mark as unhealthy after 3 consecutive failures
        if (this.failureCount >= 3) {
            this.status = Status.UNHEALTHY;
        }

        // Mark as blocked after 10 failures
        if (this.failureCount >= 10) {
            this.status = Status.BLOCKED;
        }
    }

    /**
     * Marks this proxy permanently blocked. Used by the rotation manager
     * when it removes a proxy for excessive failures, so holders of the
     * instance observe the terminal tier (the 10-failure threshold alone is
     * unreachable when removal happens earlier).
     */
    void markBlocked() {
        this.status = Status.BLOCKED;
    }

    public void reset() {
        this.status = Status.UNKNOWN;
        this.failureCount = 0;
        this.successCount = 0;
        this.lastError = null;
        this.averageResponseTime = 0;
    }

    /**
     * Returns the proxy URL in the format expected by most tools.
     */
    public String toUrl() {
        StringBuilder sb = new StringBuilder();

        switch (type) {
            case HTTP -> sb.append("http://");
            case HTTPS -> sb.append("https://");
            case SOCKS4 -> sb.append("socks4://");
            // Prefer proxy-side DNS for SOCKS5. Plain socks5 may resolve the
            // destination locally in curl and leak DNS outside the proxy.
            case SOCKS5 -> sb.append("socks5h://");
        }

        if (hasAuthentication()) {
            sb.append(username);
            if (password != null) {
                sb.append(":").append(password);
            }
            sb.append("@");
        }

        sb.append(host).append(":").append(port);

        return sb.toString();
    }

    /**
     * Returns the proxy address without protocol (host:port).
     */
    public String getAddress() {
        return host + ":" + port;
    }

    /**
     * Checks if this proxy is considered healthy for use.
     */
    public boolean isHealthy() {
        return status == Status.HEALTHY || status == Status.UNKNOWN;
    }

    /**
     * Calculates a health score for proxy selection priority.
     * Higher score means better proxy.
     */
    public double getHealthScore() {
        if (status == Status.BLOCKED) {
            return 0.0;
        }

        if (status == Status.UNHEALTHY) {
            return 0.1;
        }

        if (successCount == 0) {
            return 0.5; // Unknown, give it a chance
        }

        double successRate = (double) successCount / (successCount + failureCount);
        double responseScore = averageResponseTime > 0 ? Math.max(0.1, 1.0 - (averageResponseTime / 10000.0)) : 0.5;

        return successRate * 0.7 + responseScore * 0.3;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Proxy proxy = (Proxy) o;
        return port == proxy.port &&
               Objects.equals(host, proxy.host) &&
               type == proxy.type &&
               Objects.equals(username, proxy.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, type, username);
    }

    @Override
    public String toString() {
        return String.format("Proxy{%s, status=%s, failures=%d, successes=%d}",
                getAddress(), status, failureCount, successCount);
    }
}
