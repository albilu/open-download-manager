package org.aria2;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * system.multicall wire-format contract: aria2 expects params[0] to be the
 * array of method calls, and RPC authentication to appear as a "token:..."
 * first element INSIDE each inner call's params. A token on the outer params
 * corrupts the request (params[0] becomes a string), and a missing inner
 * token gets every call rejected once a secret is configured.
 */
@DisplayName("system.multicall payload carries the token inside each call")
class Aria2MulticallPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Multicall payload: outer params[0] is the call array, token inside each call")
    void multicallPayloadStructure() throws Exception {
        Aria2Client client = new Aria2Client("aria2c", "http://localhost:6800/jsonrpc", "secret-1");

        List<Map<String, Object>> calls = client.tellStatusMulticallCalls(
                List.of("gid-aaa", "gid-bbb"),
                new String[]{"gid", "status"});

        String payload = client.buildMulticallPayload(calls);
        JsonNode root = MAPPER.readTree(payload);

        assertEquals("system.multicall", root.get("method").asText());

        // aria2 expects params[0] to be the ARRAY of calls; the token must
        // live inside each inner call, never at params[0]
        JsonNode params = root.get("params");
        assertTrue(params.isArray(), "params must be an array");
        assertTrue(params.get(0).isArray(),
                "params[0] must be the calls array (not a token string, not a bare call)");
        assertEquals(2, params.get(0).size(), "one call per gid");

        for (JsonNode call : params.get(0)) {
            assertEquals("aria2.tellStatus", call.get("methodName").asText());
            JsonNode inner = call.get("params");
            assertEquals("token:secret-1", inner.get(0).asText(),
                    "each inner call must authenticate with the token");
            assertTrue(inner.get(1).isTextual(), "gid follows the token");
            assertTrue(inner.get(2).isArray(), "status keys follow the gid");
        }
        assertEquals("gid-aaa", params.get(0).get(0).get("params").get(1).asText());
        assertEquals("gid-bbb", params.get(0).get(1).get("params").get(1).asText());
    }

    @Test
    @DisplayName("Tokenless clients still produce a clean calls array")
    void tokenlessMulticallPayload() throws Exception {
        Aria2Client client = new Aria2Client("aria2c", "http://localhost:6800/jsonrpc", null);

        List<Map<String, Object>> calls = client.tellStatusMulticallCalls(
                List.of("gid-x"), new String[]{"status"});
        String payload = client.buildMulticallPayload(calls);
        JsonNode params = MAPPER.readTree(payload).get("params");

        assertTrue(params.get(0).isArray(), "params[0] is the calls array");
        assertEquals("gid-x", params.get(0).get(0).get("params").get(0).asText(),
                "no token element when unauthenticated");
    }
}
