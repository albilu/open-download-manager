package org.manager.download.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;

class ChecksumValidationActionTest {

    @TempDir
    Path tempDir;

    private Download testDownload;
    private Path testFile;
    private String testFileContent = "Hello, World! This is a test file for checksum validation.";

    @BeforeEach
    void setUp() throws IOException {
        // Create test file
        testFile = tempDir.resolve("test-file.txt");
        Files.write(testFile, testFileContent.getBytes());

        // Create mock download
        testDownload = createTestDownload("test-download", testFile.getParent(), testFile.getFileName().toString());
    }

    @Test
    void shouldValidateCorrectMD5Checksum() throws Exception {
        // Calculate expected MD5 checksum
        String expectedChecksum = calculateChecksum(testFileContent.getBytes(), "MD5");

        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, expectedChecksum);

        boolean result = action.execute(testDownload);

        assertTrue(result, "Validation should succeed with correct checksum");
        assertEquals(expectedChecksum.toLowerCase(), action.getActualChecksum().toLowerCase());
        assertEquals(testFile, action.getValidatedFile());
        assertFalse(action.isCancelled());
    }

    @Test
    void shouldValidateCorrectSHA256Checksum() throws Exception {
        String expectedChecksum = calculateChecksum(testFileContent.getBytes(), "SHA-256");

        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.SHA256, expectedChecksum);

        boolean result = action.execute(testDownload);

        assertTrue(result, "Validation should succeed with correct SHA256 checksum");
        assertEquals(expectedChecksum.toLowerCase(), action.getActualChecksum().toLowerCase());
    }

    @Test
    void shouldFailWithIncorrectChecksum() {
        String incorrectChecksum = "0123456789abcdef0123456789abcdef";

        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, incorrectChecksum);

        boolean result = action.execute(testDownload);

        assertFalse(result, "Validation should fail with incorrect checksum");
        assertNotEquals(incorrectChecksum, action.getActualChecksum());
    }

    @Test
    void shouldHandleCaseInsensitiveComparison() throws Exception {
        String expectedChecksum = calculateChecksum(testFileContent.getBytes(), "MD5");
        String upperCaseChecksum = expectedChecksum.toUpperCase();

        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, upperCaseChecksum, false);

        boolean result = action.execute(testDownload);

        assertTrue(result, "Case-insensitive validation should succeed");
        assertFalse(action.isCaseSensitive());
    }

    @Test
    void shouldHandleCaseSensitiveComparison() throws Exception {
        String expectedChecksum = calculateChecksum(testFileContent.getBytes(), "MD5");
        String upperCaseChecksum = expectedChecksum.toUpperCase();

        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, upperCaseChecksum, true);

        boolean result = action.execute(testDownload);

        // Should fail because actual checksum is lowercase but expected is uppercase
        assertFalse(result, "Case-sensitive validation should fail with different case");
        assertTrue(action.isCaseSensitive());
    }

    @Test
    void shouldFailWhenFileDoesNotExist() {
        Download downloadWithMissingFile = createTestDownload("missing-download",
                tempDir, "non-existent-file.txt");

        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, "dummy-checksum");

        boolean result = action.execute(downloadWithMissingFile);

        assertFalse(result, "Validation should fail when file doesn't exist");
        assertNull(action.getActualChecksum());
    }

    @Test
    void shouldFailWhenDownloadDestinationIsNull() {
        Download downloadWithoutDestination = createTestDownload("no-dest", null, "test.txt");

        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, "dummy-checksum");

        boolean result = action.execute(downloadWithoutDestination);

        assertFalse(result, "Validation should fail when download destination is null");
        assertNull(action.getActualChecksum());
    }

    @Test
    void shouldRejectMultiFileDownloads() throws IOException {
        Path secondFile = tempDir.resolve("second-file.txt");
        Files.writeString(secondFile, "second payload");
        testDownload.recordOutputPath(testFile);
        testDownload.recordOutputPath(secondFile);
        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, "dummy-checksum");

        assertFalse(action.execute(testDownload),
                "one whole-file checksum must not validate only part of a multi-file download");
        assertNull(action.getValidatedFile());
        assertNull(action.getActualChecksum());
    }

    @Test
    void shouldRejectDirectoryOutputs() {
        Download directoryDownload = createTestDownload("directory", tempDir, "ignored");
        directoryDownload.recordOutputPath(tempDir);
        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, "dummy-checksum");

        assertFalse(action.execute(directoryDownload));
        assertEquals(tempDir, action.getValidatedFile());
        assertNull(action.getActualChecksum());
    }

    @Test
    void shouldSupportCancellation() throws Exception {
        // Create a larger file for testing cancellation
        Path largeFile = tempDir.resolve("large-file.txt");
        byte[] largeContent = new byte[1024 * 1024]; // 1MB
        Files.write(largeFile, largeContent);

        Download largeDownload = createTestDownload("large-download",
                largeFile.getParent(), largeFile.getFileName().toString());

        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.SHA256, "dummy-checksum");

        // Start validation in background and cancel immediately
        Thread validationThread = new Thread(() -> action.execute(largeDownload));
        validationThread.start();

        // Cancel the operation
        action.cancel();

        validationThread.join(1000); // Wait up to 1 second

        assertTrue(action.isCancelled(), "Action should be marked as cancelled");
    }

    @Test
    void shouldCreateFromStringWithMD5() {
        String checksumString = "md5:5d41402abc4b2a76b9719d911017c592";

        ChecksumValidationAction action = ChecksumValidationAction.fromString(checksumString);

        assertEquals(ChecksumValidationAction.ChecksumAlgorithm.MD5, action.getAlgorithm());
        assertEquals("5d41402abc4b2a76b9719d911017c592", action.getExpectedChecksum());
        assertFalse(action.isCaseSensitive());
    }

    @Test
    void shouldCreateFromStringWithSHA256() {
        String checksumString = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        ChecksumValidationAction action = ChecksumValidationAction.fromString(checksumString);

        assertEquals(ChecksumValidationAction.ChecksumAlgorithm.SHA256, action.getAlgorithm());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", action.getExpectedChecksum());
    }

    @Test
    void shouldCreateFromStringWithSHA1Variation() {
        String checksumString = "sha-1:aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";

        ChecksumValidationAction action = ChecksumValidationAction.fromString(checksumString);

        assertEquals(ChecksumValidationAction.ChecksumAlgorithm.SHA1, action.getAlgorithm());
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", action.getExpectedChecksum());
    }

    @Test
    void shouldThrowExceptionForInvalidStringFormat() {
        assertThrows(IllegalArgumentException.class, () -> {
            ChecksumValidationAction.fromString("invalid-format");
        }, "Should throw exception for string without colon separator");

        assertThrows(IllegalArgumentException.class, () -> {
            ChecksumValidationAction.fromString(":");
        }, "Should throw exception for empty parts");

        assertThrows(IllegalArgumentException.class, () -> {
            ChecksumValidationAction.fromString(null);
        }, "Should throw exception for null input");
    }

    @Test
    void shouldThrowExceptionForUnsupportedAlgorithm() {
        assertThrows(IllegalArgumentException.class, () -> {
            ChecksumValidationAction.fromString("unsupported:abc123");
        }, "Should throw exception for unsupported algorithm");
    }

    @Test
    void shouldThrowExceptionForNullAlgorithm() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ChecksumValidationAction(null, "abc123");
        }, "Should throw exception for null algorithm");
    }

    @Test
    void shouldThrowExceptionForNullOrEmptyChecksum() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ChecksumValidationAction(ChecksumValidationAction.ChecksumAlgorithm.MD5, null);
        }, "Should throw exception for null checksum");

        assertThrows(IllegalArgumentException.class, () -> {
            new ChecksumValidationAction(ChecksumValidationAction.ChecksumAlgorithm.MD5, "");
        }, "Should throw exception for empty checksum");

        assertThrows(IllegalArgumentException.class, () -> {
            new ChecksumValidationAction(ChecksumValidationAction.ChecksumAlgorithm.MD5, "   ");
        }, "Should throw exception for whitespace-only checksum");
    }

    @Test
    void shouldHaveCorrectActionType() {
        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, "abc123");

        assertEquals(AfterCompletionAction.ActionType.CHECKSUM_VALIDATION, action.getType());
    }

    @Test
    void shouldHaveHighSeverity() {
        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, "abc123");

        assertEquals(AfterCompletionAction.Severity.HIGH, action.getSeverity());
    }

    @Test
    void shouldHaveDescriptiveDescription() {
        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.SHA256, "a1b2c3d4e5f6789012345678901234567890", true);

        String description = action.getDescription();

        assertTrue(description.contains("SHA256"), "Description should contain algorithm name");
        assertTrue(description.contains("a1b2c3d4e5f6789..."), "Description should contain truncated checksum");
        assertTrue(description.contains("case-sensitive"), "Description should mention case sensitivity");
    }

    @Test
    void shouldTestAllSupportedAlgorithms() throws Exception {
        ChecksumValidationAction.ChecksumAlgorithm[] algorithms = {
                ChecksumValidationAction.ChecksumAlgorithm.MD5,
                ChecksumValidationAction.ChecksumAlgorithm.SHA1,
                ChecksumValidationAction.ChecksumAlgorithm.SHA256,
                ChecksumValidationAction.ChecksumAlgorithm.SHA384,
                ChecksumValidationAction.ChecksumAlgorithm.SHA512
        };

        for (ChecksumValidationAction.ChecksumAlgorithm algorithm : algorithms) {
            String expectedChecksum = calculateChecksum(testFileContent.getBytes(), algorithm.getAlgorithmName());

            ChecksumValidationAction action = new ChecksumValidationAction(algorithm, expectedChecksum);
            boolean result = action.execute(testDownload);

            assertTrue(result, "Validation should succeed for algorithm: " + algorithm.name());
            assertEquals(expectedChecksum.toLowerCase(), action.getActualChecksum().toLowerCase());
        }
    }

    @Test
    void shouldReturnTrueOnCancel() {
        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, "abc123");

        boolean cancelResult = action.cancel();

        assertTrue(cancelResult, "Cancel should return true");
        assertTrue(action.isCancelled(), "Action should be marked as cancelled");
    }

    @Test
    void shouldHandleEmptyFile() throws IOException {
        // Create empty file
        Path emptyFile = tempDir.resolve("empty-file.txt");
        Files.write(emptyFile, new byte[0]);

        Download emptyDownload = createTestDownload("empty-download",
                emptyFile.getParent(), emptyFile.getFileName().toString());

        // Expected checksum for empty file with MD5
        String expectedEmptyMD5 = "d41d8cd98f00b204e9800998ecf8427e";

        ChecksumValidationAction action = new ChecksumValidationAction(
                ChecksumValidationAction.ChecksumAlgorithm.MD5, expectedEmptyMD5);

        boolean result = action.execute(emptyDownload);

        assertTrue(result, "Validation should succeed for empty file with correct checksum");
        assertEquals(expectedEmptyMD5, action.getActualChecksum().toLowerCase());
    }

    // Helper methods

    private Download createTestDownload(String id, Path destination, String fileName) {
        Download download = new Download();
        download.setName(fileName);
        download.setDestination(destination);
        download.setStatus(Download.Status.COMPLETED);
        return download;
    }

    private String calculateChecksum(byte[] data, String algorithm) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] hashBytes = digest.digest(data);
        return bytesToHex(hashBytes);
    }

    /**
     * Convert byte array to hexadecimal string (java 21 compatible).
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
