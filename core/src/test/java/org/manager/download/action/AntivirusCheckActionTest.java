package org.manager.download.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.manager.download.action.AfterCompletionAction.ActionType;
import org.manager.download.action.AfterCompletionAction.Severity;

@DisplayName("AntivirusCheckAction scan execution semantics")
class AntivirusCheckActionTest {

    @TempDir
    Path tempDir;

    private Download completedDownload(Path file) throws IOException {
        Download download = new Download(URI.create("https://example.test/sample.txt"));
        download.setName("sample.txt");
        download.setDestination(tempDir);
        download.setStatus(Download.Status.COMPLETED);
        download.setOutputPaths(List.of(file));
        return download;
    }

    @Test
    @DisplayName("custom command with clean exit reports success without threats")
    void cleanCustomScanSucceeds() throws Exception {
        Path file = Files.writeString(tempDir.resolve("sample.txt"), "hello");
        AntivirusCheckAction action = new AntivirusCheckAction("true", 30);

        assertTrue(action.execute(completedDownload(file)));
        assertFalse(action.isThreatDetected());
        assertFalse(action.isScanning());
        assertEquals("", action.getScanResult());
    }

    @Test
    @DisplayName("scanner output is captured verbatim")
    void scanOutputIsCaptured() throws Exception {
        Path file = Files.writeString(tempDir.resolve("sample.txt"), "hello");
        AntivirusCheckAction action = new AntivirusCheckAction("echo scanner-line-42", 30);

        assertTrue(action.execute(completedDownload(file)));
        assertEquals("scanner-line-42", action.getScanResult().trim());
    }

