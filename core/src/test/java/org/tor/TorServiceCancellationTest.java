package org.tor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TorServiceCancellationTest {

    @TempDir
    Path tempDir;

    @Test
    void stopCancelsAProcessThatIsStillBootstrappingAndShutdownStillCompletes() throws Exception {
        Path marker = tempDir.resolve("started");
        Path executable = tempDir.resolve("fake-tor");
        Files.writeString(executable, """
                #!/bin/sh
                trap 'exit 0' TERM INT
                touch '%s'
                echo 'Bootstrapped 5%% (conn): Connecting' >&2
                while :; do sleep 1; done
                """.formatted(marker));
        Files.setPosixFilePermissions(executable,
                PosixFilePermissions.fromString("rwx------"));

        int socksPort = freePort();
        int controlPort = freePort();
        TorService service = new TorService(executable.toString(), Map.of(
                "SocksPort", String.valueOf(socksPort),
                "ControlPort", String.valueOf(controlPort),
                "DataDirectory", tempDir.resolve("data").toString()),
                tempDir.resolve("torrc"));
        CountDownLatch bootstrapUpdate = new CountDownLatch(1);
        AtomicInteger observedProgress = new AtomicInteger(-1);
        service.addListener(event -> {
            if (event == TorService.TorServiceEvent.BOOTSTRAP_PROGRESS) {
                observedProgress.set(service.getBootstrapProgress());
                bootstrapUpdate.countDown();
            }
        });

        try {
            CompletableFuture<Boolean> starting = service.start();
            assertTrue(awaitFile(marker), "the fake Tor process should have started");
            assertTrue(bootstrapUpdate.await(3, TimeUnit.SECONDS),
                    "the fake Tor bootstrap percentage should be published");
            assertTrue(service.isStarting());
            assertEquals(5, observedProgress.get(),
                    "listeners should observe the parsed bootstrap percentage");

            assertTrue(service.stop());
            assertFalse(starting.get(8, TimeUnit.SECONDS),
                    "a stop request must invalidate a pending bootstrap");
            assertFalse(service.isRunning());
            assertFalse(service.isStarting());
            assertEquals(0, service.getBootstrapProgress(),
                    "stopping Tor should clear stale bootstrap progress");

            // stopInternal marks process monitoring as stopping. That must not
            // make the later whole-service shutdown return early.
            service.shutdown();
            service.getShutdownFuture().get(5, TimeUnit.SECONDS);
            assertTrue(service.getShutdownFuture().isDone());
        } finally {
            service.shutdown();
        }
    }

    private static boolean awaitFile(Path path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) {
                return true;
            }
            Thread.sleep(20);
        }
        return Files.exists(path);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
