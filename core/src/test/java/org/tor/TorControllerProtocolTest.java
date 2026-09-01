package org.tor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the TorController wire protocol against an in-process fake
 * control-port server. No Tor daemon and no network required: the fake
 * server asserts the exact commands the controller must send and answers
 * with control-spec responses.
 */
@DisplayName("TorController control-port protocol")
class TorControllerProtocolTest {

    @TempDir
    Path tempDir;

    /** Minimal single-connection fake control server with scripted replies. */
    private static final class FakeControlServer implements AutoCloseable {
        final ServerSocket serverSocket;
        final ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
        volatile PrintWriter writer;
        volatile Socket connection;
        final CountDownLatch clientConnected = new CountDownLatch(1);
        volatile boolean authOk = true;
        // reply groups: one group per received command line; a group's lines
        // are written together so multi-line control replies stay atomic
        final ConcurrentLinkedQueue<java.util.List<String>> scriptedReplies = new ConcurrentLinkedQueue<>();

        FakeControlServer() throws IOException {
            serverSocket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::serve);
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void reply(String... lines) {
            scriptedReplies.add(java.util.List.of(lines));
        }

        private void serve() {
            try {
                Socket socket = serverSocket.accept();
                connection = socket;
                writer = new PrintWriter(socket.getOutputStream(), true);
                clientConnected.countDown();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    received.add(line);
                    if (line.equals("QUIT")) {
                        break;
                    }
                    java.util.List<String> group = scriptedReplies.poll();
                    if (group != null) {
                        for (String reply : group) {
                            writer.println(reply);
                        }
                    }
                }
            } catch (IOException ignored) {
                // socket closed by test teardown
            }
        }

        boolean receivedCommandStartingWith(String prefix) throws InterruptedException {
            return waitForLineStartingWith(prefix) != null;
        }

