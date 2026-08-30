package org.manager.download.action;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.manager.download.Download;

/**
 * After completion action that validates the checksum of downloaded files.
 * Supports MD5, SHA1, SHA-256, SHA-384, and SHA-512 algorithms.
 */
public class ChecksumValidationAction implements AfterCompletionAction {

    private static final Logger LOGGER = Logger.getLogger(ChecksumValidationAction.class.getName());

    public enum ChecksumAlgorithm {
        MD5("MD5"),
        SHA1("SHA-1"),
        SHA256("SHA-256"),
        SHA384("SHA-384"),
        SHA512("SHA-512");

        private final String algorithmName;

        ChecksumAlgorithm(String algorithmName) {
            this.algorithmName = algorithmName;
        }

        public String getAlgorithmName() {
            return algorithmName;
        }
    }

    private final ChecksumAlgorithm algorithm;
    private final String expectedChecksum;
    private final boolean caseSensitive;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private String actualChecksum;
    private Path validatedFile;

    /**
     * Creates a new ChecksumValidationAction.
     *
     * @param algorithm        The checksum algorithm to use
     * @param expectedChecksum The expected checksum value
     * @param caseSensitive    Whether checksum comparison should be case-sensitive
     */
    public ChecksumValidationAction(ChecksumAlgorithm algorithm, String expectedChecksum, boolean caseSensitive) {
        if (algorithm == null) {
            throw new IllegalArgumentException("Algorithm cannot be null");
        }
        if (expectedChecksum == null || expectedChecksum.trim().isEmpty()) {
            throw new IllegalArgumentException("Expected checksum cannot be null or empty");
        }

        this.algorithm = algorithm;
        this.expectedChecksum = expectedChecksum.trim();
        this.caseSensitive = caseSensitive;
    }

    /**
     * Creates a new ChecksumValidationAction with case-insensitive comparison.
     *
     * @param algorithm        The checksum algorithm to use
     * @param expectedChecksum The expected checksum value
     */
    public ChecksumValidationAction(ChecksumAlgorithm algorithm, String expectedChecksum) {
        this(algorithm, expectedChecksum, false);
    }

