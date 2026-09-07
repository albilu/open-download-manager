package org.manager.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.proxychains.ProxychainsConfig;

/** Owns a private, strict proxychains route for a TCP-only native operation. */
public final class ProxiedCommand implements AutoCloseable {
    private final Path config;
    private final List<String> prefix;

    private ProxiedCommand(Path config, List<String> prefix) {
        this.config = config;
        this.prefix = prefix;
    }

    public static ProxiedCommand prepare(String address) throws IOException {
        String route = NetworkProcessPolicy.proxyAddress(address);
        if (route.isEmpty()) { return new ProxiedCommand(null, List.of()); }
        if (route.startsWith("https://")) {
            throw new IOException("This native tool cannot use a TLS HTTPS proxy");
        }
        Path config = ProxychainsConfig.forProxyAddress(route).createTempConfig();
        return new ProxiedCommand(config, List.of(ToolPaths.proxychains(), "-q", "-f", config.toString()));
    }

    public List<String> wrap(List<String> command) {
        ArrayList<String> result = new ArrayList<>(prefix);
        result.addAll(command);
        return result;
    }

    @Override public void close() throws IOException {
        if (config != null) { Files.deleteIfExists(config); }
    }
}
