package org.odm.gtk4;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class I18nTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @CsvSource({
        "fr_FR.UTF-8, , fr",
        "fr_BE.UTF-8, , fr",
        "fr_CA.UTF-8, , fr",
        "en_US.UTF-8, , en",
        "de_DE.UTF-8, , en",
        "en_US.UTF-8, de:fr_CA:en, fr",
        "en_US.UTF-8, en:fr, en",
        "fr_FR.UTF-8, en, en",
        "C, fr, en",
        "C.UTF-8, fr, en"
    })
    void nativeGtkAndJavaUseTheSystemLanguage(String locale, String language, String expected) throws Exception {
        Path output = temporary.resolve("locale.log");
        // gettext and GTK cache the process locale. Test real startup environments,
        // rather than mutating Locale.setDefault() after the native toolkit has started.
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                I18nLocaleProbe.class.getName(), expected);
        builder.environment().keySet().removeIf(key -> key.startsWith("LC_") || key.equals("LANGUAGE"));
        builder.environment().put("LANG", locale);
        builder.environment().put("LC_ALL", locale);
        if (language != null) { builder.environment().put("LANGUAGE", language); }
        Process child = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), () -> read(output));
            assertEquals(0, child.exitValue(), () -> read(output));
            assertTrue(read(output).contains("LOCALE CHECK PASSED"), () -> read(output));
        } finally {
            child.destroyForcibly();
        }
    }

    private static String read(Path output) {
        try { return Files.readString(output); }
        catch (Exception error) { return error.toString(); }
    }
}
