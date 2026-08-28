package org.tor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cookie authentication against a fake control port. Tor writes
 * {@code control_auth_cookie} into its DataDirectory, so the controller
 * must read the cookie from the configured data directory first and fall
 * back to the legacy locations only when that file is absent.
 */
class TorControllerCookieAuthTest {

    private ServerSocket serverSocket;
    private ExecutorService serverExecutor;
    private final CopyOnWriteArrayList<String> receivedLines = new CopyOnWriteArrayList<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        serverExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-tor-control");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    private int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptOneConnection(String expectedHex) {
        serverExecutor.submit(() -> {
            try (Socket socket = serverSocket.accept();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    receivedLines.add(line);
                    if (line.startsWith("AUTHENTICATE")) {
                        boolean ok = ("AUTHENTICATE " + expectedHex).equals(line);
                        writer.printf("%s\r\n", ok ? "250 OK" : "515 Authentication failed");
                        writer.flush();
                    }
                }
            } catch (Exception e) {
                // client closed the socket
            }
        });
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }

    private static byte[] sampleCookie() {
        byte[] cookie = new byte[32];
        for (int i = 0; i < cookie.length; i++) {
            cookie[i] = (byte) (i + 1);
        }
        return cookie;
    }

    @Test
    void authenticatesWithCookieFromConfiguredDataDirectory() throws Exception {
        Path dataDir = tempDir.resolve("tor-data");
        Files.createDirectories(dataDir);
        byte[] cookie = sampleCookie();
        Files.write(dataDir.resolve("control_auth_cookie"), cookie);

        acceptOneConnection(hex(cookie));
        TorController controller = new TorController("127.0.0.1", port(), null, 5000, dataDir);
        try {
            CompletableFuture<Boolean> connected = controller.connect();
            assertTrue(connected.get(10, TimeUnit.SECONDS),
                    "controller must authenticate with the cookie from the configured data directory");
            assertEquals("AUTHENTICATE " + hex(cookie), receivedLines.get(0));
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void legacyHomeLocationIsUsedWhenDataDirectoryHasNoCookie() throws Exception {
        Path dataDir = tempDir.resolve("empty-data-dir");
        Files.createDirectories(dataDir);

        byte[] cookie = sampleCookie();
        Path legacyDir = tempDir.resolve("fake-home").resolve(".tor");
        Files.createDirectories(legacyDir);
        Files.write(legacyDir.resolve("control_auth_cookie"), cookie);

        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("fake-home").toString());
        acceptOneConnection(hex(cookie));
        TorController controller = new TorController("127.0.0.1", port(), null, 5000, dataDir);
        try {
            assertTrue(controller.connect().get(10, TimeUnit.SECONDS),
                    "legacy ~/.tor fallback must still work when the data directory has no cookie");
            assertEquals("AUTHENTICATE " + hex(cookie), receivedLines.get(0));
        } finally {
            controller.shutdown();
            System.setProperty("user.home", realHome);
        }
    }

    @Test
    void connectFailsWhenNoCookieIsFoundAnywhere() throws Exception {
        Path dataDir = tempDir.resolve("empty-data-dir");
        Files.createDirectories(dataDir);

        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("fake-home-no-cookie").toString());
        acceptOneConnection("");
        TorController controller = new TorController("127.0.0.1", port(), null, 5000, dataDir);
        try {
            assertFalse(controller.connect().get(10, TimeUnit.SECONDS),
                    "without any cookie the authentication must fail");
        } finally {
            controller.shutdown();
            System.setProperty("user.home", realHome);
        }
    }
}
