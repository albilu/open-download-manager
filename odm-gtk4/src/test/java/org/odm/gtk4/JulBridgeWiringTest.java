package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.logging.Handler;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;

class JulBridgeWiringTest {

    @Test
    void routesJulThroughSlf4jWithNoConsoleDuplicates() {
        assertDoesNotThrow(OdmApplication::wireJulBridge);
        Handler[] rootHandlers = Logger.getLogger("").getHandlers();
        assertEquals(1, rootHandlers.length, "root JUL logger must have exactly the bridge handler");
        assertInstanceOf(SLF4JBridgeHandler.class, rootHandlers[0]);
    }
}
