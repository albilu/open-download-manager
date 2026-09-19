package org.manager.tools;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineLocaleTest {
    @Test
    void parsedToolsUseStableUtf8OutputWithoutChangingTheApplicationLocale() throws Exception {
        String applicationLanguage = System.getenv("LANGUAGE");
        ProcessBuilder builder = new ProcessBuilder("locale");
        builder.environment().put("LC_ALL", "fr_FR.UTF-8");
        builder.environment().put("LC_NUMERIC", "fr_FR.UTF-8");
        builder.environment().put("LANGUAGE", "fr");
        Process child = NetworkProcessPolicy.prepare(builder).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(output.contains("LC_ALL=C.UTF-8"), output);
            assertTrue(output.contains("LANGUAGE=C"), output);
            assertTrue(output.contains("LC_NUMERIC=\"C.UTF-8\""), output);
            assertEquals(applicationLanguage, System.getenv("LANGUAGE"));
        } finally {
            child.destroyForcibly();
        }
    }
}
