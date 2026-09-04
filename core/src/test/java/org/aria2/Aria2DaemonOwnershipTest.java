package org.aria2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;

/**
 * Daemon-ownership contract for {@link Aria2Client}:
 *
 * 1. An RPC endpoint occupied by a daemon is adopted ONLY when the user
 *    explicitly configured an RPC secret and an authenticated probe with
 *    that secret succeeds. A daemon with no configured secret is rejected:
 *    startup fails without sending download data.
 * 2. Shutting down while attached to an EXTERNAL_AUTHENTICATED daemon
 *    closes ODM's transports only — the daemon never receives
 *    aria2.shutdown.
 * 3. Shutting down an ODM_STARTED daemon sends the authenticated shutdown
 *    RPC and reaps the daemon.
 * 4. Generic extra arguments can never override the RPC binding, port,
 *    secret, or enable-RPC controls ODM owns.
 */
@DisplayName("Aria2 daemon ownership: adopt authenticated daemons only, shutdown by ownership")
class Aria2DaemonOwnershipTest {

    private static final int BASE_PORT = 7310;
    private static final String EXTERNAL_SECRET = "external-daemon-secret-42";

    @TempDir
    Path tempDir;

    private Path downloadDir;
    private final List<Process> externalDaemons = new ArrayList<>();
    private Aria2Client odmClient;

