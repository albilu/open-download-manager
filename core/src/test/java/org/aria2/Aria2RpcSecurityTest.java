package org.aria2;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Security contract for the self-managed aria2 RPC daemon:
 *
 * 1. The RPC endpoint must bind to localhost only (--rpc-listen-all=false),
 *    never to all interfaces.
 * 2. A self-launched daemon must require an RPC secret, and every RPC
 *    payload this client builds must carry the matching token — even when
 *    the caller did not configure one (a random secret is generated).
 */
@DisplayName("Aria2 self-launched RPC daemon must be localhost-only and token-protected")
class Aria2RpcSecurityTest {

    @Test
    @DisplayName("Self-launched daemon binds localhost and gets a generated secret")
    void launchCommandBindsLocalhostWithGeneratedSecret() throws Exception {
        Aria2Client client = new Aria2Client("aria2c");

        List<String> cmd = client.buildRpcLaunchCommand(null);

        assertFalse(cmd.contains("--rpc-listen-all=true"),
                "RPC daemon must never listen on all interfaces");
        assertTrue(cmd.contains("--rpc-listen-all=false"),
                "RPC daemon must explicitly bind localhost only");
        assertTrue(cmd.stream().anyMatch(a -> a.startsWith("--rpc-secret=")),
                "self-launched daemon must require an RPC secret");

        String payload = client.buildPayload("aria2.getVersion");
        assertTrue(payload.contains("token:"),
                "RPC payloads must carry the generated secret after launch-command build");
    }

    @Test
    @DisplayName("Explicitly configured token is used for both daemon flag and payloads")
    void explicitTokenUsedForDaemonAndPayloads() throws Exception {
        Aria2Client client = new Aria2Client("aria2c", "http://localhost:6800/jsonrpc", "test-secret-123");

        List<String> cmd = client.buildRpcLaunchCommand(null);

        assertTrue(cmd.contains("--rpc-secret=test-secret-123"),
                "configured token must become the daemon's rpc-secret");

        String payload = client.buildPayload("aria2.getVersion");
        assertTrue(payload.contains("token:test-secret-123"),
                "configured token must be sent with every RPC payload");
    }

    @Test
    @DisplayName("Self-launched daemon ignores external configuration by default")
    void externalConfigurationIsOptIn() {
        Aria2Client isolated = new Aria2Client("aria2c",
                "http://localhost:6801/jsonrpc", null);
        List<String> isolatedCommand = isolated.buildRpcLaunchCommand(null);

        assertTrue(isolatedCommand.contains("--no-conf"));
        assertTrue(isolatedCommand.contains("--rpc-listen-port=6801"));

        isolated.setHonorExternalConfiguration(true);
        List<String> honoringCommand = isolated.buildRpcLaunchCommand(null);
        assertFalse(honoringCommand.contains("--no-conf"));
    }

    @Test
    @DisplayName("An explicit configuration path replaces default isolation")
    void explicitConfigurationPathIsHonored() {
        Aria2Client client = new Aria2Client("aria2c");
        client.setConfigFile("/tmp/odm-explicit-aria2.conf");

        List<String> command = client.buildRpcLaunchCommand(null);

        assertTrue(command.contains("--conf-path=/tmp/odm-explicit-aria2.conf"));
        assertFalse(command.contains("--no-conf"));
    }
}
