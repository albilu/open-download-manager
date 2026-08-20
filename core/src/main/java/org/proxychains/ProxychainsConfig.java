package org.proxychains;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for creating and managing proxychains configuration files. This
 * class provides methods for generating and manipulating proxychains
 * configuration files with various proxy settings.
 */
public class ProxychainsConfig {

    private static final Logger LOGGER = Logger.getLogger(ProxychainsConfig.class.getName());

    public enum ProxyType {
        HTTP("http"),
        HTTPS("https"),
        SOCKS4("socks4"),
        SOCKS5("socks5");

        private final String value;

        ProxyType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static ProxyType fromString(String text) {
            for (ProxyType type : ProxyType.values()) {
                if (type.value.equalsIgnoreCase(text)
                        || type.name().equalsIgnoreCase(text)) {
                    return type;
                }
            }
            return null;
        }
    }

    public enum ChainType {
        DYNAMIC("dynamic_chain"),
        STRICT("strict_chain"),
        RANDOM("random_chain");

        private final String value;

        ChainType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private ChainType chainType;
    private boolean proxyDns;
    private int tcpReadTimeout;
    private int tcpConnectTimeout;
    private final List<ProxyEntry> proxyList;
    private Path configPath;

    /**
     * Creates a new ProxychainsConfig with default settings.
     */
    public ProxychainsConfig() {
        this.chainType = ChainType.DYNAMIC;
        this.proxyDns = true;
        this.tcpReadTimeout = 15000;
        this.tcpConnectTimeout = 8000;
        this.proxyList = new ArrayList<>();
    }

    /**
     * Creates a new ProxychainsConfig from an existing config file.
     *
     * @param configFile Path to existing proxychains config file
     * @throws IOException if the file cannot be read
     */
    public ProxychainsConfig(Path configFile) throws IOException {
        this();
        this.configPath = configFile;
        loadFromFile(configFile);
    }

    /**
     * Loads configuration from a file.
     *
     * @param configFile Path to the configuration file
     * @throws IOException if the file cannot be read
     */
    private void loadFromFile(Path configFile) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue; // Skip comments and empty lines
                }