    private Path scannerScript(String body) throws IOException {
        Path script = tempDir.resolve("scan-" + System.nanoTime() + ".sh");
        Files.writeString(script, body);
        Files.setPosixFilePermissions(script,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    @Test
    @DisplayName("custom command output does not invent a scanner verdict")
    void arbitraryCustomOutputIsNotAThreatVerdict() throws Exception {
        Path file = Files.writeString(tempDir.resolve("sample.txt"), "hello");
        Path script = scannerScript("#!/bin/sh\necho \"sample.txt: EICAR-TROJAN Found\"\nexit 0\n");
        AntivirusCheckAction action = new AntivirusCheckAction(script + " {file}", 30);

        assertTrue(action.execute(completedDownload(file)), "exit code is 0, so the scan itself succeeded");
        assertFalse(action.isThreatDetected());
        assertTrue(action.getResultMessage().contains("inspect its output"));
        assertTrue(action.getScanResult().contains("EICAR"));
    }

    @Test
    @DisplayName("non-zero exit code fails the scan for custom commands")
    void nonZeroExitFailsCustomScan() throws Exception {
        Path file = Files.writeString(tempDir.resolve("sample.txt"), "hello");
        Path script = scannerScript("#!/bin/sh\necho \"malware suspicious\"\nexit 2\n");
        AntivirusCheckAction action = new AntivirusCheckAction(script + " {file}", 30);

        assertFalse(action.execute(completedDownload(file)));
        assertFalse(action.isThreatDetected(), "scanner failure is not a threat verdict");
    }

    @Test
    @DisplayName("the {file} placeholder is replaced with the download output path")
    void placeholderIsSubstituted() throws Exception {
        Path file = Files.writeString(tempDir.resolve("named.txt"), "content");
        // 'cat' fails with a missing operand only when substitution did not happen;
        // 'wc -l <file>' prints to stdout and exits 0 only when the file exists
        AntivirusCheckAction action = new AntivirusCheckAction("wc -c {file}", 30);

        assertTrue(action.execute(completedDownload(file)));
        assertTrue(action.getScanResult().trim().endsWith("named.txt"),
                "wc must have received the substituted file path, got: " + action.getScanResult());
    }

    @Test
    @DisplayName("a scan timeout forcibly stops the scanner and reports failure")
    @Timeout(30)
    void scanTimeoutStopsScanner() throws Exception {
        Path file = Files.writeString(tempDir.resolve("sample.txt"), "hello");
        AntivirusCheckAction action = new AntivirusCheckAction("sleep 30", 1);

        assertFalse(action.execute(completedDownload(file)));
        assertFalse(action.isScanning());
        assertNull(action.getScanResult(), "no result may be published for a timed-out scan");
    }

    @Test
    @DisplayName("cancel terminates a running scan")
    @Timeout(30)
    void cancelTerminatesRunningScan() throws Exception {
        Path file = Files.writeString(tempDir.resolve("sample.txt"), "hello");
        AntivirusCheckAction action = new AntivirusCheckAction("sleep 30", 0);
        Download download = completedDownload(file);

        Thread worker = new Thread(() -> action.execute(download));
        worker.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!action.isScanning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(action.isScanning(), "scan should be running by now");
        assertTrue(action.cancel());
        worker.join(10_000);
        assertFalse(worker.isAlive(), "execute must return after the scan process was destroyed");
    }

    @Test
    @DisplayName("cancel without a scan is a harmless no-op")
    void cancelWithoutScanIsNoOp() {
        AntivirusCheckAction action = new AntivirusCheckAction("true", 5);
        assertTrue(action.cancel());
        assertFalse(action.isScanning());
    }

    @Test
    @DisplayName("missing destination, output path, or file each reject the scan")
    void missingInputsAreRejected() throws Exception {
        AntivirusCheckAction action = new AntivirusCheckAction("true", 5);

        Download noDestination = new Download(URI.create("https://example.test/a.txt"));
        noDestination.setDestination(null);
        assertFalse(action.execute(noDestination));

        Download noOutput = new Download(URI.create("https://example.test/b.txt"));
        noOutput.setDestination(tempDir);
        noOutput.setOutputPaths(List.of());
        assertFalse(action.execute(noOutput));

        Download ghostFile = completedDownload(tempDir.resolve("ghost.bin"));
        assertFalse(action.execute(ghostFile));
        assertFalse(action.isScanning());
    }

    @Test
    @DisplayName("an executable-missing command fails without throwing")
    void missingScannerBinaryFails() throws Exception {
        Path file = Files.writeString(tempDir.resolve("sample.txt"), "hello");
        AntivirusCheckAction action = new AntivirusCheckAction("no-such-scanner-binary-xyz", 5);

        assertFalse(action.execute(completedDownload(file)));
        assertFalse(action.isScanning());
    }

    @Test
    @DisplayName("accessors expose the configured scan setup")
    void accessorsExposeConfiguration() {
        AntivirusCheckAction custom = new AntivirusCheckAction("clamscan-custom --fast {file}", 25);
        assertEquals(AntivirusCheckAction.AntivirusType.CUSTOM, custom.getAntivirusType());
        assertEquals("clamscan-custom --fast {file}", custom.getCustomCommand());
        assertEquals(25, custom.getTimeoutSeconds());
        assertTrue(custom.getDescription().contains("custom command"));
        assertTrue(custom.getDescription().contains("25s"));
        assertEquals(ActionType.ANTIVIRUS_CHECK, custom.getType());
        assertEquals(Severity.HIGH, custom.getSeverity());

        AntivirusCheckAction clamav = new AntivirusCheckAction(AntivirusCheckAction.AntivirusType.CLAMAV, 0);
        assertEquals("clamscan", clamav.getExecutablePath());
        assertTrue(clamav.getDescription().contains("ClamAV"));
        assertFalse(clamav.getDescription().contains("timeout"), "timeout 0 means none, so it is not advertised");
    }

    @Test
    @DisplayName("built-in scanners execute through their discovered path")
    void builtInScannerUsesDiscoveredPath() throws Exception {
        Path file = Files.writeString(tempDir.resolve("sample.txt"), "hello");
        Path scanner = scannerScript("#!/bin/sh\necho clean\nexit 0\n");
        AntivirusCheckAction action = new AntivirusCheckAction(
                AntivirusCheckAction.AntivirusType.CLAMAV, scanner.toString(), 30);

        assertEquals(scanner.toString(), action.getExecutablePath());
        assertTrue(action.execute(completedDownload(file)));
        assertEquals("clean", action.getScanResult().trim());
    }

    @Test
    @DisplayName("negative timeouts are clamped to zero")
    void negativeTimeoutClamped() {
        AntivirusCheckAction action = new AntivirusCheckAction("true", -10);
        assertEquals(0, action.getTimeoutSeconds());
    }
    @Test
    void clamavExitCodesDistinguishCleanThreatAndFailureRegardlessOfFilenames() throws Exception {
        Path file = Files.writeString(tempDir.resolve("antivirus-found-rootkit.txt"), "clean");
        for (int code : new int[]{0, 1, 2}) {
            Path scanner = scannerScript("#!/bin/sh\necho 'antivirus-found-rootkit.txt: OK'\nexit " + code + "\n");
            AntivirusCheckAction action = new AntivirusCheckAction(
                    AntivirusCheckAction.AntivirusType.CLAMAV, scanner.toString(), 5);
            assertEquals(code != 2, action.execute(completedDownload(file)));
            assertEquals(code == 1, action.isThreatDetected());
            assertEquals(code == 0 ? "No threats detected" : code == 1 ? "Threats detected"
                    : "Antivirus scanner exited with code 2", action.getResultMessage());
        }
    }

    @Test
    void customArgumentsPreserveSpacesQuotesAndLiteralShellText() throws Exception {
        Path file = Files.writeString(tempDir.resolve("file with 'quotes' $dollar; text.txt"), "safe");
        Path scanner = tempDir.resolve("scanner with spaces.sh");
        Files.writeString(scanner, "#!/bin/sh\n[ \"$#\" -eq 2 ] && [ \"$1\" = 'fixed argument' ] && [ -f \"$2\" ]\n");
        scanner.toFile().setExecutable(true);
        for (String placeholder : List.of("{file}", "\"{file}\"", "'{file}'")) {
            AntivirusCheckAction action = new AntivirusCheckAction(
                    "\"" + scanner + "\" 'fixed argument' " + placeholder, 5);
            assertTrue(action.execute(completedDownload(file)), action.getFailureMessage());
        }
        AntivirusCheckAction malformed = new AntivirusCheckAction("'unterminated", 5);
        assertFalse(malformed.execute(completedDownload(file)));
    }

}
