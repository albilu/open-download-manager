package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests for the sibling-checksum detection parser (GNU, BSD, and bare-hex
 * formats, with algorithm-specific length validation).
 */
class ChecksumProbeTest {

    private static final String SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void parsesGnuStyleWithTwoSpacesAndFilename() {
        Optional<String> result = ChecksumProbe.parse(
                SHA256 + "  ubuntu-24.04.iso\n", "sha256");
        assertTrue(result.isPresent());
        assertEquals(SHA256, result.get());
    }

    @Test
    void parsesGnuStyleWithStarPrefix() {
        Optional<String> result = ChecksumProbe.parse(
                SHA256 + " *ubuntu.iso\n", "sha256");
        assertTrue(result.isPresent());
        assertEquals(SHA256, result.get());
    }

    @Test
    void parsesBsdStyle() {
        Optional<String> result = ChecksumProbe.parse(
                "SHA256 (ubuntu.iso) = " + SHA256.toUpperCase() + "\n", "sha256");
        assertTrue(result.isPresent());
        assertEquals(SHA256, result.get(), "digest should be lowercased");
    }

    @Test
    void parsesBareHex() {
        Optional<String> result = ChecksumProbe.parse(SHA256 + "\n", "sha256");
        assertTrue(result.isPresent());
        assertEquals(SHA256, result.get());
    }

    @Test
    void skipsCommentsAndBlankLines() {
        Optional<String> result = ChecksumProbe.parse(
                "# published checksums\n\n" + SHA256 + "  file\n", "sha256");
        assertTrue(result.isPresent());
    }

    @Test
    void picksMatchingEntryAmongMultiple() {
        String other = "a".repeat(64);
        Optional<String> result = ChecksumProbe.parse(
                other + "  other-file.zip\n" + SHA256 + "  target.iso\n", "sha256");
        // First valid entry wins; servers list their files in order
        assertTrue(result.isPresent());
        assertEquals(other, result.get());
    }

    @Test
    void rejectsWrongLengthForAlgorithm() {
        // 64 hex chars offered as sha1 (40 expected) must be rejected
        Optional<String> result = ChecksumProbe.parse(SHA256 + "\n", "sha1");
        assertTrue(result.isEmpty());
    }

    @Test
    void rejectsNonHexTokens() {
        Optional<String> result = ChecksumProbe.parse(
                "The quick brown fox  file.txt\n", "md5");
        assertTrue(result.isEmpty());
    }

    @Test
    void rejectsEmptyBody() {
        assertTrue(ChecksumProbe.parse("", "sha256").isEmpty());
        assertTrue(ChecksumProbe.parse(null, "sha256").isEmpty());
    }

    @Test
    void probeIgnoresNonHttpSchemesAndPathlessUrls() {
        assertTrue(ChecksumProbe.probe(null).isEmpty());
        assertTrue(ChecksumProbe.probe(java.net.URI.create(
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")).isEmpty());
        assertTrue(ChecksumProbe.probe(java.net.URI.create("https://example.com/")).isEmpty());
    }
}