    @Override
    public boolean execute(Download download) {
        // Reset state. The cancelled flag is deliberately NOT reset here: a
        // cancel() issued before execute() must be honored, not wiped.
        actualChecksum = null;
        validatedFile = null;

        // Validate download has a destination
        if (download.getDestination() == null) {
            LOGGER.warning("Cannot validate checksum: download destination is not set");
            return false;
        }

        validatedFile = download.getPrimaryOutputPath();
        if (validatedFile == null) {
            LOGGER.warning("Cannot validate checksum: output path is unknown");
            return false;
        }

        // Check if file exists
        if (!Files.exists(validatedFile)) {
            LOGGER.warning("Cannot validate checksum: file does not exist: " + validatedFile);
            return false;
        }

        // Check if file is readable
        if (!Files.isReadable(validatedFile)) {
            LOGGER.warning("Cannot validate checksum: file is not readable: " + validatedFile);
            return false;
        }

        try {
            LOGGER.info("Starting checksum validation for file: " + validatedFile
                    + " using algorithm: " + algorithm.getAlgorithmName());

            // Calculate actual checksum
            actualChecksum = calculateChecksum(validatedFile, algorithm);

            if (cancelled.get()) {
                LOGGER.info("Checksum validation was cancelled");
                return false;
            }

            // Compare checksums
            boolean isValid = compareChecksums(expectedChecksum, actualChecksum, caseSensitive);

            if (isValid) {
                LOGGER.info("Checksum validation PASSED for file: " + validatedFile);
                LOGGER.info("Expected: " + expectedChecksum);
                LOGGER.info("Actual: " + actualChecksum);
                return true;
            } else {
                LOGGER.severe("Checksum validation FAILED for file: " + validatedFile);
                LOGGER.severe("Expected: " + expectedChecksum);
                LOGGER.severe("Actual: " + actualChecksum);
                LOGGER.severe("Algorithm: " + algorithm.getAlgorithmName());
                return false;
            }

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "I/O error during checksum validation: " + e.getMessage(), e);
            return false;
        } catch (NoSuchAlgorithmException e) {
            LOGGER.log(Level.SEVERE, "Unsupported checksum algorithm: " + algorithm.getAlgorithmName(), e);
            return false;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error during checksum validation: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public ActionType getType() {
        return ActionType.CHECKSUM_VALIDATION;
    }

    @Override
    public String getDescription() {
        return "Checksum validation (" + algorithm.name() + ") - Expected: "
                + truncateChecksum(expectedChecksum, 18)
                + (caseSensitive ? " (case-sensitive)" : " (case-insensitive)");
    }

    @Override
    public Severity getSeverity() {
        return Severity.HIGH;
    }

    @Override
    public boolean cancel() {
        cancelled.set(true);
        LOGGER.info("Checksum validation cancellation requested");
        return true;
    }

    /**
     * Calculate the checksum of a file using the specified algorithm.
     *
     * @param file      The file to calculate checksum for
     * @param algorithm The checksum algorithm
     * @return The calculated checksum as a hex string
     * @throws IOException              If an I/O error occurs
     * @throws NoSuchAlgorithmException If the algorithm is not supported
     */
    private String calculateChecksum(Path file, ChecksumAlgorithm algorithm)
            throws IOException, NoSuchAlgorithmException {

        MessageDigest digest = MessageDigest.getInstance(algorithm.getAlgorithmName());

        try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[8192]; // 8KB buffer
            int bytesRead;

            while ((bytesRead = bis.read(buffer)) != -1 && !cancelled.get()) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        if (cancelled.get()) {
            throw new IOException("Checksum calculation was cancelled");
        }

        byte[] hashBytes = digest.digest();
        return bytesToHex(hashBytes);
    }

    /**
     * Compare two checksums.
     *
     * @param expected      The expected checksum
     * @param actual        The actual checksum
     * @param caseSensitive Whether comparison should be case-sensitive
     * @return true if checksums match, false otherwise
     */
    private boolean compareChecksums(String expected, String actual, boolean caseSensitive) {
        if (expected == null || actual == null) {
            return false;
        }

        if (caseSensitive) {
            return expected.equals(actual);
        } else {
            return expected.toLowerCase(Locale.ROOT).equals(actual.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Truncate a checksum for display purposes.
     *
     * @param checksum  The checksum to truncate
     * @param maxLength Maximum length
     * @return Truncated checksum with ellipsis if needed
     */
    private String truncateChecksum(String checksum, int maxLength) {
        if (checksum.length() <= maxLength) {
            return checksum;
        }
        return checksum.substring(0, maxLength - 3) + "...";
    }

    /**
     * Get the checksum algorithm used by this action.
     *
     * @return The checksum algorithm
     */
    public ChecksumAlgorithm getAlgorithm() {
        return algorithm;
    }

    /**
     * Get the expected checksum value.
     *
     * @return The expected checksum
     */
    public String getExpectedChecksum() {
        return expectedChecksum;
    }

    /**
     * Get the actual calculated checksum (only available after execution).
     *
     * @return The actual checksum, or null if not yet calculated
     */
    public String getActualChecksum() {
        return actualChecksum;
    }

    /**
     * Get the file that was validated (only available after execution).
     *
     * @return The validated file path, or null if not yet executed
     */
    public Path getValidatedFile() {
        return validatedFile;
    }

    /**
     * Check if checksum comparison is case-sensitive.
     *
     * @return true if case-sensitive, false otherwise
     */
    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    /**
     * Check if the validation was cancelled.
     *
     * @return true if cancelled, false otherwise
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Convert byte array to hexadecimal string (Java 21 compatible).
     *
     * @param bytes The byte array to convert
     * @return Hexadecimal string representation
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * Create a ChecksumValidationAction from a checksum string that includes
     * the algorithm. Supports formats like "md5:abc123", "sha256:def456", etc.
     *
     * @param checksumWithAlgorithm Checksum string with algorithm prefix
     * @param caseSensitive         Whether comparison should be case-sensitive
     * @return ChecksumValidationAction instance
     * @throws IllegalArgumentException If the format is invalid or algorithm is
     *                                  unsupported
     */
    public static ChecksumValidationAction fromString(String checksumWithAlgorithm, boolean caseSensitive) {
        if (checksumWithAlgorithm == null || !checksumWithAlgorithm.contains(":")) {
            throw new IllegalArgumentException("Checksum must be in format 'algorithm:checksum'");
        }

        String[] parts = checksumWithAlgorithm.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid checksum format. Expected 'algorithm:checksum'");
        }

        String algorithmName = parts[0].trim().toUpperCase(Locale.ROOT);
        String checksum = parts[1].trim();

        ChecksumAlgorithm algorithm;
        try {
            // Handle common variations
            algorithm = switch (algorithmName) {
                case "MD5" ->
                    ChecksumAlgorithm.MD5;
                case "SHA1", "SHA-1" ->
                    ChecksumAlgorithm.SHA1;
                case "SHA256", "SHA-256" ->
                    ChecksumAlgorithm.SHA256;
                case "SHA384", "SHA-384" ->
                    ChecksumAlgorithm.SHA384;
                case "SHA512", "SHA-512" ->
                    ChecksumAlgorithm.SHA512;
                default ->
                    throw new IllegalArgumentException("Unsupported checksum algorithm: " + algorithmName);
            };
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported checksum algorithm: " + algorithmName, e);
        }

        return new ChecksumValidationAction(algorithm, checksum, caseSensitive);
    }

    /**
     * Create a ChecksumValidationAction from a checksum string with
     * case-insensitive comparison.
     *
     * @param checksumWithAlgorithm Checksum string with algorithm prefix
     * @return ChecksumValidationAction instance
     */
    public static ChecksumValidationAction fromString(String checksumWithAlgorithm) {
        return fromString(checksumWithAlgorithm, false);
    }
}