        String waitForLineStartingWith(String prefix) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                for (String line : received) {
                    if (line.startsWith(prefix)) {
                        return line;
                    }
                }
                Thread.sleep(20);
            }
            return null;
        }

        @Override
        public void close() {
            try {
                if (connection != null) {
                    connection.close();
                }
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    @DisplayName("password authentication completes the control handshake")
    void passwordAuthentication() throws Exception {
        try (FakeControlServer server = new FakeControlServer()) {
            server.reply("250 OK"); // AUTHENTICATE
            TorController controller = new TorController("127.0.0.1", server.port(), "sekrit", 5000);
            try {
                assertTrue(controller.connect().get(10, TimeUnit.SECONDS));
                assertTrue(controller.isConnected());
                assertTrue(server.receivedCommandStartingWith("AUTHENTICATE \"sekrit\""),
                        "password auth must quote the secret: " + server.received);
            } finally {
                controller.disconnect();
            }
        }
    }

    @Test
    @DisplayName("rejected password authentication fails the connection")
    void rejectedPasswordFails() throws Exception {
        try (FakeControlServer server = new FakeControlServer()) {
            server.reply("515 Authentication failed");
            TorController controller = new TorController("127.0.0.1", server.port(), "wrong", 5000);
            try {
                assertFalse(controller.connect().get(10, TimeUnit.SECONDS));
                assertFalse(controller.isConnected());
            } finally {
                controller.disconnect();
            }
        }
    }

    @Test
    @DisplayName("cookie authentication reads the data-directory cookie and sends it as hex")
    void cookieAuthentication() throws Exception {
        byte[] cookie = new byte[32];
        new SecureRandom().nextBytes(cookie);
        Files.write(tempDir.resolve("control_auth_cookie"), cookie);
        StringBuilder expectedHex = new StringBuilder();
        for (byte b : cookie) {
            expectedHex.append(String.format("%02X", b));
        }

        try (FakeControlServer server = new FakeControlServer()) {
            server.reply("250 OK");
            TorController controller = new TorController("127.0.0.1", server.port(), null, 5000, tempDir);
            try {
                assertTrue(controller.connect().get(10, TimeUnit.SECONDS));
                assertTrue(server.receivedCommandStartingWith("AUTHENTICATE " + expectedHex),
                        "cookie must be sent as uppercase hex");
            } finally {
                controller.disconnect();
            }
        }
    }

    @Test
    @DisplayName("an invalid cookie length is ignored and fallback auth is attempted")
    void invalidCookieFallsBack() throws Exception {
        Files.write(tempDir.resolve("control_auth_cookie"), new byte[8]);

        try (FakeControlServer server = new FakeControlServer()) {
            server.reply("250 OK"); // for the plain AUTHENTICATE fallback
            TorController controller = new TorController("127.0.0.1", server.port(), null, 5000, tempDir);
            try {
                assertTrue(controller.connect().get(10, TimeUnit.SECONDS));
                assertTrue(server.receivedCommandStartingWith("AUTHENTICATE"),
                        "fallback auth must still be attempted");
            } finally {
                controller.disconnect();
            }
        }
    }

    @Test
    @DisplayName("connecting to a dead port reports failure")
    void deadPortFails() throws Exception {
        try (ServerSocket hole = new ServerSocket(0)) {
            int deadPort = hole.getLocalPort();
            hole.close(); // now guaranteed free
            TorController controller = new TorController("127.0.0.1", deadPort, null, 1000);
            try {
                assertFalse(controller.connect().get(10, TimeUnit.SECONDS));
                assertFalse(controller.isConnected());
            } finally {
                controller.shutdown();
            }
        }
    }

    @Test
    @DisplayName("changeIp issues SIGNAL NEWNYM and reports success on 250")
    void changeIpSendsNewnym() throws Exception {
        try (FakeControlServer server = new FakeControlServer()) {
            server.reply("250 OK"); // AUTHENTICATE
            server.reply("250 OK"); // SIGNAL NEWNYM
            TorController controller = new TorController("127.0.0.1", server.port(), "pw", 5000);
            try {
                assertTrue(controller.connect().get(10, TimeUnit.SECONDS));
                assertTrue(controller.changeIp().get(10, TimeUnit.SECONDS));
                assertTrue(server.receivedCommandStartingWith("SIGNAL NEWNYM"));
            } finally {
                controller.disconnect();
            }
        }
    }

    @Test
    @DisplayName("changeIp reports failure when the control port errors")
    void changeIpReportsErrors() throws Exception {
        try (FakeControlServer server = new FakeControlServer()) {
            server.reply("250 OK");
            server.reply("552 Unrecognized signal");
            TorController controller = new TorController("127.0.0.1", server.port(), "pw", 5000);
            try {
                assertTrue(controller.connect().get(10, TimeUnit.SECONDS));
                assertFalse(controller.changeIp().get(10, TimeUnit.SECONDS));
            } finally {
                controller.disconnect();
            }
        }
    }

    @Test
    @DisplayName("closeCircuit succeeds on 250 and fails on an error reply")
    void closeCircuit() throws Exception {
        try (FakeControlServer server = new FakeControlServer()) {
            server.reply("250 OK"); // auth
            server.reply("250 OK"); // CLOSECIRCUIT ok
            server.reply("512 Unrecognized circuit"); // CLOSECIRCUIT bad
            TorController controller = new TorController("127.0.0.1", server.port(), "pw", 5000);
            try {
                assertTrue(controller.connect().get(10, TimeUnit.SECONDS));
                assertTrue(controller.closeCircuit("7").get(10, TimeUnit.SECONDS));
                assertFalse(controller.closeCircuit("999").get(10, TimeUnit.SECONDS));
            } finally {
                controller.disconnect();
            }
        }
    }

    @Test
    @DisplayName("circuit info lines are parsed into CircuitInfo records")
    void circuitInfoParsing() throws Exception {
        try (FakeControlServer server = new FakeControlServer()) {
            server.reply("250 OK"); // auth
            server.reply(
                    "250-5 BUILT $AAAAAAAA~relayOne BUILD_FLAGS=IS_NEVER PURPOSE=GENERAL",
                    "250 OK");
            TorController controller = new TorController("127.0.0.1", server.port(), "pw", 5000);
            try {
                assertTrue(controller.connect().get(10, TimeUnit.SECONDS));
                List<TorController.CircuitInfo> circuits = controller.getCircuitInfo().get(10, TimeUnit.SECONDS);
                // The controller looks for lines starting with "250-"/"250+";
                // our scripted line must produce one parsed circuit
                assertEquals(1, circuits.size());
                TorController.CircuitInfo circuit = circuits.get(0);
                assertEquals("5", circuit.id);
                assertEquals("BUILT", circuit.status);
                assertEquals("$AAAAAAAA~relayOne", circuit.path);
                assertEquals("IS_NEVER", circuit.buildFlags);
                assertEquals("GENERAL", circuit.purpose);
                assertTrue(circuit.toString().contains("BUILT"));
            } finally {
                controller.disconnect();
            }
        }
    }

    @Test
    @DisplayName("setConfiguration quotes values per the control-spec escaping rules")
    void setConfigurationQuotesValues() throws Exception {
        try (FakeControlServer server = new FakeControlServer()) {
            server.reply("250 OK"); // AUTHENTICATE
            server.reply("250 OK"); // SETCONF SocksPort
            server.reply("250 OK"); // SETCONF Bridge
            TorController controller = new TorController("127.0.0.1", server.port(), "pw", 5000);
            try {
                assertTrue(controller.connect().get(10, TimeUnit.SECONDS));
                assertTrue(controller.setConfiguration("SocksPort", "9050").get(10, TimeUnit.SECONDS));

                assertNotNull(server.waitForLineStartingWith("SETCONF SocksPort"),
                        "simple values must be wrapped in quotes");

                assertTrue(controller.setConfiguration("Bridge", "a\"b\\c d").get(10, TimeUnit.SECONDS));
                assertNotNull(server.waitForLineStartingWith("SETCONF Bridge"),
                        "escaped SETCONF must arrive");
                assertTrue(server.received.contains("SETCONF Bridge=\"a\\\"b\\\\c d\""),
                        "quotes and backslashes must be escaped, spaces preserved: " + server.received);
            } finally {
                controller.disconnect();
            }
        }
    }

    @Test
    @DisplayName("getConfiguration extracts the value from a 250 reply")
    void getConfigurationParsesReply() throws Exception {
        try (FakeControlServer server = new FakeControlServer()) {
            server.reply("250 OK"); // auth
            server.reply("250 SocksPort=9050");
            TorController controller = new TorController("127.0.0.1", server.port(), "pw", 5000);
            try {
                assertTrue(controller.connect().get(10, TimeUnit.SECONDS));
                assertEquals("9050", controller.getConfiguration("SocksPort").get(10, TimeUnit.SECONDS));
            } finally {
                controller.disconnect();
            }
        }
    }

    @Test
    @DisplayName("commands without an active connection fail fast")
    void commandsWithoutConnectionFail() throws Exception {
        TorController controller = new TorController("127.0.0.1", 1, null, 250);
        try {
            assertFalse(controller.changeIp().get(15, TimeUnit.SECONDS),
                    "unconnectable control port must yield false, not an exception");
            assertTrue(controller.getCircuitInfo().get(15, TimeUnit.SECONDS).isEmpty());
            assertFalse(controller.setConfiguration("Key", "value").get(15, TimeUnit.SECONDS));
            assertNull(controller.getConfiguration("Key").get(15, TimeUnit.SECONDS));
        } finally {
            controller.shutdown();
        }
    }

    @Test
    @DisplayName("disconnect sends QUIT and marks the controller disconnected")
    void disconnectSendsQuit() throws Exception {
        try (FakeControlServer server = new FakeControlServer()) {
            server.reply("250 OK"); // auth
            TorController controller = new TorController("127.0.0.1", server.port(), "pw", 5000);
            assertTrue(controller.connect().get(10, TimeUnit.SECONDS));
            controller.disconnect();
            assertFalse(controller.isConnected());
            assertNotNull(server.waitForLineStartingWith("QUIT"),
                    "disconnect must say goodbye to the daemon");
        }
    }

    @Test
    @DisplayName("connect after shutdown is refused without touching the network")
    void connectAfterShutdownIsRefused() throws Exception {
        TorController controller = new TorController("127.0.0.1", 1, null, 250);
        controller.shutdown();
        assertFalse(controller.connect().get(5, TimeUnit.SECONDS),
                "a shutting-down controller must refuse new connections");
        assertFalse(controller.isConnected());
    }

    @Test
    @DisplayName("shutdown is idempotent")
    void shutdownIsIdempotent() {
        TorController controller = new TorController("127.0.0.1", 1, null, 250);
        controller.shutdown();
        controller.shutdown();
        assertFalse(controller.isConnected());
    }
}