                if (line.startsWith("dynamic_chain")) {
                    this.chainType = ChainType.DYNAMIC;
                } else if (line.startsWith("strict_chain")) {
                    this.chainType = ChainType.STRICT;
                } else if (line.startsWith("random_chain")) {
                    this.chainType = ChainType.RANDOM;
                } else if (line.startsWith("proxy_dns")) {
                    this.proxyDns = true;
                } else if (line.startsWith("tcp_read_time_out")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 1) {
                        try {
                            this.tcpReadTimeout = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException e) {
                            LOGGER.log(Level.WARNING, "Invalid tcp_read_time_out value: " + parts[1]);
                        }
                    }
                } else if (line.startsWith("tcp_connect_time_out")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 1) {
                        try {
                            this.tcpConnectTimeout = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException e) {
                            LOGGER.log(Level.WARNING, "Invalid tcp_connect_time_out value: " + parts[1]);
                        }
                    }
                } else if (line.startsWith("http") || line.startsWith("socks")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        try {
                            ProxyType type = ProxyType.fromString(parts[0]);
                            String host = parts[1];
                            int port = Integer.parseInt(parts[2]);

                            // Optional username and password
                            String username = parts.length > 3 ? parts[3] : null;
                            String password = parts.length > 4 ? parts[4] : null;

                            ProxyEntry entry = new ProxyEntry(type, host, port, username, password);
                            proxyList.add(entry);
                        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                            LOGGER.log(Level.WARNING, "Invalid proxy entry: " + line, e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Sets the chain type for this configuration.
     *
     * @param chainType The chain type to use
     * @return This ProxychainsConfig instance
     */
    public ProxychainsConfig setChainType(ChainType chainType) {
        this.chainType = chainType;
        return this;
    }

    /**
     * Sets whether to proxy DNS requests.
     *
     * @param proxyDns Whether to proxy DNS requests
     * @return This ProxychainsConfig instance
     */
    public ProxychainsConfig setProxyDns(boolean proxyDns) {
        this.proxyDns = proxyDns;
        return this;
    }

    /**
     * Sets the TCP read timeout in milliseconds.
     *
     * @param tcpReadTimeout The TCP read timeout
     * @return This ProxychainsConfig instance
     */
    public ProxychainsConfig setTcpReadTimeout(int tcpReadTimeout) {
        this.tcpReadTimeout = tcpReadTimeout;
        return this;
    }

    /**
     * Sets the TCP connect timeout in milliseconds.
     *
     * @param tcpConnectTimeout The TCP connect timeout
     * @return This ProxychainsConfig instance
     */
    public ProxychainsConfig setTcpConnectTimeout(int tcpConnectTimeout) {
        this.tcpConnectTimeout = tcpConnectTimeout;
        return this;
    }

    /**
     * Adds a proxy to the proxy list.
     *
     * @param type The proxy type
     * @param host The proxy host
     * @param port The proxy port
     * @return This ProxychainsConfig instance
     */
    public ProxychainsConfig addProxy(ProxyType type, String host, int port) {
        proxyList.add(new ProxyEntry(type, host, port, null, null));
        return this;
    }

    /**
     * Adds a proxy with authentication to the proxy list.
     *
     * @param type     The proxy type
     * @param host     The proxy host
     * @param port     The proxy port
     * @param username The username for authentication
     * @param password The password for authentication
     * @return This ProxychainsConfig instance
     */
    public ProxychainsConfig addProxy(ProxyType type, String host, int port, String username, String password) {
        proxyList.add(new ProxyEntry(type, host, port, username, password));
        return this;
    }

    /**
     * Clears all proxies from the proxy list.
     *
     * @return This ProxychainsConfig instance
     */
    public ProxychainsConfig clearProxies() {
        proxyList.clear();
        return this;
    }

    /**
     * Gets the chain type for this configuration.
     *
     * @return The chain type
     */
    public ChainType getChainType() {
        return chainType;
    }

    /**
     * Gets whether to proxy DNS requests.
     *
     * @return true if DNS requests are proxied, false otherwise
     */
    public boolean isProxyDns() {
        return proxyDns;
    }

    /**
     * Gets the TCP read timeout in milliseconds.
     *
     * @return The TCP read timeout
     */
    public int getTcpReadTimeout() {
        return tcpReadTimeout;
    }

    /**
     * Gets the TCP connect timeout in milliseconds.
     *
     * @return The TCP connect timeout
     */
    public int getTcpConnectTimeout() {
        return tcpConnectTimeout;
    }

    /**
     * Gets the list of proxies in this configuration.
     *
     * @return An unmodifiable list of proxy entries
     */
    public List<ProxyEntry> getProxyList() {
        return Collections.unmodifiableList(proxyList);
    }

    /**
     * Gets the path to the configuration file, if available.
     *
     * @return The config path, or null if not set
     */
    public Path getConfigPath() {
        return configPath;
    }

    /**
     * Creates a temporary configuration file with the current settings.
     *
     * @return Path to the created configuration file
     * @throws IOException if the configuration file cannot be created
     */
    public Path createTempConfig() throws IOException {
        Path configFile = Files.createTempFile("proxychains_", ".conf");
        saveToFile(configFile);
        configFile.toFile().deleteOnExit(); // Clean up when the JVM exits
        this.configPath = configFile;
        return configFile;
    }

    /**
     * Saves the current configuration to the specified file.
     *
     * @param configFile Path to the configuration file
     * @throws IOException if the configuration file cannot be written
     */
    public void saveToFile(Path configFile) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(configFile.toFile()))) {
            // Write chain type
            writer.write(chainType.getValue());
            writer.newLine();

            // Write proxy DNS setting
            if (proxyDns) {
                writer.write("proxy_dns");
                writer.newLine();
            }

            // Write timeout settings
            writer.write("tcp_read_time_out " + tcpReadTimeout);
            writer.newLine();
            writer.write("tcp_connect_time_out " + tcpConnectTimeout);
            writer.newLine();

            writer.newLine();
            writer.write("[ProxyList]");
            writer.newLine();

            // Write proxy entries
            for (ProxyEntry proxy : proxyList) {
                writer.write(proxy.toString());
                writer.newLine();
            }
        }
    }

    /**
     * Parses a proxy string in the format "type://[user:pass@]host:port" and
     * adds it to this configuration.
     *
     * @param proxyString The proxy string to parse
     * @return This ProxychainsConfig instance
     */
    public ProxychainsConfig parseProxyString(String proxyString) {
        try {
            if (proxyString == null || proxyString.isEmpty()) {
                return this;
            }

            // Parse proxy string (e.g. "socks5://user:pass@127.0.0.1:9050")
            String[] parts = proxyString.split("://");
            if (parts.length != 2) {
                LOGGER.warning("Invalid proxy string format: " + proxyString);
                return this;
            }

            // Get proxy type
            String typeStr = parts[0].toLowerCase();
            ProxyType type = ProxyType.fromString(typeStr);
            if (type == null) {
                LOGGER.warning("Unknown proxy type: " + typeStr);
                return this;
            }

            // Parse auth, host and port
            String hostPart = parts[1];
            String username = null;
            String password = null;

            // Check if there's auth info
            if (hostPart.contains("@")) {
                String[] authHostParts = hostPart.split("@");
                if (authHostParts.length == 2) {
                    String[] authParts = authHostParts[0].split(":");
                    if (authParts.length == 2) {
                        username = authParts[0];
                        password = authParts[1];
                    }
                    hostPart = authHostParts[1];
                }
            }

            // Parse host and port
            String[] hostPortParts = hostPart.split(":");
            if (hostPortParts.length != 2) {
                LOGGER.warning("Invalid host:port format: " + hostPart);
                return this;
            }

            String host = hostPortParts[0];
            int port = Integer.parseInt(hostPortParts[1]);

            // Add the proxy
            if (username != null && password != null) {
                addProxy(type, host, port, username, password);
            } else {
                addProxy(type, host, port);
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error parsing proxy string: " + proxyString, e);
        }

        return this;
    }

    /**
     * Represents a proxy entry in the proxychains configuration.
     */
    public static class ProxyEntry {

        private final ProxyType type;
        private final String host;
        private final int port;
        private final String username;
        private final String password;

        public ProxyEntry(ProxyType type, String host, int port, String username, String password) {
            this.type = type;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }

        public ProxyType getType() {
            return type;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public boolean hasAuthentication() {
            return username != null && !username.isEmpty()
                    && password != null && !password.isEmpty();
        }

        @Override
        public String toString() {
            if (hasAuthentication()) {
                return String.format("%s %s %d %s %s", type.getValue(), host, port, username, password);
            } else {
                return String.format("%s %s %d", type.getValue(), host, port);
            }
        }
    }

    /**
     * Gets the default system-wide proxychains config path.
     *
     * @return The path to the default proxychains config
     */
    public static Path getDefaultConfigPath() {
        // Check common locations
        Path[] possiblePaths = {
                Paths.get("/etc/proxychains.conf"),
                Paths.get("/etc/proxychains4.conf"),
                Paths.get("/usr/local/etc/proxychains.conf"),
                Paths.get(System.getProperty("user.home"), ".proxychains/proxychains.conf")
        };

        for (Path path : possiblePaths) {
            if (Files.exists(path)) {
                return path;
            }
        }

        return null;
    }
}