    @BeforeEach
    void setUp() throws IOException {
        downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);
        ApplicationContext.initialize();
    }

    @AfterEach
    void tearDown() {
        if (odmClient != null) {
            try {
                odmClient.disconnectWebSocket();
            } catch (Exception e) {
                // ignore
            }
            try {
                odmClient.stopAria2c();
            } catch (Exception e) {
                // ignore
            }
        }
        for (Process daemon : externalDaemons) {
            daemon.destroy();
            try {
                if (!daemon.waitFor(5, TimeUnit.SECONDS)) {
                    daemon.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                daemon.destroyForcibly();
            }
        }
    }

    /**
     * Launches a foreground (non-daemonized) aria2c RPC process so the
     * returned handle reliably controls its lifetime from the test.
     */
    private Process startExternalDaemon(int port, String secret, String... extraFlags) throws Exception {
        String aria2Path = ApplicationContext.getToolPath("aria2");
        List<String> cmd = new ArrayList<>(Arrays.asList(
                aria2Path,
                "--enable-rpc",
                "--rpc-listen-all=false",
                "--rpc-listen-port=" + port,
                "--dir=" + downloadDir));
        if (secret != null) {
            cmd.add("--rpc-secret=" + secret);
        }
        cmd.addAll(Arrays.asList(extraFlags));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        externalDaemons.add(process);

        // Wait for the daemon to accept RPC (max 10s)
        Aria2Client probe = new Aria2Client(aria2Path, rpcUrl(port), secret);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                probe.getVersion();
                return process;
            } catch (Exception e) {
                Thread.sleep(200);
            }
        }
        throw new IOException("External test daemon on port " + port + " never became responsive");
    }

    private static String rpcUrl(int port) {
        return "http://localhost:" + port + "/jsonrpc";
    }

    @Test
    @DisplayName("An occupied endpoint with no configured secret is rejected, not adopted")
    @Timeout(30)
    void occupiedEndpointWithoutConfiguredSecretIsRejected() throws Exception {
        int port = BASE_PORT;
        startExternalDaemon(port, null);

        odmClient = new Aria2Client(ApplicationContext.getToolPath("aria2"), rpcUrl(port), null);

        IOException failure = assertThrows(IOException.class,
                () -> odmClient.startAria2cWithRpc(List.of("--dir=" + downloadDir)),
                "a tokenless client must refuse an endpoint occupied by an unauthenticated daemon");
        assertTrue(failure.getMessage().toLowerCase().contains("secret"),
                "rejection message must be actionable and mention the secret: " + failure.getMessage());
        assertEquals(Aria2Client.DaemonOwnership.STOPPED, odmClient.getDaemonOwnership(),
                "a rejected daemon must not be recorded as adopted or owned");

        // The external daemon is untouched: still answering RPC
        Aria2Client externalProbe = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), rpcUrl(port), null);
        assertDoesNotThrow(externalProbe::getVersion,
                "rejecting the endpoint must not disturb the foreign daemon");
    }

    @Test
    @DisplayName("An occupied endpoint with a secret-protected daemon and matching configured secret is adopted")
    @Timeout(30)
    void occupiedEndpointWithValidConfiguredSecretIsAdopted() throws Exception {
        int port = BASE_PORT + 1;
        startExternalDaemon(port, EXTERNAL_SECRET);

        odmClient = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), rpcUrl(port), EXTERNAL_SECRET);

        assertTrue(odmClient.startAria2cWithRpc(null),
                "an authenticated external daemon must be adopted");
        assertEquals(Aria2Client.DaemonOwnership.EXTERNAL_AUTHENTICATED,
                odmClient.getDaemonOwnership(),
                "successful authenticated probe must record external ownership");

        Map<String, Object> version = assertDoesNotThrow(odmClient::getVersion);
        assertNotNull(version.get("version"), "adopted daemon must answer authenticated RPC");
    }

    @Test
    @DisplayName("Shutting down an adopted external daemon closes ODM transports without sending shutdown")
    @Timeout(30)
    void externalDaemonShutdownNeverSendsDaemonShutdown() throws Exception {
        int port = BASE_PORT + 2;
        startExternalDaemon(port, EXTERNAL_SECRET);

        odmClient = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), rpcUrl(port), EXTERNAL_SECRET);
        assertTrue(odmClient.startAria2cWithRpc(null));
        assertEquals(Aria2Client.DaemonOwnership.EXTERNAL_AUTHENTICATED, odmClient.getDaemonOwnership());

        // Open an ODM transport so closing it is meaningful, then run the
        // handler-style teardown: transports closed, daemon left running
        odmClient.setUseWebSocket(true);
        assertDoesNotThrow(odmClient::connectWebSocket);
        odmClient.disconnectWebSocket();
        assertTrue(odmClient.stopAria2c(),
                "external teardown must succeed without owning the daemon");
        assertEquals(Aria2Client.DaemonOwnership.STOPPED, odmClient.getDaemonOwnership());

        Aria2Client externalProbe = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), rpcUrl(port), EXTERNAL_SECRET);
        assertDoesNotThrow(externalProbe::getVersion,
                "the adopted external daemon must still be running: aria2.shutdown must never be sent to it");
    }

    @Test
    @DisplayName("Shutting down an ODM-started daemon sends the authenticated shutdown and reaps it")
    @Timeout(30)
    void ownedDaemonShutdownSendsAuthenticatedShutdownAndReapsChild() throws Exception {
        int port = BASE_PORT + 3;
        odmClient = new Aria2Client(ApplicationContext.getToolPath("aria2"), rpcUrl(port), null);

        assertTrue(odmClient.startAria2cWithRpc(List.of("--dir=" + downloadDir)),
                "self-launch on a free endpoint must succeed");
        assertEquals(Aria2Client.DaemonOwnership.ODM_STARTED, odmClient.getDaemonOwnership(),
                "verified authenticated readiness must record ODM ownership");
        assertNotNull(odmClient.getRpcSecret(),
                "an ODM-started daemon must be authenticated with a generated secret");

        // Handler-style teardown
        odmClient.disconnectWebSocket();
        assertTrue(odmClient.stopAria2c(), "owned daemon shutdown must succeed");
        assertEquals(Aria2Client.DaemonOwnership.STOPPED, odmClient.getDaemonOwnership());

        Aria2Client goneProbe = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), rpcUrl(port), null);
        assertThrows(Exception.class, goneProbe::getVersion,
                "the ODM-started daemon must be reaped: nothing may answer on the endpoint");
    }

    @Test
    @DisplayName("An occupied endpoint whose daemon rejects the configured secret is rejected")
    @Timeout(30)
    void occupiedEndpointWithWrongConfiguredSecretIsRejected() throws Exception {
        int port = BASE_PORT + 5;
        startExternalDaemon(port, "the-real-secret");

        odmClient = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), rpcUrl(port), "a-wrong-secret");

        IOException failure = assertThrows(IOException.class,
                () -> odmClient.startAria2cWithRpc(List.of("--dir=" + downloadDir)),
                "a daemon that rejects the configured credentials must not be adopted");
        assertTrue(failure.getMessage().toLowerCase().contains("secret"),
                "rejection message must be actionable and mention the secret: " + failure.getMessage());
        assertEquals(Aria2Client.DaemonOwnership.STOPPED, odmClient.getDaemonOwnership());

        // The external daemon is untouched: still answering with its own secret
        Aria2Client externalProbe = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), rpcUrl(port), "the-real-secret");
        assertDoesNotThrow(externalProbe::getVersion,
                "rejecting the endpoint must not disturb the foreign daemon");
    }

    @Test
    @DisplayName("A daemon accepting ODM's own generated secret (survived child) gets a distinct refusal")
    @Timeout(30)
    void survivingOdmChildGetsDistinctRefusalMessage() throws Exception {
        int port = BASE_PORT + 6;
        odmClient = new Aria2Client(ApplicationContext.getToolPath("aria2"), rpcUrl(port), null);
        // Generate ODM's child secret without launching, then occupy the
        // endpoint with a daemon that accepts it — the zombie-child case
        odmClient.buildRpcLaunchCommand(null);
        startExternalDaemon(port, odmClient.getRpcSecret());

        IOException failure = assertThrows(IOException.class,
                () -> odmClient.startAria2cWithRpc(null),
                "a tokenless client must still refuse the occupied endpoint");
        assertTrue(failure.getMessage().toLowerCase().contains("previously"),
                "a daemon authenticating ODM's own generated secret is a survived ODM child; "
                        + "the message must say so instead of blaming credentials: " + failure.getMessage());
        assertEquals(Aria2Client.DaemonOwnership.STOPPED, odmClient.getDaemonOwnership());

        Aria2Client stillAlive = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), rpcUrl(port), odmClient.getRpcSecret());
        assertDoesNotThrow(stillAlive::getVersion);
    }

    @Test
    @DisplayName("A tokenless external daemon is refused even with a configured secret")
    @Timeout(30)
    void tokenlessExternalDaemonIsRefusedDespiteConfiguredSecret() throws Exception {
        int port = BASE_PORT + 9;
        // No --rpc-secret: this daemon accepts EVERY token, including wrong ones
        startExternalDaemon(port, null);

        odmClient = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), rpcUrl(port), "configured-secret-1");

        IOException failure = assertThrows(IOException.class,
                () -> odmClient.startAria2cWithRpc(null),
                "a daemon that accepts a deliberately wrong token enforces no secret; "
                        + "adoption would be an authentication bypass");
        assertTrue(failure.getMessage().toLowerCase().contains("secret"),
                "rejection message must be actionable and mention the secret: " + failure.getMessage());
        assertEquals(Aria2Client.DaemonOwnership.STOPPED, odmClient.getDaemonOwnership(),
                "an unauthenticated daemon must never be recorded as adopted");

        // The external daemon is untouched: still answering tokenless RPC
        Aria2Client externalProbe = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), rpcUrl(port), null);
        assertDoesNotThrow(externalProbe::getVersion,
                "refusing the endpoint must not disturb the foreign daemon");
    }

    @Test
    @DisplayName("A failed owned-daemon shutdown keeps ownership while the daemon still answers RPC")
    @Timeout(90)
    void failedOwnedShutdownRetainsOwnershipUntilDaemonConfirmedDead() throws Exception {
        int port = BASE_PORT + 10;
        String secret = "owned-shutdown-secret";
        java.util.concurrent.atomic.AtomicInteger forceAttempts = new java.util.concurrent.atomic.AtomicInteger();
        odmClient = new Aria2Client(ApplicationContext.getToolPath("aria2"), rpcUrl(port), secret) {
            @Override
            public String shutdown() throws IOException, Aria2RpcException {
                throw new IOException("simulated graceful shutdown failure");
            }

            @Override
            public String forceShutdown() throws IOException, Aria2RpcException {
                if (forceAttempts.incrementAndGet() == 1) {
                    throw new IOException("simulated force shutdown failure");
                }
                return super.forceShutdown();
            }
        };

        assertTrue(odmClient.startAria2cWithRpc(List.of("--dir=" + downloadDir)),
                "self-launch must succeed");
        assertEquals(Aria2Client.DaemonOwnership.ODM_STARTED, odmClient.getDaemonOwnership());

        // First stop attempt: every shutdown RPC fails, the daemon survives
        assertFalse(odmClient.stopAria2c(),
                "a surviving daemon must be reported as not stopped");
        assertEquals(Aria2Client.DaemonOwnership.ODM_STARTED, odmClient.getDaemonOwnership(),
                "ownership must be retained while the daemon still answers RPC — "
                        + "resetting it makes the surviving daemon unmanageable");

        Aria2Client survivorProbe = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), rpcUrl(port), secret);
        assertDoesNotThrow(survivorProbe::getVersion,
                "the daemon survived the failed shutdown and must still answer RPC");

        // A later stop must be able to retry the shutdown instead of
        // treating the daemon as foreign or already gone
        assertTrue(odmClient.stopAria2c(),
                "a retry with a working escalation path must reap the daemon");
        assertEquals(Aria2Client.DaemonOwnership.STOPPED, odmClient.getDaemonOwnership());
        assertThrows(Exception.class, survivorProbe::getVersion,
                "nothing may answer on the endpoint after the successful retry");
    }

    @Test
    @DisplayName("Reserved RPC arguments in extra args are filtered and cannot override ODM controls")
    void reservedRpcArgumentsAreFilteredFromExtraArgs() throws Exception {
        Aria2Client client = new Aria2Client(
                "aria2c", "http://localhost:" + (BASE_PORT + 4) + "/jsonrpc", "odm-secret");

        List<String> cmd = client.buildRpcLaunchCommand(Arrays.asList(
                "--rpc-secret=evil-secret",
                "--rpc-listen-port=9999",
                "--rpc-listen-all=true",
                "--enable-rpc=false",
                "--dir=/tmp/odm-downloads"));

        assertTrue(cmd.contains("--rpc-secret=odm-secret"),
                "ODM's configured secret stays authoritative");
        assertFalse(cmd.contains("--rpc-secret=evil-secret"),
                "extra args must not override the RPC secret");
        assertFalse(cmd.contains("--rpc-listen-port=9999"),
                "extra args must not override ODM's RPC port selection");
        assertTrue(cmd.contains("--rpc-listen-port=" + (BASE_PORT + 4)),
                "the port is derived from ODM's own RPC endpoint");
        assertTrue(cmd.contains("--rpc-listen-all=false"),
                "extra args must not loosen the localhost-only binding");
        assertFalse(cmd.contains("--enable-rpc=false"),
                "extra args must not disable the RPC server ODM requires");
        assertTrue(cmd.contains("--enable-rpc"),
                "ODM's own enable-rpc control remains present");
        assertTrue(cmd.contains("--dir=/tmp/odm-downloads"),
                "non-reserved generic arguments are preserved");

        // Two-argument flag form is filtered too
        List<String> cmd2 = client.buildRpcLaunchCommand(Arrays.asList(
                "--rpc-secret", "evil-secret-2",
                "--enable-rpc", "false",
                "--max-connection-per-server", "4"));
        assertFalse(cmd2.contains("--rpc-secret=evil-secret-2"));
        assertFalse(cmd2.stream().anyMatch("--rpc-secret"::equals),
                "the two-argument form of a reserved flag must be dropped");
        assertFalse(cmd2.stream().anyMatch("evil-secret-2"::equals),
                "the value of a dropped two-argument reserved flag must not leak through");
        assertFalse(cmd2.stream().anyMatch("false"::equals),
                "the value of a dropped two-argument enable-rpc must not leak as a stray positional");
        assertTrue(cmd2.contains("--max-connection-per-server"));
        assertTrue(cmd2.contains("4"));

        // Consecutive reserved flags: a boolean-style flag must not swallow
        // the next reserved flag as its "value" and leak that flag's real
        // value as a stray positional
        List<String> cmd3 = client.buildRpcLaunchCommand(Arrays.asList(
                "--enable-rpc", "--rpc-secret", "leaked-value"));
        assertFalse(cmd3.stream().anyMatch("--rpc-secret"::equals),
                "the reserved --rpc-secret flag itself must be dropped");
        assertFalse(cmd3.stream().anyMatch("leaked-value"::equals),
                "the secret value must not leak as a stray positional after consecutive reserved flags");

        List<String> cmd4 = client.buildRpcLaunchCommand(Arrays.asList(
                "--no-conf=false", "--conf-path", "/tmp/foreign.conf"));
        assertTrue(cmd4.contains("--no-conf"),
                "ODM's default configuration isolation must remain authoritative");
        assertFalse(cmd4.contains("--no-conf=false"));
        assertFalse(cmd4.contains("--conf-path"));
        assertFalse(cmd4.contains("/tmp/foreign.conf"));
    }
}
