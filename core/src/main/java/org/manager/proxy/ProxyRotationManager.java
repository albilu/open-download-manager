package org.manager.proxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Manages a pool of proxies and provides rotation functionality. This class
 * handles proxy selection, health tracking, and automatic rotation for download
 * retry mechanisms.
 */
public class ProxyRotationManager {

    private static final Logger LOGGER = Logger.getLogger(ProxyRotationManager.class.getName());

    private final List<Proxy> proxyPool;
    private final Map<String, Proxy> usedProxies; // Track proxies currently in use
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Random random = ThreadLocalRandom.current();

    // Configuration
    private final int maxFailuresBeforeRemoval;
    private final long healthCheckIntervalMinutes;
    private final boolean enableHealthChecks;

    // Default proxy list for fallback
    private static final String[] DEFAULT_PROXIES = {
            "http://proxy1.example.com:8080",
            "http://proxy2.example.com:8080",
            "socks5://proxy3.example.com:1080",
            "http://proxy4.example.com:3128"
    };

    /**
     * Creates a new ProxyRotationManager with default configuration.
     */
    public ProxyRotationManager() {
        this(new ArrayList<>(), 5, 30, true);
    }

    /**
     * Creates a new ProxyRotationManager with custom configuration.
     *
     * @param initialProxies             Initial list of proxies
     * @param maxFailuresBeforeRemoval   Maximum failures before removing a proxy
     * @param healthCheckIntervalMinutes Interval between health checks in
     *                                   minutes
     * @param enableHealthChecks         Whether to enable automatic health checks
     */
    public ProxyRotationManager(List<Proxy> initialProxies,
            int maxFailuresBeforeRemoval,
            long healthCheckIntervalMinutes,
            boolean enableHealthChecks) {
        this.proxyPool = new ArrayList<>(initialProxies);
        this.usedProxies = new ConcurrentHashMap<>();
        this.maxFailuresBeforeRemoval = maxFailuresBeforeRemoval;
        this.healthCheckIntervalMinutes = healthCheckIntervalMinutes;
        this.enableHealthChecks = enableHealthChecks;

        // If no proxies provided, load defaults
        if (this.proxyPool.isEmpty()) {
            loadDefaultProxies();
        }

        LOGGER.info("ProxyRotationManager initialized with " + proxyPool.size() + " proxies");
    }

    /**
     * Loads proxies from a file. Each line should contain a proxy URL. Supports
     * formats like: - http://host:port - http://user:pass@host:port -
     * socks5://host:port
     *
     * @param filePath Path to the proxy list file
     * @return Number of proxies loaded
     * @throws IOException If file cannot be read
     */
    public int loadProxiesFromFile(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException("Proxy file does not exist: " + filePath);
        }

