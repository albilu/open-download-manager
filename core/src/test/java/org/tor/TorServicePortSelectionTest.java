package org.tor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TorServicePortSelectionTest {

    @TempDir
    Path tempDir;

    @Test
    void occupiedSocksAndControlPortsAdvanceToDistinctAvailablePorts() throws Exception {
        try (ServerSocket occupiedSocks = loopbackListener();
                ServerSocket occupiedControl = loopbackListener()) {
            int requestedSocks = occupiedSocks.getLocalPort();
            int requestedControl = occupiedControl.getLocalPort();
            TorService service = new TorService(
                    tempDir.resolve("missing-tor").toString(),
                    Map.of(
                            "SocksPort", requestedSocks + " IPv4Only",
                            "ControlPort", Integer.toString(requestedControl),
                            "DataDirectory", tempDir.resolve("data").toString()),
                    tempDir.resolve("torrc"));
            try {
                assertFalse(service.start().get(5, TimeUnit.SECONDS),
                        "the deliberately missing executable must not start");

                assertTrue(service.getSocksPort() > requestedSocks);
                assertTrue(service.getControlPort() > requestedControl);
                assertNotEquals(service.getSocksPort(), service.getControlPort());
                assertTrue(service.getConfiguration().get("SocksPort")
                        .endsWith(" IPv4Only"),
                        "port probing must preserve Tor listener flags");
            } finally {
                service.shutdown();
            }
        }
    }

    private static ServerSocket loopbackListener() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            ServerSocket socket = new ServerSocket();
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            if (socket.getLocalPort() < 65_500) {
                return socket;
            }
            socket.close();
        }
        throw new IllegalStateException("Could not reserve a test port below 65500");
    }
}
