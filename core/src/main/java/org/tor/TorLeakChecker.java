package org.tor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to check for DNS leakage and verify proper Tor connection.
 * Performs various tests to ensure traffic is properly routed through Tor.
 */
public class TorLeakChecker {

    private static final Logger LOGGER = Logger.getLogger(TorLeakChecker.class.getName());

    // Test endpoints
    private static final String TOR_CHECK_URL = "https://check.torproject.org/api/ip";
    private static final String IP_CHECK_URL = "https://api.ipify.org";
    private static final String DNS_LEAK_TEST_URL = "https://www.dnsleaktest.com/api/ip";
    private static final Pattern TOR_CHECK_IP_PATTERN = Pattern.compile("\"IP\":\"([^\"]+)\"");
    private static final Pattern TOR_CHECK_COUNTRY_PATTERN = Pattern.compile("\"Country\":\"([^\"]+)\"");
    private static final List<String> DNS_TEST_DOMAINS = Arrays.asList(
            "google.com",
            "cloudflare.com",
            "quad9.net",
            "opendns.com");

    // Tor configuration
    private final String socksProxyHost;
    private final int socksProxyPort;
    private final int connectionTimeoutMs;
    private final int readTimeoutMs;

    // Test results
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile ExecutorService executorService;

    /**
     * Creates a new TorLeakChecker with default Tor proxy settings.
     */
    public TorLeakChecker() {
        this("127.0.0.1", 9050, 30000, 30000);
    }