        lock.writeLock().lock();
        try {
            int initialCount = proxyPool.size();

            try (BufferedReader reader = Files.newBufferedReader(filePath)) {
                String line;
                int lineNumber = 0;

                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    line = line.trim();

                    // Skip empty lines and comments
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }

                    try {
                        Proxy proxy = Proxy.fromUrl(line);
                        if (!proxyPool.contains(proxy)) {
                            proxyPool.add(proxy);
                        }
                    } catch (Exception e) {
                        LOGGER.warning(
                                "Failed to parse proxy on line " + lineNumber + ": " + line + " - " + e.getMessage());
                    }
                }
            }

            int loadedCount = proxyPool.size() - initialCount;
            LOGGER.info("Loaded " + loadedCount + " new proxies from " + filePath);
            return loadedCount;

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Adds a proxy to the pool.
     *
     * @param proxy The proxy to add
     * @return true if proxy was added, false if it already exists
     */
    public boolean addProxy(Proxy proxy) {
        if (proxy == null) {
            return false;
        }

        lock.writeLock().lock();
        try {
            if (!proxyPool.contains(proxy)) {
                proxyPool.add(proxy);
                LOGGER.fine("Added proxy: " + proxy.getAddress());
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a proxy from the pool.
     *
     * @param proxy The proxy to remove
     * @return true if proxy was removed, false if it didn't exist
     */
    public boolean removeProxy(Proxy proxy) {
        if (proxy == null) {
            return false;
        }

        lock.writeLock().lock();
        try {
            boolean removed = proxyPool.remove(proxy);
            if (removed) {
                usedProxies.remove(proxy.getAddress());
                LOGGER.fine("Removed proxy: " + proxy.getAddress());
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets a random healthy proxy from the pool. Prioritizes proxies that
     * haven't been used recently.
     *
     * @return A proxy to use, or null if no healthy proxies available
     */
    public Proxy getRandomProxy() {
        lock.readLock().lock();
        try {
            List<Proxy> healthyProxies = proxyPool.stream()
                    .filter(Proxy::isHealthy)
                    .collect(Collectors.toList());

            if (healthyProxies.isEmpty()) {
                LOGGER.warning("No healthy proxies available, trying all proxies");
                healthyProxies = new ArrayList<>(proxyPool);
            }

            if (healthyProxies.isEmpty()) {
                LOGGER.severe("No proxies available in the pool");
                return null;
            }

            // Prefer proxies not currently in use
            List<Proxy> availableProxies = healthyProxies.stream()
                    .filter(proxy -> !usedProxies.containsKey(proxy.getAddress()))
                    .collect(Collectors.toList());

            if (availableProxies.isEmpty()) {
                availableProxies = healthyProxies;
            }

            // Weighted random selection based on health score
            return selectProxyByWeight(availableProxies);

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets a different proxy than the one provided (for retry scenarios).
     *
     * @param excludeProxy Proxy to exclude from selection
     * @return A different proxy, or null if no alternatives available
     */
    public Proxy getAlternativeProxy(Proxy excludeProxy) {
        lock.readLock().lock();
        try {
            List<Proxy> alternatives = proxyPool.stream()
                    .filter(Proxy::isHealthy)
                    .filter(proxy -> !proxy.equals(excludeProxy))
                    .collect(Collectors.toList());

            if (alternatives.isEmpty()) {
                // Try all proxies except the excluded one
                alternatives = proxyPool.stream()
                        .filter(proxy -> !proxy.equals(excludeProxy))
                        .collect(Collectors.toList());
            }

            if (alternatives.isEmpty()) {
                LOGGER.warning("No alternative proxies available");
                return null;
            }

            return selectProxyByWeight(alternatives);

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Marks a proxy as being used by a download.
     *
     * @param proxy      The proxy being used
     * @param downloadId ID of the download using this proxy
     */
    public void markProxyInUse(Proxy proxy, String downloadId) {
        if (proxy != null && downloadId != null) {
            usedProxies.put(proxy.getAddress(), proxy);
            LOGGER.fine("Marked proxy " + proxy.getAddress() + " as in use by download " + downloadId);
        }
    }

    /**
     * Releases a proxy from use.
     *
     * @param proxy      The proxy to release
     * @param downloadId ID of the download releasing this proxy
     */
    public void releaseProxy(Proxy proxy, String downloadId) {
        if (proxy != null) {
            usedProxies.remove(proxy.getAddress());
            LOGGER.fine("Released proxy " + proxy.getAddress() + " from download " + downloadId);
        }
    }

    /**
     * Records a successful use of a proxy.
     *
     * @param proxy        The proxy that succeeded
     * @param responseTime Response time in milliseconds
     */
    public void recordSuccess(Proxy proxy, long responseTime) {
        if (proxy != null) {
            proxy.recordSuccess(responseTime);
            LOGGER.fine(
                    "Recorded success for proxy " + proxy.getAddress() + " (response time: " + responseTime + "ms)");
        }
    }

    /**
     * Records a failure for a proxy.
     *
     * @param proxy The proxy that failed
     * @param error Error message or reason for failure
     */
    public void recordFailure(Proxy proxy, String error) {
        if (proxy == null) {
            return;
        }

        proxy.recordFailure(error);
        LOGGER.warning("Recorded failure for proxy " + proxy.getAddress() + ": " + error
                + " (total failures: " + proxy.getFailureCount() + ")");

        // Remove proxy if it has too many failures
        if (proxy.getFailureCount() >= maxFailuresBeforeRemoval) {
            lock.writeLock().lock();
            try {
                removeProxy(proxy);
                LOGGER.warning("Removed proxy " + proxy.getAddress() + " due to excessive failures");
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    /**
     * Gets statistics about the proxy pool.
     *
     * @return Map containing various statistics
     */
    public Map<String, Object> getStatistics() {
        lock.readLock().lock();
        try {
            Map<String, Object> stats = new HashMap<>();

            stats.put("totalProxies", proxyPool.size());
            stats.put("proxiesInUse", usedProxies.size());

            long healthyCount = proxyPool.stream().mapToLong(proxy -> proxy.isHealthy() ? 1 : 0).sum();
            stats.put("healthyProxies", healthyCount);

            long unhealthyCount = proxyPool.stream()
                    .mapToLong(proxy -> proxy.getStatus() == Proxy.Status.UNHEALTHY ? 1 : 0).sum();
            stats.put("unhealthyProxies", unhealthyCount);

            long blockedCount = proxyPool.stream().mapToLong(proxy -> proxy.getStatus() == Proxy.Status.BLOCKED ? 1 : 0)
                    .sum();
            stats.put("blockedProxies", blockedCount);

            double avgResponseTime = proxyPool.stream()
                    .filter(proxy -> proxy.getAverageResponseTime() > 0)
                    .mapToLong(Proxy::getAverageResponseTime)
                    .average()
                    .orElse(0.0);
            stats.put("averageResponseTime", avgResponseTime);

            return stats;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Performs health check cleanup - removes blocked proxies and resets
     * unhealthy ones.
     */
    public void performHealthCheck() {
        if (!enableHealthChecks) {
            return;
        }

        lock.writeLock().lock();
        try {
            Instant cutoff = Instant.now().minus(healthCheckIntervalMinutes, ChronoUnit.MINUTES);

            // Remove permanently blocked proxies
            proxyPool.removeIf(proxy -> proxy.getStatus() == Proxy.Status.BLOCKED);

            // Reset proxies that haven't been tested recently
            for (Proxy proxy : proxyPool) {
                if (proxy.getLastTested() != null && proxy.getLastTested().isBefore(cutoff)) {
                    if (proxy.getStatus() == Proxy.Status.UNHEALTHY) {
                        proxy.reset();
                        LOGGER.fine("Reset unhealthy proxy " + proxy.getAddress() + " for retry");
                    }
                }
            }

            LOGGER.info("Health check completed. Active proxies: " + proxyPool.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets the current size of the proxy pool.
     *
     * @return Number of proxies in the pool
     */
    public int getPoolSize() {
        lock.readLock().lock();
        try {
            return proxyPool.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks if the proxy pool is empty.
     *
     * @return true if no proxies are available, false otherwise
     */
    public boolean isEmpty() {
        return getPoolSize() == 0;
    }

    /**
     * Selects a proxy using weighted random selection based on health scores.
     */
    private Proxy selectProxyByWeight(List<Proxy> proxies) {
        if (proxies.isEmpty()) {
            return null;
        }

        if (proxies.size() == 1) {
            return proxies.get(0);
        }

        // Calculate total weight
        double totalWeight = proxies.stream()
                .mapToDouble(Proxy::getHealthScore)
                .sum();

        if (totalWeight <= 0) {
            // If all proxies have zero weight, select randomly
            return proxies.get(random.nextInt(proxies.size()));
        }

        // Weighted random selection
        double randomValue = random.nextDouble() * totalWeight;
        double currentWeight = 0;

        for (Proxy proxy : proxies) {
            currentWeight += proxy.getHealthScore();
            if (currentWeight >= randomValue) {
                return proxy;
            }
        }

        // Fallback (shouldn't happen)
        return proxies.get(proxies.size() - 1);
    }

    /**
     * Loads default proxies as a fallback.
     */
    private void loadDefaultProxies() {
        LOGGER.info("Loading default proxy list");
        for (String proxyUrl : DEFAULT_PROXIES) {
            try {
                Proxy proxy = Proxy.fromUrl(proxyUrl);
                proxyPool.add(proxy);
            } catch (Exception e) {
                LOGGER.warning("Failed to parse default proxy: " + proxyUrl + " - " + e.getMessage());
            }
        }
    }
}
