package org.jackett;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;

class JackettServiceTest {
    @TempDir Path directory;

    private JackettService service(GlobalSettings settings, boolean ready) throws Exception {
        Path install = directory.resolve("installed");
        Path executable = install.resolve("Jackett/jackett");
        Files.createDirectories(executable.getParent());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        Files.writeString(executable, "#!/bin/sh\nexec " + quote(java) + " -cp " + quote(cp)
                + " 'org.jackett.JackettServiceTest$Daemon' " + ready + " \"$@\"\n");
        executable.toFile().setExecutable(true);
        return new JackettService(settings, install, directory.resolve("config"), directory.resolve("log"), Duration.ofSeconds(5));
    }

    static int freePort() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static String quote(String text) { return "'" + text.replace("'", "'\"'\"'") + "'"; }

    @Test void startsAuthenticatesAndStopsItsExactProcessAndEndpoint() throws Exception {
        GlobalSettings settings = new GlobalSettings(); settings.setProperty(JackettSettings.PORT, Integer.toString(freePort()));
        JackettService service = service(settings, true);
        try {
            service.start(); service.start();
            assertEquals(JackettService.State.RUNNING, service.status().state());
            assertEquals("0.24.99 — Running", service.status().message());
            JackettClient client = service.client(); assertTrue(client.isReady());
            long pid = Long.parseLong(Files.readString(directory.resolve("config/pid")));
            assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
            service.stop();
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(client.isReady()); assertEquals(JackettService.State.STOPPED, service.status().state());
            settings.setProperty(JackettSettings.PORT, Integer.toString(freePort()));
            service.start(); assertTrue(service.client().isReady());
        } finally { service.close(); }
        assertThrows(java.util.concurrent.CancellationException.class, service::start);
    }

    @Test void occupiedPortIsNeverClaimedOrStopped() throws Exception {
        try (ServerSocket unrelated = new ServerSocket(0)) {
            GlobalSettings settings = new GlobalSettings(); settings.setProperty(JackettSettings.PORT, Integer.toString(unrelated.getLocalPort()));
            try (JackettService service = service(settings, true)) {
                IOException error = assertThrows(IOException.class, service::start);
                assertTrue(error.getMessage().contains("in use"));
                service.stop(); assertFalse(unrelated.isClosed());
                assertFalse(Files.exists(directory.resolve("config/pid")));
            }
        }
    }

    @Test void closingDuringStartupCancelsReadinessAndKillsTheChild() throws Exception {
        GlobalSettings settings = new GlobalSettings(); settings.setProperty(JackettSettings.PORT, Integer.toString(freePort()));
        JackettService service = service(settings, false);
        CompletableFuture<Void> start = CompletableFuture.runAsync(() -> {
            try { service.start(); } catch (IOException error) { throw new RuntimeException(error); }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(directory.resolve("config/pid")) && System.nanoTime() < deadline) { Thread.sleep(20); }
            assertTrue(Files.exists(directory.resolve("config/pid")));
            long pid = Long.parseLong(Files.readString(directory.resolve("config/pid")));
            service.close();
            assertThrows(Exception.class, () -> start.get(5, TimeUnit.SECONDS));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally { service.close(); }
    }

    /** Separate real OS process used to exercise ownership and cancellation without network indexers. */
    public static class Daemon {
        public static void main(String[] args) throws Exception {
            int port = 0; Path config = null;
            for (int i = 1; i < args.length - 1; i++) {
                if (args[i].equals("--Port")) { port = Integer.parseInt(args[i + 1]); }
                if (args[i].equals("--DataFolder")) { config = Path.of(args[i + 1]); }
            }
            Files.writeString(config.resolve("ServerConfig.json"), "{\"APIKey\":\"generated\"}");
            Files.writeString(config.resolve("pid"), Long.toString(ProcessHandle.current().pid()));
            var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            boolean ready = Boolean.parseBoolean(args[0]);
            server.createContext("/", exchange -> {
                boolean authenticated = exchange.getRequestURI().getQuery().contains("apikey=generated");
                byte[] body = (exchange.getRequestURI().getPath().equals("/api/v2.0/server/config")
                        ? "{\"app_version\":\"v0.24.99\"}" : "<caps/>").getBytes();
                exchange.sendResponseHeaders(ready && authenticated ? 200 : 503, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
        }
    }
}