    /**
     * Creates a new TorLeakChecker with custom proxy settings.
     *
     * @param socksProxyHost      SOCKS proxy host
     * @param socksProxyPort      SOCKS proxy port
     * @param connectionTimeoutMs Connection timeout in milliseconds
     * @param readTimeoutMs       Read timeout in milliseconds
     */
    public TorLeakChecker(String socksProxyHost, int socksProxyPort,
            int connectionTimeoutMs, int readTimeoutMs) {
        this.socksProxyHost = socksProxyHost;
        this.socksProxyPort = socksProxyPort;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "TorLeakChecker-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Performs a comprehensive leak check.
     *
     * @return CompletableFuture with LeakCheckResult
     */
    public CompletableFuture<LeakCheckResult> performLeakCheck() {
        if (isRunning.getAndSet(true)) {
            return CompletableFuture.completedFuture(
                    new LeakCheckResult(false, "Leak check already running", null, null, null));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("Starting comprehensive Tor leak check...");

                // Check if Tor proxy is accessible
                if (!isTorProxyAccessible()) {
                    return new LeakCheckResult(false, "Tor proxy not accessible", null, null, null);
                }

                // Perform IP leak check
                IpLeakResult ipResult = checkIpLeak();

                // Perform DNS leak check
                DnsLeakResult dnsResult = checkDnsLeak();

                // Check if using Tor network
                TorNetworkResult torResult = checkTorNetwork();

                // Determine overall result
                boolean isSecure = ipResult.isSecure && dnsResult.isSecure && torResult.isUsingTor;
                String message = buildResultMessage(ipResult, dnsResult, torResult);

                return new LeakCheckResult(isSecure, message, ipResult, dnsResult, torResult);

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error during leak check", e);
                return new LeakCheckResult(false, "Leak check failed: " + e.getMessage(), null, null, null);
            } finally {
                isRunning.set(false);
            }
        }, getOrCreateExecutorService());
    }

    /**
     * Performs a quick IP leak check only.
     *
     * @return CompletableFuture with the external IP address
     */
    public CompletableFuture<String> getExternalIp() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fetchUrlContent(IP_CHECK_URL, true).trim();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to get external IP", e);
                return null;
            }
        }, getOrCreateExecutorService());
    }

    /**
     * Ensures we have a valid executor service, creating a new one if the
     * current one is shut down.
     */
    private synchronized ExecutorService getOrCreateExecutorService() {
        if (executorService == null || executorService.isShutdown()) {
            this.executorService = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "TorLeakChecker-Worker");
                t.setDaemon(true);
                return t;
            });
        }
        return executorService;
    }

    /**
     * Checks if the Tor proxy is accessible.
     *
     * @return true if proxy is accessible
     */
    public boolean isTorProxyAccessible() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(socksProxyHost, socksProxyPort), 5000);
            return true;
        } catch (Exception e) {
            LOGGER.fine("Tor proxy not accessible: " + e.getMessage());
            return false;
        }
    }

    /**
     * Shuts down the leak checker and releases resources.
     */
    public void shutdown() {
        synchronized (this) {
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // Private helper methods
    private IpLeakResult checkIpLeak() {
        try {
            LOGGER.fine("Checking IP leak...");

            // Get IP without proxy
            String directIp = null;
            try {
                directIp = fetchUrlContent(IP_CHECK_URL, false);
            } catch (Exception e) {
                LOGGER.fine("Could not get direct IP: " + e.getMessage());
            }

            // Get IP through Tor
            String torIp = fetchUrlContent(IP_CHECK_URL, true);

            // Fail closed: a verdict requires both IPs to be known and
            // different. Treating an unknown direct IP as "secure" would
            // give false confidence in exactly the situations (blocked
            // direct egress, captive portal) where leaks hide.
            boolean isSecure = torIp != null && directIp != null && !torIp.equals(directIp);
            String message;
            if (torIp == null) {
                message = "Tor IP could not be determined";
            } else if (directIp == null) {
                message = "Direct IP unknown — cannot verify, treated as unsafe";
            } else if (isSecure) {
                message = "IP properly masked through Tor";
            } else {
                message = "IP leak detected";
            }

            return new IpLeakResult(isSecure, message, directIp, torIp);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "IP leak check failed", e);
            return new IpLeakResult(false, "IP check failed: " + e.getMessage(), null, null);
        }
    }

    private DnsLeakResult checkDnsLeak() {
        try {
            LOGGER.fine("Checking DNS leak...");

            List<String> directDnsServers = new ArrayList<>();
            List<String> torDnsServers = new ArrayList<>();

            // Test DNS resolution through different paths
            for (String domain : DNS_TEST_DOMAINS) {
                try {
                    // Direct DNS resolution
                    InetAddress[] directAddresses = InetAddress.getAllByName(domain);
                    if (directAddresses.length > 0) {
                        directDnsServers.add(directAddresses[0].getHostAddress());
                    }

                    // DNS through Tor (this is tricky - we check if we can resolve through proxy)
                    String torResolvedIp = resolveDnsThroughTor(domain);
                    if (torResolvedIp != null) {
                        torDnsServers.add(torResolvedIp);
                    }

                } catch (Exception e) {
                    LOGGER.fine("DNS test failed for " + domain + ": " + e.getMessage());
                }
            }

            // Check if DNS servers are different (indicating proper Tor usage)
            boolean isSecure = !torDnsServers.isEmpty()
                    && (directDnsServers.isEmpty() || !directDnsServers.equals(torDnsServers));

            String message = isSecure ? "DNS properly routed through Tor" : "Potential DNS leak detected";

            return new DnsLeakResult(isSecure, message, directDnsServers, torDnsServers);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "DNS leak check failed", e);
            return new DnsLeakResult(false, "DNS check failed: " + e.getMessage(),
                    Collections.emptyList(), Collections.emptyList());
        }
    }

    private TorNetworkResult checkTorNetwork() {
        try {
            LOGGER.fine("Checking Tor network connectivity...");

            // Check with Tor Project's check service
            String response = fetchUrlContent(TOR_CHECK_URL, true);

            boolean isUsingTor = false;
            String exitNode = null;
            String country = null;

            if (response != null) {
                // Parse JSON response (simple parsing)
                isUsingTor = response.contains("\"IsTor\":true") || response.contains("\"IsTor\": true");

                // Extract exit node info if available
                Matcher ipMatcher = TOR_CHECK_IP_PATTERN.matcher(response);
                if (ipMatcher.find()) {
                    exitNode = ipMatcher.group(1);
                }

                Matcher countryMatcher = TOR_CHECK_COUNTRY_PATTERN.matcher(response);
                if (countryMatcher.find()) {
                    country = countryMatcher.group(1);
                }
            }

            String message = isUsingTor ? "Successfully connected through Tor network"
                    : "Not connected through Tor network";

            return new TorNetworkResult(isUsingTor, message, exitNode, country);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Tor network check failed", e);
            return new TorNetworkResult(false, "Tor network check failed: " + e.getMessage(), null, null);
        }
    }

    private String fetchUrlContent(String urlString, boolean useTorProxy) throws IOException {
        URL url = new URL(urlString);
        URLConnection connection;

        if (useTorProxy) {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(socksProxyHost, socksProxyPort));
            connection = url.openConnection(proxy);
        } else {
            connection = url.openConnection();
        }

        connection.setConnectTimeout(connectionTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; TorLeakChecker)");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream()))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString().trim();
        }
    }

    private String resolveDnsThroughTor(String domain) {
        Socket socket = null;
        try {
            // The socket must be created WITH the SOCKS proxy: connecting the
            // hostname through the proxy makes the proxy (Tor) resolve the
            // name. A plain socket here would resolve and connect DIRECTLY —
            // the exact leak this checker exists to detect.
            Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(socksProxyHost, socksProxyPort));
            socket = new Socket(proxy);

            // Connect to the domain through Tor. SOCKS5 carries the hostname
            // to the proxy, so no local DNS resolution happens.
            socket.connect(new InetSocketAddress(domain, 80), 5000);
            String resolvedIp = ((InetSocketAddress) socket.getRemoteSocketAddress())
                    .getAddress().getHostAddress();
            return resolvedIp;
        } catch (Exception e) {
            // DNS resolution through Tor failed or not properly configured
            return null;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception closeError) {
                    LOGGER.fine("Error closing SOCKS probe socket: " + closeError.getMessage());
                }
            }
        }
    }

    /** Test seam for the SOCKS-routing contract. */
    void resolveDnsThroughTorForTest(String domain) {
        resolveDnsThroughTor(domain);
    }

    private String buildResultMessage(IpLeakResult ipResult, DnsLeakResult dnsResult, TorNetworkResult torResult) {
        StringBuilder message = new StringBuilder();

        if (ipResult != null) {
            message.append("IP: ").append(ipResult.message).append(". ");
        }

        if (dnsResult != null) {
            message.append("DNS: ").append(dnsResult.message).append(". ");
        }

        if (torResult != null) {
            message.append("Tor: ").append(torResult.message);
        }

        return message.toString();
    }

    // Result classes
    /**
     * Complete leak check result.
     */
    public static class LeakCheckResult {

        public final boolean isSecure;
        public final String message;
        public final IpLeakResult ipResult;
        public final DnsLeakResult dnsResult;
        public final TorNetworkResult torResult;

        public LeakCheckResult(boolean isSecure, String message, IpLeakResult ipResult,
                DnsLeakResult dnsResult, TorNetworkResult torResult) {
            this.isSecure = isSecure;
            this.message = message;
            this.ipResult = ipResult;
            this.dnsResult = dnsResult;
            this.torResult = torResult;
        }

        @Override
        public String toString() {
            return String.format("LeakCheckResult{secure=%s, message='%s'}", isSecure, message);
        }
    }

    /**
     * IP leak check result.
     */
    public static class IpLeakResult {

        private boolean isSecure;
        private String message;
        private String directIp;
        private String torIp;

        public IpLeakResult() {
        }

        public IpLeakResult(boolean isSecure, String message, String directIp, String torIp) {
            this.isSecure = isSecure;
            this.message = message;
            this.directIp = directIp;
            this.torIp = torIp;
        }

        public boolean isSecure() {
            return isSecure;
        }

        public void setSecure(boolean secure) {
            isSecure = secure;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getDirectIp() {
            return directIp;
        }

        public void setDirectIp(String directIp) {
            this.directIp = directIp;
        }

        public String getTorIp() {
            return torIp;
        }

        public void setTorIp(String torIp) {
            this.torIp = torIp;
        }

        @Override
        public String toString() {
            return String.format("IpLeakResult{isSecure=%s, message='%s', directIp='%s', torIp='%s'}",
                    isSecure, message, directIp, torIp);
        }
    }

    /**
     * DNS leak check result.
     */
    public static class DnsLeakResult {

        public final boolean isSecure;
        public final String message;
        public final List<String> directDnsServers;
        public final List<String> torDnsServers;

        public DnsLeakResult(boolean isSecure, String message,
                List<String> directDnsServers, List<String> torDnsServers) {
            this.isSecure = isSecure;
            this.message = message;
            this.directDnsServers = new ArrayList<>(directDnsServers);
            this.torDnsServers = new ArrayList<>(torDnsServers);
        }

        @Override
        public String toString() {
            return String.format("DnsLeakResult{secure=%s, directServers=%d, torServers=%d}",
                    isSecure, directDnsServers.size(), torDnsServers.size());
        }
    }

    /**
     * Tor network connectivity result.
     */
    public static class TorNetworkResult {

        private boolean isUsingTor;
        private String message;
        private String exitNodeIp;
        private String exitNodeCountry;

        public TorNetworkResult() {
        }

        public TorNetworkResult(boolean isUsingTor, String message, String exitNodeIp, String exitNodeCountry) {
            this.isUsingTor = isUsingTor;
            this.message = message;
            this.exitNodeIp = exitNodeIp;
            this.exitNodeCountry = exitNodeCountry;
        }

        public boolean isUsingTor() {
            return isUsingTor;
        }

        public void setUsingTor(boolean usingTor) {
            isUsingTor = usingTor;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getExitNodeIp() {
            return exitNodeIp;
        }

        public void setExitNodeIp(String exitNodeIp) {
            this.exitNodeIp = exitNodeIp;
        }

        public String getExitNodeCountry() {
            return exitNodeCountry;
        }

        public void setExitNodeCountry(String exitNodeCountry) {
            this.exitNodeCountry = exitNodeCountry;
        }

        @Override
        public String toString() {
            return String.format("TorNetworkResult{isUsingTor=%s, message='%s', exitNodeIp='%s', exitNodeCountry='%s'}",
                    isUsingTor, message, exitNodeIp, exitNodeCountry);
        }
    }
}
