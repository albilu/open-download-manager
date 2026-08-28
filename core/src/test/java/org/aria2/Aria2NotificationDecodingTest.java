package org.aria2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicReference;
import org.aria2.Aria2Client.Aria2RpcError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * aria2 WebSocket notifications use the wire shape
 * {@code params:[{"gid":"..."}]} — the gid is a FIELD of the first object
 * param, not the param itself. Error notifications carry the human-readable
 * error text in a second STRING param. The decoder must honor both shapes.
 */
@DisplayName("Aria2 notification decoding honors the real payload shapes")
class Aria2NotificationDecodingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class RecordingListener implements Aria2NotificationListener {

        final AtomicReference<String> gid = new AtomicReference<>();
        final AtomicReference<String> errorMessage = new AtomicReference<>();

        @Override
        public void onDownloadStart(String gid) {
            this.gid.set(gid);
        }

        @Override
        public void onDownloadPause(String gid) {
            this.gid.set(gid);
        }

        @Override
        public void onDownloadStop(String gid) {
            this.gid.set(gid);
        }

        @Override
        public void onDownloadComplete(String gid) {
            this.gid.set(gid);
        }

        @Override
        public void onDownloadError(String gid, Aria2RpcError error) {
            this.gid.set(gid);
            this.errorMessage.set(error != null ? error.message : null);
        }

        @Override
        public void onBtDownloadComplete(String gid) {
            this.gid.set(gid);
        }
    }

    @Test
    @DisplayName("The gid is read from the object param's gid field")
    void gidIsReadFromObjectParamField() throws Exception {
        Aria2Client client = new Aria2Client("aria2c", "http://localhost:6800/jsonrpc", null);
        RecordingListener listener = new RecordingListener();
        client.addNotificationListener(listener);

        JsonNode payload = MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"aria2.onDownloadStart\","
                + "\"params\":[{\"gid\":\"2089b05ecca3d829\"}]}");
        client.handleNotification(payload);

        assertEquals("2089b05ecca3d829", listener.gid.get(),
                "the gid must come from params[0].gid, not params[0] rendered as text");
    }

    @Test
    @DisplayName("Every notification method decodes the object-param gid")
    void everyNotificationMethodDecodesGid() throws Exception {
        Aria2Client client = new Aria2Client("aria2c", "http://localhost:6800/jsonrpc", null);
        RecordingListener listener = new RecordingListener();
        client.addNotificationListener(listener);

        for (String method : new String[]{
                "aria2.onDownloadPause", "aria2.onDownloadStop", "aria2.onDownloadComplete",
                "aria2.onBtDownloadComplete"}) {
            JsonNode payload = MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"" + method
                    + "\",\"params\":[{\"gid\":\"gid-" + method + "\"}]}");
            client.handleNotification(payload);
            assertEquals("gid-" + method, listener.gid.get(), method + " must decode the gid field");
        }
    }

    @Test
    @DisplayName("The error notification's second string param becomes the error message")
    void errorMessageComesFromSecondStringParam() throws Exception {
        Aria2Client client = new Aria2Client("aria2c", "http://localhost:6800/jsonrpc", null);
        RecordingListener listener = new RecordingListener();
        client.addNotificationListener(listener);

        JsonNode payload = MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"aria2.onDownloadError\","
                + "\"params\":[{\"gid\":\"deadbeef00\"},\"Exception: [downloadError] error\"]}");
        client.handleNotification(payload);

        assertEquals("deadbeef00", listener.gid.get());
        assertNotNull(listener.errorMessage.get(), "an error notification must deliver an error object");
        assertEquals("Exception: [downloadError] error", listener.errorMessage.get(),
                "the error text arrives as the second param STRING, not an error code");
    }

    @Test
    @DisplayName("The error notification's object param yields its errorMessage field")
    void errorMessageComesFromObjectParamErrorMessageField() throws Exception {
        Aria2Client client = new Aria2Client("aria2c", "http://localhost:6800/jsonrpc", null);
        RecordingListener listener = new RecordingListener();
        client.addNotificationListener(listener);

        // Real aria2 daemons send params[1] as an object with errorCode
        // and errorMessage fields
        JsonNode payload = MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"aria2.onDownloadError\","
                + "\"params\":[{\"gid\":\"cafe123456\"},"
                + "{\"errorCode\":\"1\",\"errorMessage\":\"Exception: [downloadError] error\"}]}");
        client.handleNotification(payload);

        assertEquals("cafe123456", listener.gid.get());
        assertNotNull(listener.errorMessage.get(), "an error notification must deliver an error object");
        assertEquals("Exception: [downloadError] error", listener.errorMessage.get(),
                "the error text must come from params[1].errorMessage when the param is an object");
    }
}
