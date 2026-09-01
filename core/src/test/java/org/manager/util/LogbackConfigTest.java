package org.manager.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boots the production logback.xml against a temp directory and verifies the
 * file appender target, content, and rotation. ODM_LOG_DIR is a system
 * property that overrides the XDG path resolution inside logback.xml
 * (system properties outrank environment variables in logback variable
 * substitution).
 */
@Execution(ExecutionMode.SAME_THREAD)
class LogbackConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void writesIntoOdmLogsDirectoryWithConfiguredPattern() throws Exception {
        String originalOdmLogDir = setLogProperties(tempDir);
        try {
            configure("/logback.xml");

            Logger logger = LoggerFactory.getLogger(LogbackConfigTest.class);
            String marker = "disk-logging-" + System.nanoTime();
            logger.info(marker);

            Path logFile = tempDir.resolve("odm").resolve("logs").resolve("odm.log");
            assertTrue(Files.exists(logFile), "odm.log not created at " + logFile);
            String content = Files.readString(logFile);
            assertTrue(content.contains(marker), "marker missing from log file");
            assertTrue(content.contains("[INFO"), "level token missing from log file");
        } finally {
            restoreConsoleOnly();
            restoreLogProperties(originalOdmLogDir);
        }
    }

    @Test
    void rotatesFilesOnceFileSizeCapIsExceeded() throws Exception {
        String originalOdmLogDir = setLogProperties(tempDir);
        try {
            configure("/logback.xml");

            Logger noisy = LoggerFactory.getLogger("rotation-test");
            String payload = "x".repeat(1024);
            for (int i = 0; i < 2600; i++) {
                noisy.info("{} {}", i, payload);
            }

            Path logDir = tempDir.resolve("odm").resolve("logs");
            assertTrue(Files.exists(logDir.resolve("odm.log")), "active odm.log missing");
            try (var entries = Files.list(logDir)) {
                List<Path> rolled = entries
                        .filter(p -> p.getFileName().toString().matches("odm\\.\\d{4}-\\d{2}-\\d{2}\\.\\d+\\.log(\\.gz)?"))
                        .collect(Collectors.toList());
                assertFalse(rolled.isEmpty(), "no rolled files produced above 2MB");
            }
        } finally {
            restoreConsoleOnly();
            restoreLogProperties(originalOdmLogDir);
        }
    }

    private String setLogProperties(Path logBase) {
        String originalOdmLogDir = System.getProperty("ODM_LOG_DIR");
        System.setProperty("ODM_LOG_DIR", logBase.toString());
        System.setProperty("odm.logging.level", "INFO");
        return originalOdmLogDir;
    }

    private void restoreLogProperties(String originalOdmLogDir) {
        if (originalOdmLogDir == null) {
            System.clearProperty("ODM_LOG_DIR");
        } else {
            System.setProperty("ODM_LOG_DIR", originalOdmLogDir);
        }
        System.clearProperty("odm.logging.level");
    }

    private void configure(String resource) throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try (InputStream stream = LogbackConfigTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource + " missing from classpath");
            configurator.doConfigure(stream);
        }
        List<Status> errors = context.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getLevel() == Status.ERROR)
                .collect(Collectors.toList());
        assertTrue(errors.isEmpty(),
                "logback configuration produced errors: " + errors);
    }

    private void restoreConsoleOnly() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try (InputStream stream = LogbackConfigTest.class.getResourceAsStream("/logback-test.xml")) {
            if (stream != null) {
                configurator.doConfigure(stream);
            }
        }
    }
}
